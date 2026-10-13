package com.amhs.integration;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Just enough MELSEC communication protocol (3E frame, binary) to read and write a block of words.
 *
 * <p>Two commands, written out rather than taken from a library, for the same reason as the Modbus
 * one next door: a batch read (0401) and a batch write (1401) is the whole of what a controller
 * interface needs, and the frame is a fixed header and a handful of little-endian fields.
 *
 * <p><b>Addresses are written the way the controller's documentation writes them</b> -- {@code D100},
 * {@code W1A0}, {@code R500} -- and the convention is the maker's, not a choice made here: the
 * number after a word register (D, R) is decimal, and after a link register (W, B) it is hex. Every
 * MELSEC manual and every engineer reading one works that way, so a document that says {@code W1A0}
 * means the register the engineer means and not the four hundred and sixteenth.
 *
 * <p>Only word devices are addressable. A bit device read a word at a time would answer, but what
 * came back would be sixteen unrelated bits, and nothing in a mapping document can say which of
 * them it meant. Where a command needs a bit, the layout puts it inside a word of the block.
 */
final class McProtocol implements WordBus {
 /** The most words one 3E request may carry. The protocol's own limit for a word batch. */
 private static final int MAX_WORDS=960;
 private static final int SUBHEADER_REQUEST=0x5000, SUBHEADER_ANSWER=0xD000;
 private static final int BATCH_READ=0x0401, BATCH_WRITE=0x1401;
 /** Four seconds, in the protocol's 250 ms units. What the controller waits before giving up. */
 private static final int WATCHDOG=16;

 private final String host; private final int port,timeoutMs;
 private Socket socket; private DataInputStream in; private DataOutputStream out;

 McProtocol(String host,int port,int timeoutMs){ this.host=host; this.port=port; this.timeoutMs=timeoutMs; }

 /** A device as the controller knows it: which family of registers, and which one of them. */
 record Device(int code,int number){ }

 /**
  * The devices this reads and writes, with the base their numbers are written in.
  *
  * <p>Hex for the link registers because that is how they are documented and wired; decimal for
  * the data and file registers, for the same reason.
  */
 private enum Word {
  D(0xA8,10), W(0xB4,16), R(0xAF,10), ZR(0xB0,10);
  final int code,base;
  Word(int code,int base){ this.code=code; this.base=base; }
 }

 static Device device(String address){
  var text=address==null?"":address.trim().toUpperCase(Locale.ROOT);
  for(var word:Word.values()){
   if(!text.startsWith(word.name())) continue;
   var digits=text.substring(word.name().length());
   // ZR starts with Z, and so would a Z device; a name that matched the prefix but has nothing
   // after it is not an address at all.
   if(digits.isEmpty()) continue;
   try{ return new Device(word.code,Integer.parseInt(digits,word.base)); }
   catch(NumberFormatException e){ break; }
  }
  throw new IllegalArgumentException("MC 주소가 아닙니다: "+address+" (D100·W1A0·R500·ZR1000 형식)");
 }

 @Override public synchronized void open() throws IOException {
  if(socket!=null&&socket.isConnected()&&!socket.isClosed()) return;
  close();
  var fresh=new Socket();
  fresh.connect(new InetSocketAddress(host,port),timeoutMs);
  fresh.setSoTimeout(timeoutMs);
  fresh.setTcpNoDelay(true);
  socket=fresh;
  in=new DataInputStream(new BufferedInputStream(fresh.getInputStream()));
  out=new DataOutputStream(new BufferedOutputStream(fresh.getOutputStream()));
 }

 @Override public synchronized int[] read(String area,int words) throws IOException {
  if(words<1||words>MAX_WORDS) throw new IOException("한 번에 읽을 수 있는 워드는 1~"+MAX_WORDS+"개입니다");
  open();
  var answer=exchange(BATCH_READ,device(area),words,null);
  if(answer.length<words*2) throw new IOException("읽기 응답의 길이가 맞지 않습니다");
  var values=new int[words];
  // The wire is little-endian, and a word read back is the word the controller holds.
  for(int i=0;i<words;i++) values[i]=(answer[i*2]&0xFF)|(answer[i*2+1]&0xFF)<<8;
  return values;
 }

 @Override public synchronized void write(String area,int[] values) throws IOException {
  if(values.length<1||values.length>MAX_WORDS) throw new IOException("한 번에 쓸 수 있는 워드는 1~"+MAX_WORDS+"개입니다");
  open();
  var payload=new byte[values.length*2];
  for(int i=0;i<values.length;i++){ payload[i*2]=(byte)values[i]; payload[i*2+1]=(byte)(values[i]>>8); }
  exchange(BATCH_WRITE,device(area),values.length,payload);
 }

 /**
  * One request and its answer.
  *
  * <p>3E has no transaction id: a request is answered before the next one goes, and this is
  * synchronized so that stays true. An end code other than zero is the controller saying no --
  * which is an answer, not a broken link, and so the command may be sent again.
  */
 private byte[] exchange(int command,Device device,int points,byte[] payload) throws IOException {
  var body=new ByteArrayOutputStream();
  var request=new DataOutputStream(body);
  writeShortLE(request,WATCHDOG);
  writeShortLE(request,command);
  writeShortLE(request,0);                       // subcommand 0000: addressed by word
  request.writeByte(device.number());            // device number, three bytes little-endian
  request.writeByte(device.number()>>8);
  request.writeByte(device.number()>>16);
  request.writeByte(device.code());
  writeShortLE(request,points);
  if(payload!=null) request.write(payload);

  writeShortLE(out,SUBHEADER_REQUEST);
  out.writeByte(0x00);                           // network number: the station itself
  out.writeByte(0xFF);                           // PC number: the connected station
  writeShortLE(out,0x03FF);                      // destination module I/O: the CPU
  out.writeByte(0x00);                           // destination module station
  writeShortLE(out,body.size());
  out.write(body.toByteArray());
  out.flush();

  int subheader=readShortLE();
  if(subheader!=SUBHEADER_ANSWER) throw new IOException("MC 응답이 아닙니다: 0x"+Integer.toHexString(subheader));
  in.readNBytes(5);                              // network, PC, I/O and station, echoed back
  int length=readShortLE();
  if(length<2) throw new IOException("MC 응답이 짧습니다");
  int end=readShortLE();
  var answer=new byte[length-2];
  in.readFully(answer);
  if(end!=0) throw new ControllerRefused("MELSEC",end);
  return answer;
 }

 private static void writeShortLE(DataOutput sink,int value) throws IOException {
  sink.writeByte(value); sink.writeByte(value>>8);
 }
 private int readShortLE() throws IOException {
  int low=in.readUnsignedByte();
  return low|in.readUnsignedByte()<<8;
 }

 @Override public synchronized void close(){
  var open=socket; socket=null; in=null; out=null;
  if(open!=null) try{ open.close(); }catch(IOException ignored){ }
 }
}
