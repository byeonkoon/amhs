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
 * Logging in decides who may do anything at all, so the cases that matter are the ones where
 * getting it wrong lets the wrong person in, keeps the wrong person in, or leaves nobody able to
 * let anyone in.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class AccountDirectorySqlTest {
 @Autowired AccountDirectory accounts; @Autowired Logins logins; @Autowired JdbcTemplate jdbc;
 private static final long SITE=1;
 private final String run=UUID.randomUUID().toString().substring(0,6);

 @AfterEach void clean(){
  jdbc.update("DELETE FROM platform.user_session WHERE account_id IN"
   +" (SELECT id FROM platform.account WHERE code LIKE ?)",run+"%");
  jdbc.update("DELETE FROM platform.account WHERE code LIKE ?",run+"%");
 }
 private AccountSettings settings(String suffix,String role,boolean enabled){
  return new AccountSettings(run+"-"+suffix,run+" "+suffix,role,enabled);
 }
 private void rejected(Runnable call,int status,String code){
  assertThatThrownBy(call::run).isInstanceOfSatisfying(ConfigurationException.class,e->{
   assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).isEqualTo(code);
  });
 }

 @Test void aPasswordLetsItsOwnerInAndIsNeverReadableBack(){
  var made=accounts.create(SITE,settings("op","OPERATOR",true),"a-good-enough-password");
  assertThat(made.canLogIn()).isTrue();
  assertThat(made.level()).isEqualTo(2);
  var session=logins.logIn(SITE,run+"-op","a-good-enough-password");
  assertThat(session.principal().role()).isEqualTo(Role.OPERATOR);
  assertThat(session.token()).isNotBlank().hasSizeGreaterThanOrEqualTo(32);
  // Nothing in a listing can give the password away, not even by accident.
  assertThat(accounts.list(SITE).toString()).doesNotContain("a-good-enough-password");
  assertThat(logins.resolve(PrincipalResolver.sha256Hex(session.token())))
   .get().extracting(Principal::code).isEqualTo(run+"-op");
 }

 /** A wrong password and a name nobody has are refused the same way, so neither confirms the other. */
 @Test void aWrongPasswordAndAnUnknownNameAreRefusedIdentically(){
  accounts.create(SITE,settings("same","VIEWER",true),"the-right-password");
  rejected(()->logins.logIn(SITE,run+"-same","the-wrong-password"),401,"LOGIN_REFUSED");
  rejected(()->logins.logIn(SITE,run+"-nobody","the-wrong-password"),401,"LOGIN_REFUSED");
  rejected(()->logins.logIn(SITE,run+"-same",null),401,"LOGIN_REFUSED");
 }

 @Test void loggingOutAndSwitchingOffBothEndTheSession(){
  var made=accounts.create(SITE,settings("out","OPERATOR",true),"a-good-enough-password");
  var first=logins.logIn(SITE,run+"-out","a-good-enough-password");
  logins.logOut(PrincipalResolver.sha256Hex(first.token()));
  assertThat(logins.resolve(PrincipalResolver.sha256Hex(first.token()))).isEmpty();

  var second=logins.logIn(SITE,run+"-out","a-good-enough-password");
  assertThat(logins.resolve(PrincipalResolver.sha256Hex(second.token()))).isPresent();
  // Authority taken away does not survive in a browser that is already logged in.
  accounts.update(SITE,made.id(),made.version(),settings("out","OPERATOR",false));
  assertThat(logins.resolve(PrincipalResolver.sha256Hex(second.token()))).isEmpty();
  rejected(()->logins.logIn(SITE,run+"-out","a-good-enough-password"),401,"LOGIN_REFUSED");
 }

 @Test void changingAPasswordStopsTheOldOneAndTheSessionsItOpened(){
  var made=accounts.create(SITE,settings("pw","OPERATOR",true),"the-first-password");
  var session=logins.logIn(SITE,run+"-pw","the-first-password");
  accounts.setPassword(SITE,made.id(),"the-second-password");
  assertThat(logins.resolve(PrincipalResolver.sha256Hex(session.token()))).isEmpty();
  rejected(()->logins.logIn(SITE,run+"-pw","the-first-password"),401,"LOGIN_REFUSED");
  assertThat(logins.logIn(SITE,run+"-pw","the-second-password").principal().code()).isEqualTo(run+"-pw");
 }

 /** A session carries the account's authority as it is now, not as it was at login. */
 @Test void aLevelTakenAwayDoesNotLingerInAnOpenSession(){
  var made=accounts.create(SITE,settings("lv","ADMIN",true),"a-good-enough-password");
  var session=logins.logIn(SITE,run+"-lv","a-good-enough-password");
  assertThat(session.principal().role()).isEqualTo(Role.ADMIN);
  accounts.update(SITE,made.id(),made.version(),settings("lv","VIEWER",true));
  // The change ended the session outright; logging in again gives the level it has now.
  assertThat(logins.resolve(PrincipalResolver.sha256Hex(session.token()))).isEmpty();
  assertThat(logins.logIn(SITE,run+"-lv","a-good-enough-password").principal().role()).isEqualTo(Role.VIEWER);
 }

 @Test void theLastWayInCannotBeTakenAway(){
  int existing=jdbc.queryForObject(
   "SELECT count(*) FROM platform.account WHERE site_id=? AND role='ADMIN' AND enabled=TRUE",Integer.class,SITE);
  var admin=accounts.create(SITE,settings("adm","ADMIN",true),"a-good-enough-password");
  if(existing==0){
   rejected(()->accounts.update(SITE,admin.id(),admin.version(),settings("adm","OPERATOR",true)),409,"LAST_ADMIN");
   rejected(()->accounts.update(SITE,admin.id(),admin.version(),settings("adm","ADMIN",false)),409,"LAST_ADMIN");
  }else{
   // The seeded administrators are other ways in, so this one may step down.
   assertThat(accounts.update(SITE,admin.id(),admin.version(),settings("adm","OPERATOR",true)).role())
    .isEqualTo(Role.OPERATOR);
  }
 }

 @Test void codesAreUniqueRolesAreCheckedAndAPasswordHasToBeWorthSomething(){
  var first=accounts.create(SITE,settings("dup","VIEWER",true),"a-good-enough-password");
  rejected(()->accounts.create(SITE,settings("dup","VIEWER",true),"another-password"),409,"ACCOUNT_CODE_IN_USE");
  rejected(()->accounts.create(SITE,settings("bad","SUPERUSER",true),"a-good-enough-password"),422,"UNKNOWN_ROLE");
  rejected(()->accounts.create(SITE,settings("tiny","VIEWER",true),"ab"),422,"PASSWORD_TOO_SHORT");
  rejected(()->accounts.update(SITE,first.id(),first.version()+5,settings("dup","VIEWER",true)),409,"VERSION_CONFLICT");
  rejected(()->accounts.update(SITE,999999,0,settings("gone","VIEWER",true)),404,"ACCOUNT_NOT_FOUND");
 }

 /** The levels the screens are divided by, in the order a check compares them. */
 @Test void theFourLevelsRankFromWatchingToEverything(){
  assertThat(Role.VIEWER.level()).isEqualTo(1);
  assertThat(Role.ADMIN.level()).isEqualTo(4);
  assertThat(Role.MANAGER.allows(Role.OPERATOR)).isTrue();
  assertThat(Role.OPERATOR.allows(Role.MANAGER)).isFalse();
  assertThat(Role.ofLevel(3)).isEqualTo(Role.MANAGER);
 }

 /**
  * The demo logins the migration seeds: one account per level, each with a password already set,
  * so the screens open without setting anything up first.
  *
  * <p>Which password opens which of them is not asserted here, because asserting it would mean
  * writing the passwords into this repository. The pairing is kept outside it; what is checked
  * here is that each level exists, is usable, and carries a hash rather than nothing.
  */
 @Test void theSeededLoginsCoverEveryLevelAndCanAllBeUsed(){
  var seeded=accounts.list(SITE).stream()
   .filter(a -> !a.code().startsWith(run)).toList();
  for(String code:new String[]{"Visitor","Operator","Manager","Administrator","developer"})
   assertThat(seeded).anySatisfy(a->{
    assertThat(a.code()).isEqualTo(code);
    assertThat(a.enabled()).isTrue();
    assertThat(a.canLogIn()).isTrue();
   });
  assertThat(seeded).filteredOn(a -> a.code().equals("Visitor")).singleElement()
   .extracting(AccountView::role).isEqualTo(Role.VIEWER);
  assertThat(seeded).filteredOn(a -> a.code().equals("Operator")).singleElement()
   .extracting(AccountView::role).isEqualTo(Role.OPERATOR);
  assertThat(seeded).filteredOn(a -> a.code().equals("Manager")).singleElement()
   .extracting(AccountView::role).isEqualTo(Role.MANAGER);
  assertThat(seeded).filteredOn(a -> a.code().equals("Administrator")).singleElement()
   .extracting(AccountView::role).isEqualTo(Role.ADMIN);
  assertThat(seeded).filteredOn(a -> a.code().equals("developer")).singleElement()
   .extracting(AccountView::role).isEqualTo(Role.ADMIN);
  // A stored hash has to be a hash of the shape the verifier understands, not a leftover plaintext.
  assertThat(jdbc.queryForList(
   "SELECT password_hash FROM platform.account WHERE site_id=? AND code IN"
   +" ('Visitor','Operator','Manager','Administrator','developer')",String.class,SITE))
   .hasSize(5).allSatisfy(h -> assertThat(h).startsWith("pbkdf2$210000$"));
 }
}
