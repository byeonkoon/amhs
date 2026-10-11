package com.amhs.integration.infrastructure;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Just enough Modbus TCP to read and write a block of holding registers.
 *
 * <p>Two function codes, written out rather than taken from a library: reading a block (3) and
 * writing a block (16) is the whole of what a controller interface needs, and the frame is a
 * six-byte header and a handful of bytes. A dependency for that would be more to trust, not less.
 *
 * <p>Addresses are written the way a controller's documentation writes them. {@code 40100} is the
 * hundredth holding register in the 4x convention and goes on the wire as 99; a plain number is
 * taken as the wire address it already is.
 */
final class ModbusTcp implements AutoCloseable {
 private static final int MAX_REGISTERS=125;
 private final String host; private final int port,unitId,timeoutMs;
 private Socket socket; private DataInputStream in; private DataOutputStream out;
 private int transaction;

 ModbusTcp(String host,int port,int unitId,int timeoutMs){
  this.host=host; this.port=port; this.unitId=unitId; this.timeoutMs=timeoutMs;
 }

 /** What the controller itself refused, as it said it. Not a link problem. */
 static final class ModbusRefused extends IOException {
  final int code;
  ModbusRefused(int code){ super("Modbus exception "+code); this.code=code; }
 }

 static int wireAddress(String address){
  var text=address.trim();
  long value=Long.parseLong(text);
  // 4xxxx is the documentation's way of saying "holding register"; the wire counts from zero.
  return (int)(text.length()==5&&text.charAt(0)=='4'?value-40001:value);
 }

 boolean isOpen(){ return socket!=null&&socket.isConnected()&&!socket.isClosed(); }

 synchronized void open() throws IOException {
  if(isOpen()) return;
  close();
  var fresh=new Socket();
  fresh.connect(new InetSocketAddress(host,port),timeoutMs);
  fresh.setSoTimeout(timeoutMs);
  fresh.setTcpNoDelay(true);
  socket=fresh;
  in=new DataInputStream(new BufferedInputStream(fresh.getInputStream()));
  out=new DataOutputStream(new BufferedOutputStream(fresh.getOutputStream()));
 }

 synchronized int[] read(int address,int count) throws IOException {
  if(count<1||count>MAX_REGISTERS) throw new IOException("한 번에 읽을 수 있는 레지스터는 1~"+MAX_REGISTERS+"개입니다");
  open();
  var pdu=new byte[]{3,(byte)(address>>8),(byte)address,(byte)(count>>8),(byte)count};
  var answer=exchange(pdu);
  if(answer.length<2||(answer[1]&0xFF)!=count*2) throw new IOException("읽기 응답의 길이가 맞지 않습니다");
  var values=new int[count];
  for(int i=0;i<count;i++) values[i]=(answer[2+i*2]&0xFF)<<8|(answer[3+i*2]&0xFF);
  return values;
 }

 synchronized void write(int address,int[] values) throws IOException {
  if(values.length<1||values.length>123) throw new IOException("한 번에 쓸 수 있는 레지스터는 1~123개입니다");
  open();
  var pdu=new byte[6+values.length*2];
  pdu[0]=16; pdu[1]=(byte)(address>>8); pdu[2]=(byte)address;
  pdu[3]=(byte)(values.length>>8); pdu[4]=(byte)values.length; pdu[5]=(byte)(values.length*2);
  for(int i=0;i<values.length;i++){ pdu[6+i*2]=(byte)(values[i]>>8); pdu[7+i*2]=(byte)values[i]; }
  var answer=exchange(pdu);
  if(answer.length<5) throw new IOException("쓰기 응답이 짧습니다");
 }

 /** One request and its answer. The transaction id is checked: an answer to an older one is not this one. */
 private byte[] exchange(byte[] pdu) throws IOException {
  int id=++transaction&0xFFFF;
  out.writeShort(id); out.writeShort(0); out.writeShort(pdu.length+1); out.writeByte(unitId);
  out.write(pdu); out.flush();
  while(true){
   int answered=in.readUnsignedShort();
   in.readUnsignedShort();                       // protocol id, always zero
   int length=in.readUnsignedShort();
   in.readUnsignedByte();                        // unit id
   var body=new byte[Math.max(0,length-1)];
   in.readFully(body);
   if(answered!=id) continue;                    // an answer to something older; the next one is ours
   if(body.length>0&&(body[0]&0x80)!=0) throw new ModbusRefused(body.length>1?body[1]&0xFF:0);
   return body;
  }
 }

 @Override public synchronized void close(){
  var open=socket; socket=null; in=null; out=null;
  if(open!=null) try{ open.close(); }catch(IOException ignored){ }
 }
}
