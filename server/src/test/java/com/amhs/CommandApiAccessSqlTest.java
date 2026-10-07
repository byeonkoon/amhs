package com.amhs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * A17: every command the server takes -- each mapping that is not a read -- is refused without a
 * login (401) and refused to a level 1 login (403). Configuration also refuses level 2, and inventory
 * refuses level 2. The list is read from the server itself, so a command added later is covered
 * without anybody remembering to add it here. Logging in and out are the only writes open to all.
 */
@SpringBootTest(properties={"spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class CommandApiAccessSqlTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
 @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
 private static final long VISITOR=8001, OPERATOR=8002;
 private final List<String> tokens=new ArrayList<>();

 @AfterEach void endSessions(){
  for(String t:tokens) jdbc.update("UPDATE platform.user_session SET ended_at=now(),end_reason='TEST' WHERE token_sha256=?",sha256(t));
 }

 private static String sha256(String s){
  try{ return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
  catch(Exception e){ throw new IllegalStateException(e); }
 }
 /** A session for one of the seeded accounts, made the way a login makes it: only its hash is kept. */
 private String sessionFor(long account){
  String token="test-"+account+"-"+UUID.randomUUID();
  jdbc.update("INSERT INTO platform.user_session(account_id,token_sha256,expires_at) VALUES(?,?,now()+interval '10 minutes')",
   account,sha256(token));
  tokens.add(token);
  return token;
 }

 private record Command(HttpMethod method,String path){}
 /** Every mapping that changes something, with its path variables filled in. */
 private List<Command> commands(){
  var out=new ArrayList<Command>();
  mappings.getHandlerMethods().forEach((info,handler)->{
   var methods=info.getMethodsCondition().getMethods();
   var patterns=info.getPathPatternsCondition()==null?Set.<String>of():info.getPathPatternsCondition().getPatternValues();
   for(var m:methods){
    if(m.name().equals("GET")||m.name().equals("HEAD")||m.name().equals("OPTIONS")) continue;
    for(String p:patterns){
     if(!p.startsWith("/api/")) continue;
     out.add(new Command(HttpMethod.valueOf(m.name()),p.replaceAll("\\{[^}]+}","1")));
    }
   }
  });
  return out;
 }
 private int status(Command c,String token) throws Exception {
  var req=MockMvcRequestBuilders.request(c.method(),c.path()).contentType("application/json").content("{}");
  if(token!=null) req.header("Authorization","Bearer "+token);
  return mvc.perform(req).andReturn().getResponse().getStatus();
 }

 @Test void everyCommandIsRefusedWithoutALoginAndToALevelOneLogin() throws Exception {
  var commands=commands();
  assertThat(commands).hasSizeGreaterThan(40);
  String visitor=sessionFor(VISITOR);
  var unguarded=new ArrayList<String>();
  for(var c:commands){
   if(c.path().endsWith("/session")) continue;   // logging in and out
   int anonymous=status(c,null), level1=status(c,visitor);
   if(anonymous!=401||level1!=403) unguarded.add(c.method()+" "+c.path()+" -> "+anonymous+"/"+level1);
  }
  assertThat(unguarded).as("commands not refused (anonymous/level 1)").isEmpty();
 }

 @Test void configurationAndInventoryAreRefusedToLevelTwo() throws Exception {
  String operator=sessionFor(OPERATOR);
  var unguarded=new ArrayList<String>();
  for(var c:commands()){
   boolean configuration=c.path().contains("/configuration/"), inventory=c.path().contains("/inventory");
   if(!configuration&&!inventory) continue;
   int level2=status(c,operator);
   if(level2!=403) unguarded.add(c.method()+" "+c.path()+" -> "+level2);
  }
  assertThat(unguarded).as("configuration/inventory commands open to level 2").isEmpty();
 }
}
