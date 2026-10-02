package com.amhs;
import com.amhs.platform.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Accounts decide who may do anything at all, so the cases that matter are the ones where getting
 * it wrong either hands out a token twice or leaves nobody able to hand one out.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class AccountDirectorySqlTest {
 @Autowired AccountDirectory accounts; @Autowired AccountQuery directory; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1;
 private final String run=UUID.randomUUID().toString().substring(0,6);

 @AfterEach void clean(){ jdbc.update("DELETE FROM platform.account WHERE code LIKE ?",run+"%"); }
 private AccountSettings settings(String suffix,String role,boolean enabled){
  return new AccountSettings(run+"-"+suffix,run+" "+suffix,role,enabled);
 }
 private void rejected(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{
   assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
  });
 }

 @Test void anIssuedTokenWorksOnceAndIsNeverReadableAgain(){
  var issued=accounts.create(SITE,settings("op","OPERATOR",true));
  assertThat(issued.token()).isNotBlank().hasSizeGreaterThanOrEqualTo(32);
  // The token identifies its account, and nothing in the account view carries it back.
  var resolved=directory.findByTokenHash(PrincipalResolver.sha256Hex(issued.token())).orElseThrow();
  assertThat(resolved.role()).isEqualTo(Role.OPERATOR);
  assertThat(resolved.code()).isEqualTo(run+"-op");
  assertThat(accounts.list(SITE)).anySatisfy(a -> {
   assertThat(a.code()).isNotNull();
   assertThat(a.toString()).doesNotContain(issued.token());
  });
  assertThat(accounts.list(SITE).stream().filter(a -> a.code().equals(run+"-op")))
   .singleElement().satisfies(a -> assertThat(a.hasToken()).isTrue());
 }

 @Test void reissuingRetiresTheOldTokenAndRevokingLeavesTheAccount(){
  var first=accounts.create(SITE,settings("re","ENGINEER",true));
  var id=first.account().id();
  var second=accounts.reissueToken(SITE,id);
  assertThat(second.token()).isNotEqualTo(first.token());
  assertThat(directory.findByTokenHash(PrincipalResolver.sha256Hex(first.token()))).isEmpty();
  assertThat(directory.findByTokenHash(PrincipalResolver.sha256Hex(second.token()))).isPresent();
  // Revoking stops access without deleting the record, so its audit trail keeps an owner.
  var revoked=accounts.revokeToken(SITE,id);
  assertThat(revoked.hasToken()).isFalse();
  assertThat(directory.findByTokenHash(PrincipalResolver.sha256Hex(second.token()))).isEmpty();
  assertThat(accounts.list(SITE)).anyMatch(a -> a.id()==id);
 }

 @Test void aDisabledAccountCannotGetIn(){
  var issued=accounts.create(SITE,settings("off","OPERATOR",true));
  var hash=PrincipalResolver.sha256Hex(issued.token());
  assertThat(directory.findByTokenHash(hash)).isPresent();
  accounts.update(SITE,issued.account().id(),issued.account().version(),settings("off","OPERATOR",false));
  assertThat(directory.findByTokenHash(hash)).isEmpty();
 }

 @Test void theLastWayInCannotBeTakenAway(){
  int existing=jdbc.queryForObject(
   "SELECT count(*) FROM platform.account WHERE site_id=? AND role='ADMIN' AND enabled=TRUE",Integer.class,SITE);
  var admin=accounts.create(SITE,settings("adm","ADMIN",true));
  var id=admin.account().id(); var version=admin.account().version();
  if(existing==0){
   // Nobody else may hand the authority back, so neither demoting nor disabling is allowed.
   rejected(()->accounts.update(SITE,id,version,settings("adm","OPERATOR",true)),409,"LAST_ADMIN");
   rejected(()->accounts.update(SITE,id,version,settings("adm","ADMIN",false)),409,"LAST_ADMIN");
   // With a second administrator in place the first may step down.
   var second=accounts.create(SITE,settings("adm2","ADMIN",true));
   var stepped=accounts.update(SITE,id,version,settings("adm","OPERATOR",true));
   assertThat(stepped.role()).isEqualTo(Role.OPERATOR);
   accounts.update(SITE,second.account().id(),second.account().version(),settings("adm2","ADMIN",true));
  }else{
   var stepped=accounts.update(SITE,id,version,settings("adm","OPERATOR",true));
   assertThat(stepped.role()).isEqualTo(Role.OPERATOR);
  }
 }

 @Test void codesAreUniqueAndRolesAreChecked(){
  var first=accounts.create(SITE,settings("dup","VIEWER",true));
  rejected(()->accounts.create(SITE,settings("dup","VIEWER",true)),409,"ACCOUNT_CODE_IN_USE");
  rejected(()->accounts.create(SITE,settings("bad","SUPERUSER",true)),422,"UNKNOWN_ROLE");
  rejected(()->accounts.update(SITE,first.account().id(),first.account().version()+5,
   settings("dup","VIEWER",true)),409,"VERSION_CONFLICT");
  rejected(()->accounts.update(SITE,999999,0,settings("gone","VIEWER",true)),404,"ACCOUNT_NOT_FOUND");
 }

 @Test void twoAccountsNeverReceiveTheSameToken(){
  var a=accounts.create(SITE,settings("t1","VIEWER",true));
  var b=accounts.create(SITE,settings("t2","VIEWER",true));
  assertThat(a.token()).isNotEqualTo(b.token());
 }
}
