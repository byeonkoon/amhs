package com.amhs.integration.infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * The key material the links use, and the one place that decides whether a peer is who it says.
 *
 * <p>Three things are deliberate here.
 *
 * <p><b>The material is not in the database.</b> A private key in a row is a private key in every
 * backup, every replica and every dump of that database. It is in files the operator puts on the
 * server and names in {@code amhs.device.tls.*}, so the thing that protects the link is kept the
 * way the rest of a plant keeps keys.
 *
 * <p><b>There is no fallback.</b> A link that asks for TLS and cannot have it does not open, and
 * says so. Quietly carrying on in plaintext is the single behaviour that would make all of this
 * pointless, because nothing above the link could tell the difference.
 *
 * <p><b>The files are read when they are used, and again when they change.</b> A certificate is
 * renewed while a plant is running; needing a restart to pick one up means a plant chooses between
 * a restart and an expired certificate, and it will choose neither until the link stops working.
 *
 * <p>The procedure an operator and a gateway follow is {@code docs/link-security.md}.
 */
@Component
public class DeviceTls {
 private static final Logger log=LoggerFactory.getLogger(DeviceTls.class);
 private final String keystore,keystorePassword,keyPassword,truststore,truststorePassword;
 /** What was loaded, and from files as they stood when it was. */
 private volatile Loaded loaded;

 /**
  * Public so the rule about missing material can be stated as a test.
  *
  * <p>"A link that asks for TLS and cannot have it does not open" is the whole of the protection
  * here, and it is worth being able to show the four ways it can be unavailable rather than only
  * the one a running server happens to be in.
  */
 public DeviceTls(@Value("${amhs.device.tls.keystore:}") String keystore,
           @Value("${amhs.device.tls.keystore-password:}") String keystorePassword,
           @Value("${amhs.device.tls.key-password:}") String keyPassword,
           @Value("${amhs.device.tls.truststore:}") String truststore,
           @Value("${amhs.device.tls.truststore-password:}") String truststorePassword){
  this.keystore=keystore; this.keystorePassword=keystorePassword;
  this.keyPassword=keyPassword.isBlank()?keystorePassword:keyPassword;
  this.truststore=truststore; this.truststorePassword=truststorePassword;
 }

 /** The context, and the state of the files it was built from. */
 private record Loaded(SSLContext context,long keystoreAt,long truststoreAt,String problem){}

 /** Why TLS cannot be had right now, or null when it can. */
 public String problem(){
  var state=state();
  return state==null?"TLS_NOT_CONFIGURED":state.problem();
 }
 /** Whether a link that asks for TLS can have it. */
 public boolean available(){ return problem()==null; }

 /**
  * Dials the far side and makes sure it is who the profile says.
  *
  * <p>The plain socket is connected first and then wrapped, which is what lets the name being
  * checked be the one the profile gave rather than the address that was dialled. A plant is dialled
  * by address as often as by name, and a certificate issued to a name cannot be checked against an
  * address without somebody saying which name was meant.
  */
 public Socket connect(String host,int port,int timeoutMs,String peerName) throws IOException {
  var state=state();
  if(state==null||state.problem()!=null) throw new TlsUnavailable(problem());
  var plain=new Socket();
  plain.connect(new InetSocketAddress(host,port),timeoutMs);
  try{
   var ssl=(SSLSocket)state.context().getSocketFactory()
    .createSocket(plain,peerName==null||peerName.isBlank()?host:peerName,port,true);
   var parameters=ssl.getSSLParameters();
   // What a browser does. Without it a certificate signed by the right authority for the wrong
   // machine passes, and "signed by our authority" is not the question anybody meant to ask.
   parameters.setEndpointIdentificationAlgorithm("HTTPS");
   ssl.setSSLParameters(parameters);
   // The handshake gets the same patience as the connection and no more. A socket that opens and
   // then says nothing would otherwise hold this thread for ever -- which is what something
   // listening on the right port with the wrong protocol does, and the first thing a test found.
   handshake(ssl,timeoutMs);
   return ssl;
  }catch(IOException e){
   try{ plain.close(); }catch(IOException ignored){ }
   throw e;
  }
 }

 /**
  * A port for gateways to dial in to, where arriving means proving who you are.
  *
  * <p>Client authentication is required rather than wanted: "wanted" lets a connection with no
  * certificate through, and a connection with no certificate is the arrangement this replaces.
  */
 public SSLServerSocket listen(String bind,int port) throws IOException {
  var state=state();
  if(state==null||state.problem()!=null) throw new TlsUnavailable(problem());
  var server=(SSLServerSocket)state.context().getServerSocketFactory().createServerSocket();
  server.setReuseAddress(true);
  server.setNeedClientAuth(true);
  server.bind(new InetSocketAddress(bind,port));
  return server;
 }

 /** A socket factory for a client that makes its own sockets, with the same name checking. */
 public SSLSocketFactory factoryFor(String peerName) throws IOException {
  var state=state();
  if(state==null||state.problem()!=null) throw new TlsUnavailable(problem());
  return new Verifying(state.context().getSocketFactory(),peerName);
 }

 /**
  * Shakes hands, and gives up if the far side will not.
  *
  * <p>A TLS handshake has no deadline of its own: something that accepts the socket and then says
  * nothing holds the thread until the far side closes, which may be never. So the socket is given a
  * read deadline for the handshake and put back to blocking afterwards, because everything after
  * the handshake is a reader that is meant to wait.
  */
 public static void handshake(SSLSocket socket,int timeoutMs) throws IOException {
  int was=socket.getSoTimeout();
  socket.setSoTimeout(timeoutMs<=0?10_000:timeoutMs);
  try{ socket.startHandshake(); }
  finally{ socket.setSoTimeout(was); }
 }

 /**
  * The name on the far side's certificate, or null when it presented none.
  *
  * <p>The subject's common name, and the subject alternative names with it, because a certificate
  * issued today carries the name there rather than in the subject.
  */
 public static String peerName(SSLSocket socket){
  try{
   var peer=socket.getSession().getPeerCertificates();
   if(peer.length==0||!(peer[0] instanceof X509Certificate certificate)) return null;
   return commonName(certificate.getSubjectX500Principal().getName());
  }catch(SSLPeerUnverifiedException e){ return null; }
 }
 /** Whether the far side's certificate carries this name, in its subject or among its other names. */
 public static boolean presents(SSLSocket socket,String name){
  if(name==null||name.isBlank()) return true;
  try{
   var peer=socket.getSession().getPeerCertificates();
   if(peer.length==0||!(peer[0] instanceof X509Certificate certificate)) return false;
   if(name.equalsIgnoreCase(commonName(certificate.getSubjectX500Principal().getName()))) return true;
   var others=certificate.getSubjectAlternativeNames();
   if(others==null) return false;
   for(var entry:others)
    if(entry.size()>1&&entry.get(1) instanceof String other&&name.equalsIgnoreCase(other)) return true;
   return false;
  }catch(Exception e){ return false; }
 }
 private static String commonName(String distinguishedName){
  for(var part:distinguishedName.split(",")){
   var trimmed=part.trim();
   if(trimmed.regionMatches(true,0,"CN=",0,3)) return trimmed.substring(3);
  }
  return null;
 }

 /** What a link gets when it asks for TLS the server cannot give it. Nothing is written. */
 public static final class TlsUnavailable extends IOException {
  TlsUnavailable(String code){ super(code==null?"TLS_NOT_CONFIGURED":code); }
 }

 /**
  * The loaded material, reloading it when a file has changed since it was read.
  *
  * <p>Null when nothing is configured at all, which is a different answer from "configured and
  * broken": one is a site that has not asked for TLS, the other is a site that has and needs to
  * be told what is wrong.
  */
 private Loaded state(){
  if(keystore.isBlank()&&truststore.isBlank()) return null;
  long keyAt=modifiedAt(keystore), trustAt=modifiedAt(truststore);
  var held=loaded;
  if(held!=null&&held.keystoreAt()==keyAt&&held.truststoreAt()==trustAt) return held;
  var fresh=load(keyAt,trustAt);
  loaded=fresh;
  if(fresh.problem()!=null) log.error("device TLS material cannot be used: {}",fresh.problem());
  else log.info("device TLS material loaded (keystore {}, truststore {})",
   keystore.isBlank()?"none":keystore,truststore.isBlank()?"none":truststore);
  return fresh;
 }
 private Loaded load(long keyAt,long trustAt){
  try{
   KeyManager[] keys=null;
   if(!keystore.isBlank()){
    if(keyAt==0) return new Loaded(null,keyAt,trustAt,"TLS_KEYSTORE_MISSING");
    var store=read(keystore,keystorePassword);
    var factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store,keyPassword.toCharArray());
    keys=factory.getKeyManagers();
   }
   TrustManager[] trust=null;
   if(!truststore.isBlank()){
    if(trustAt==0) return new Loaded(null,keyAt,trustAt,"TLS_TRUSTSTORE_MISSING");
    var store=read(truststore,truststorePassword);
    var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    trust=factory.getTrustManagers();
   }else{
    // Without a truststore, the only certificates trusted would be the public web's authorities,
    // which have never heard of this plant's gateways. Saying so is the honest answer; falling
    // back to them would mean trusting everything and nothing.
    return new Loaded(null,keyAt,trustAt,"TLS_TRUSTSTORE_REQUIRED");
   }
   var context=SSLContext.getInstance("TLS");
   context.init(keys,trust,null);
   return new Loaded(context,keyAt,trustAt,null);
  }catch(Exception e){
   return new Loaded(null,keyAt,trustAt,"TLS_MATERIAL_UNREADABLE");
  }
 }
 private static KeyStore read(String path,String password) throws Exception {
  var store=KeyStore.getInstance(path.toLowerCase(java.util.Locale.ROOT).endsWith(".jks")?"JKS":"PKCS12");
  try(InputStream in=Files.newInputStream(Path.of(path))){ store.load(in,password.toCharArray()); }
  return store;
 }
 private static long modifiedAt(String path){
  if(path.isBlank()) return 0;
  try{ return Files.getLastModifiedTime(Path.of(path)).toMillis(); }
  catch(IOException e){ return 0; }
 }

 /**
  * A factory whose sockets check the name the profile named.
  *
  * <p>For a client that makes its own connections -- an MQTT client, say -- which would otherwise
  * check the address it was handed, or nothing at all.
  */
 private static final class Verifying extends SSLSocketFactory {
  private final SSLSocketFactory inner; private final String peerName;
  Verifying(SSLSocketFactory inner,String peerName){ this.inner=inner; this.peerName=peerName; }
  private Socket verify(Socket socket,String host){
   if(socket instanceof SSLSocket ssl){
    var parameters=ssl.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    ssl.setSSLParameters(parameters);
   }
   return socket;
  }
  private String name(String host){ return peerName==null||peerName.isBlank()?host:peerName; }
  @Override public String[] getDefaultCipherSuites(){ return inner.getDefaultCipherSuites(); }
  @Override public String[] getSupportedCipherSuites(){ return inner.getSupportedCipherSuites(); }
  @Override public Socket createSocket(Socket s,String host,int port,boolean autoClose) throws IOException {
   return verify(inner.createSocket(s,name(host),port,autoClose),host);
  }
  @Override public Socket createSocket(String host,int port) throws IOException {
   var plain=new Socket(host,port);
   return verify(inner.createSocket(plain,name(host),port,true),host);
  }
  @Override public Socket createSocket(String host,int port,java.net.InetAddress local,int localPort) throws IOException {
   var plain=new Socket(host,port,local,localPort);
   return verify(inner.createSocket(plain,name(host),port,true),host);
  }
  @Override public Socket createSocket(java.net.InetAddress host,int port) throws IOException {
   var plain=new Socket(host,port);
   return verify(inner.createSocket(plain,name(host.getHostName()),port,true),host.getHostName());
  }
  @Override public Socket createSocket(java.net.InetAddress host,int port,java.net.InetAddress local,int localPort) throws IOException {
   var plain=new Socket(host,port,local,localPort);
   return verify(inner.createSocket(plain,name(host.getHostName()),port,true),host.getHostName());
  }
 }
}
