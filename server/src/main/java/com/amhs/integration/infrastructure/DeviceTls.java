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
import java.security.cert.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * The key material the links use, and the one place that decides whether a peer is who it says.
 *
 * <p>Four things are deliberate here.
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
 * <p><b>A withdrawn certificate is withdrawn here, from a file.</b> Until now this read a signature
 * and an expiry date, so a gateway taken off the floor kept its certificate until that date: the
 * way to stop it was to change the authority, which means new material for every other gateway
 * too. A revocation list fixes that. It is a file the operator keeps beside the trust store, and
 * not a fetch from a distribution point or an OCSP responder, for two reasons -- a controller
 * should not make a connection to the outside to decide whether it may make a connection to the
 * floor, and a responder that cannot be reached turns into "assume it is fine", which is the
 * downgrade this class exists to refuse. What it costs is that a revocation takes effect when the
 * file is replaced, so the list has to be kept the way keys are kept, and {@link #warning()} says
 * so while there is still time.
 *
 * <p>The procedure an operator and a gateway follow is {@code docs/link-security.md}.
 */
@Component
public class DeviceTls {
 private static final Logger log=LoggerFactory.getLogger(DeviceTls.class);
 private final String keystore,keystorePassword,keyPassword,truststore,truststorePassword;
 /** The revocation lists, as the operator named them: files, or directories holding them. */
 private final List<String> crlPaths;
 private final Clock clock;
 /** How long before a list runs out the check starts saying so. */
 private static final Duration SOON=Duration.ofDays(7);
 /** What was loaded, and from files as they stood when it was. */
 private volatile Loaded loaded;

 /**
  * Public so the rule about missing material can be stated as a test.
  *
  * <p>"A link that asks for TLS and cannot have it does not open" is the whole of the protection
  * here, and it is worth being able to show the four ways it can be unavailable rather than only
  * the one a running server happens to be in.
  *
  * <p>Annotated because there are two of these now, and the other one is for tests.
  */
 @org.springframework.beans.factory.annotation.Autowired
 public DeviceTls(@Value("${amhs.device.tls.keystore:}") String keystore,
           @Value("${amhs.device.tls.keystore-password:}") String keystorePassword,
           @Value("${amhs.device.tls.key-password:}") String keyPassword,
           @Value("${amhs.device.tls.truststore:}") String truststore,
           @Value("${amhs.device.tls.truststore-password:}") String truststorePassword,
           @Value("${amhs.device.tls.crls:}") String crls){
  this(keystore,keystorePassword,keyPassword,truststore,truststorePassword,crls,Clock.systemUTC());
 }
 /**
  * The same, against a clock a test can move.
  *
  * <p>A list that has run out is the one state here that cannot be made on demand: it arrives by
  * waiting. So the date this asks the question on is given rather than taken, and what a plant
  * does with a list that ran out last night is something that can be shown now.
  */
 public DeviceTls(String keystore,String keystorePassword,String keyPassword,String truststore,
           String truststorePassword,String crls,Clock clock){
  this.keystore=keystore; this.keystorePassword=keystorePassword;
  this.keyPassword=keyPassword.isBlank()?keystorePassword:keyPassword;
  this.truststore=truststore; this.truststorePassword=truststorePassword;
  this.crlPaths=crls.isBlank()?List.of()
   :java.util.Arrays.stream(crls.split(",")).map(String::trim).filter(s->!s.isBlank()).toList();
  this.clock=clock;
 }

 /**
  * The context, the state of the files it was built from, and when its verdict stops being true.
  *
  * <p>{@code validUntil} is the next moment a date could change the answer -- the day a list runs
  * out, or a week before that, whichever is still ahead. A verdict that only a changed file can
  * alter holds for ever.
  */
 private record Loaded(SSLContext context,long keystoreAt,long truststoreAt,long crlsAt,
            String problem,String warning,long validUntil){}

 /** Why TLS cannot be had right now, or null when it can. */
 public String problem(){
  var state=state();
  return state==null?"TLS_NOT_CONFIGURED":state.problem();
 }
 /**
  * What is not wrong yet but will be, or null.
  *
  * <p>There is one of these, and it is the revocation list running out. Everything else here either
  * works or does not; a list is the only part that stops working on a date while nothing about the
  * site has changed, and the day it does, every secured link stops. That is worth saying a week
  * early in the check somebody reads before a shift.
  */
 public String warning(){
  var state=state();
  return state==null?null:state.warning();
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
  long keyAt=modifiedAt(keystore), trustAt=modifiedAt(truststore), crlAt=crlsModifiedAt();
  var held=loaded;
  // Two ways the answer can go out of date: a file changed, or a date passed. The second is why
  // what was loaded carries the moment its own verdict stops being true -- without it, a list in
  // its last week would have to be judged again on every connection, which means reading the
  // stores and building a context again on every connection.
  if(held!=null&&held.keystoreAt()==keyAt&&held.truststoreAt()==trustAt&&held.crlsAt()==crlAt
   &&clock.millis()<held.validUntil()) return held;
  var fresh=load(keyAt,trustAt,crlAt);
  loaded=fresh;
  if(fresh.problem()!=null) log.error("device TLS material cannot be used: {}",fresh.problem());
  else if(held==null||held.crlsAt()!=crlAt||held.keystoreAt()!=keyAt||held.truststoreAt()!=trustAt)
   log.info("device TLS material loaded (keystore {}, truststore {}, revocation {})",
    keystore.isBlank()?"none":keystore,truststore.isBlank()?"none":truststore,
    crlPaths.isEmpty()?"not checked":String.join(" ",crlPaths));
  if(fresh.warning()!=null) log.warn("device TLS: {}",fresh.warning());
  return fresh;
 }
 private Loaded load(long keyAt,long trustAt,long crlAt){
  try{
   KeyManager[] keys=null;
   if(!keystore.isBlank()){
    if(keyAt==0) return new Loaded(null,keyAt,trustAt,crlAt,"TLS_KEYSTORE_MISSING",null,Long.MAX_VALUE);
    var store=read(keystore,keystorePassword);
    var factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store,keyPassword.toCharArray());
    keys=factory.getKeyManagers();
   }
   TrustManager[] trust;
   String warning=null;
   List<X509CRL> withdrawn=List.of();
   if(!truststore.isBlank()){
    if(trustAt==0) return new Loaded(null,keyAt,trustAt,crlAt,"TLS_TRUSTSTORE_MISSING",null,Long.MAX_VALUE);
    var store=read(truststore,truststorePassword);
    try{ withdrawn=revocations(); }
    catch(java.nio.file.NoSuchFileException e){
     return new Loaded(null,keyAt,trustAt,crlAt,"TLS_CRL_MISSING",null,Long.MAX_VALUE);
    }catch(Exception e){
     return new Loaded(null,keyAt,trustAt,crlAt,"TLS_CRL_UNREADABLE",null,Long.MAX_VALUE);
    }
    // Asked for and nothing there: a named directory with no list in it. Not the same as a file
    // that would not parse, and not the same as a site that never asked.
    if(!crlPaths.isEmpty()&&withdrawn.isEmpty())
     return new Loaded(null,keyAt,trustAt,crlAt,"TLS_CRL_MISSING",null,Long.MAX_VALUE);
    // A list that has run out is refused rather than used. It no longer says what has been
    // withdrawn since it was written, and treating it as though it did is the one thing a
    // revocation check must not do -- it would read as protection while protecting nothing.
    var age=staleness(withdrawn,clock.instant());
    if("TLS_CRL_STALE".equals(age)) return new Loaded(null,keyAt,trustAt,crlAt,age,null,Long.MAX_VALUE);
    warning=age;
    trust=trustManagers(store,withdrawn);
   }else{
    // Without a truststore, the only certificates trusted would be the public web's authorities,
    // which have never heard of this plant's gateways. Saying so is the honest answer; falling
    // back to them would mean trusting everything and nothing.
    return new Loaded(null,keyAt,trustAt,crlAt,"TLS_TRUSTSTORE_REQUIRED",null,Long.MAX_VALUE);
   }
   var context=SSLContext.getInstance("TLS");
   context.init(keys,trust,null);
   return new Loaded(context,keyAt,trustAt,crlAt,null,warning,verdictHolds(withdrawn));
  }catch(Exception e){
   return new Loaded(null,keyAt,trustAt,crlAt,"TLS_MATERIAL_UNREADABLE",null,Long.MAX_VALUE);
  }
 }

 /**
  * The trust managers, checking revocation when there is a list and not pretending to when there
  * is none.
  *
  * <p>With a list, two options are set and both matter. {@code PREFER_CRLS} asks the list first,
  * and {@code NO_FALLBACK} stops it asking an OCSP responder when the list does not cover a
  * certificate -- without it, a chain this site has no list for would be decided by a connection
  * to somebody else's server. Soft failure is deliberately not among them: a certificate whose
  * standing cannot be established does not get in.
  */
 private static TrustManager[] trustManagers(KeyStore store,List<X509CRL> withdrawn) throws Exception {
  if(withdrawn.isEmpty()){
   var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
   factory.init(store);
   return factory.getTrustManagers();
  }
  var parameters=new PKIXBuilderParameters(store,new X509CertSelector());
  var checker=(PKIXRevocationChecker)CertPathBuilder.getInstance("PKIX").getRevocationChecker();
  checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.PREFER_CRLS,
                                PKIXRevocationChecker.Option.NO_FALLBACK));
  parameters.addCertPathChecker(checker);
  parameters.setRevocationEnabled(false);   // the checker above is the one that does it
  parameters.addCertStore(CertStore.getInstance("Collection",
   new CollectionCertStoreParameters(withdrawn)));
  var factory=TrustManagerFactory.getInstance("PKIX");
  factory.init(new CertPathTrustManagerParameters(parameters));
  return factory.getTrustManagers();
 }

 /**
  * Whether a list still says anything, as of a given moment.
  *
  * <p>Given the date rather than taking it, because this is the one judgement here that cannot be
  * reached by arranging files: a list runs out by the calendar, and a test that waits a month for
  * that is a test nobody runs. Returns the refusal, the warning, or null.
  *
  * <p>A list with no next date is not treated as having run out. RFC 5280 allows one, and it means
  * the issuer is saying this list is final rather than that it was forgotten.
  */
 private static String staleness(Collection<X509CRL> withdrawn,Instant at){
  String warning=null;
  for(var list:withdrawn){
   var next=list.getNextUpdate();
   if(next==null) continue;
   if(!next.toInstant().isAfter(at)) return "TLS_CRL_STALE";
   if(next.toInstant().isBefore(at.plus(SOON))) warning="TLS_CRL_EXPIRES_SOON";
  }
  return warning;
 }

 /**
  * The next moment a date -- rather than an edit -- could change what {@link #staleness} answers.
  *
  * <p>For a list in date that is the week-out mark; for one already inside its last week, the day
  * it runs out. Earliest across the lists, because the first to go decides. Nothing with a date on
  * it means nothing to come back for.
  */
 private long verdictHolds(Collection<X509CRL> withdrawn){
  long next=Long.MAX_VALUE, now=clock.millis();
  for(var list:withdrawn){
   var runsOut=list.getNextUpdate();
   if(runsOut==null) continue;
   long warnAt=runsOut.getTime()-SOON.toMillis();
   next=Math.min(next,warnAt>now?warnAt:runsOut.getTime());
  }
  return next;
 }

 /**
  * Every list the operator named: each path a file, or a directory of them.
  *
  * <p>A file that holds no list is an error rather than an absence -- something is there and it is
  * not what it was named as, which is worth a different answer from a place with nothing in it.
  */
 private List<X509CRL> revocations() throws Exception {
  var found=new ArrayList<X509CRL>();
  var factory=CertificateFactory.getInstance("X.509");
  for(var named:crlPaths){
   var path=Path.of(named);
   for(var file:Files.isDirectory(path)?listed(path):List.of(path)){
    int before=found.size();
    try(InputStream in=Files.newInputStream(file)){
     for(var one:factory.generateCRLs(in)) if(one instanceof X509CRL list) found.add(list);
    }
    if(found.size()==before) throw new CRLException("no revocation list in "+file.getFileName());
   }
  }
  return found;
 }
 private static List<Path> listed(Path directory) throws IOException {
  try(var files=Files.list(directory)){ return files.filter(Files::isRegularFile).sorted().toList(); }
 }
 /**
  * A number that changes when the lists do.
  *
  * <p>Folded rather than maximised, so that a list being removed from a directory counts as a
  * change too: the newest date on its own would not move.
  */
 private long crlsModifiedAt(){
  long folded=0;
  for(var named:crlPaths){
   var path=Path.of(named);
   try{
    if(Files.isDirectory(path))
     for(var file:listed(path)) folded=folded*31+Files.getLastModifiedTime(file).toMillis();
    else folded=folded*31+modifiedAt(named);
   }catch(IOException e){ /* gone, which load() answers for */ }
  }
  return folded;
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
