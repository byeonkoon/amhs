package com.amhs;
import com.amhs.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Two administrators being stood down at the same time must not leave none.
 *
 * <p>"Not the last administrator" counts rows other than the one being written, so the written
 * row's version says nothing about it. Each request reads the other administrator as still there,
 * each version check passes, and both commit: a site with nobody who can hand the authority back,
 * which cannot be repaired from inside. Only serialising the decision prevents it, so that is what
 * is tested here -- in two real transactions, held open against each other.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}"})
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class LastAdminRaceSqlTest {
 @Autowired AccountDirectory accounts; @Autowired JdbcTemplate jdbc; @Autowired TransactionTemplate tx;
 private static final long SITE=1;
 private final String run=UUID.randomUUID().toString().substring(0,6);
 private final ExecutorService other=Executors.newSingleThreadExecutor();

 /** The site's own administrators are other ways in, so they are set aside for the duration. */
 @BeforeEach void standDownTheOthers(){
  jdbc.update("UPDATE platform.account SET enabled=FALSE WHERE site_id=? AND role='ADMIN' AND code NOT LIKE ?",
   SITE,run+"%");
 }
 @AfterEach void clean(){
  other.shutdownNow();
  jdbc.update("DELETE FROM platform.user_session WHERE account_id IN"
   +" (SELECT id FROM platform.account WHERE code LIKE ?)",run+"%");
  jdbc.update("DELETE FROM platform.account WHERE code LIKE ?",run+"%");
  jdbc.update("UPDATE platform.account SET enabled=TRUE WHERE site_id=? AND role='ADMIN'",SITE);
 }

 private AccountSettings settings(String suffix,String role,boolean enabled){
  return new AccountSettings(run+"-"+suffix,run+" "+suffix,role,enabled);
 }
 private int usableAdmins(){
  return jdbc.queryForObject("SELECT count(*) FROM platform.account"
   +" WHERE site_id=? AND role='ADMIN' AND enabled=TRUE AND password_hash IS NOT NULL",Integer.class,SITE);
 }

 @Test void twoAdministratorsStoodDownAtOnceCannotBothSucceed() throws Exception {
  var first=accounts.create(SITE,settings("a1","ADMIN",true),"a-good-enough-password");
  var second=accounts.create(SITE,settings("a2","ADMIN",true),"a-good-enough-password");
  assertThat(usableAdmins()).isEqualTo(2);

  var contender=new Held();
  // The first transaction decides and writes but does not commit; the second runs while it is open.
  var thrown=catchThrowable(()->tx.executeWithoutResult(s->{
   accounts.update(SITE,first.id(),first.version(),settings("a1","OPERATOR",true));
   contender.future=other.submit(()->tx.execute(
    t->accounts.update(SITE,second.id(),second.version(),settings("a2","OPERATOR",true))));
   // Long enough for the other transaction to reach the decision, which it must not be able to make.
   pause();
  }));

  // Exactly one of the two got through, whichever it was.
  var secondOutcome=catchThrowable(()->contender.future.get(30,TimeUnit.SECONDS));
  assertThat(thrown==null||secondOutcome==null)
   .as("one of the two stood down").isTrue();
  assertThat(thrown!=null||secondOutcome!=null)
   .as("the other was refused rather than both committing").isTrue();
  if(secondOutcome!=null)
   assertThat(secondOutcome).hasRootCauseInstanceOf(ConfigurationException.class)
    .rootCause().hasMessage("LAST_ADMIN");
  if(thrown!=null)
   assertThat(thrown).isInstanceOf(ConfigurationException.class).hasMessage("LAST_ADMIN");

  assertThat(usableAdmins()).as("somebody can still hand the authority back").isGreaterThanOrEqualTo(1);
 }

 /** One at a time, the rule reads the same: the second of two is the last and cannot step down. */
 @Test void oneAtATimeTheSecondOneIsRefused(){
  var first=accounts.create(SITE,settings("b1","ADMIN",true),"a-good-enough-password");
  var second=accounts.create(SITE,settings("b2","ADMIN",true),"a-good-enough-password");
  assertThat(accounts.update(SITE,first.id(),first.version(),settings("b1","OPERATOR",true)).role())
   .isEqualTo(Role.OPERATOR);
  assertThatThrownBy(()->accounts.update(SITE,second.id(),second.version(),settings("b2","OPERATOR",true)))
   .isInstanceOfSatisfying(ConfigurationException.class,e->{
    assertThat(e.status()).isEqualTo(409); assertThat(e.getMessage()).isEqualTo("LAST_ADMIN");
   });
  assertThat(usableAdmins()).isEqualTo(1);
 }

 private static void pause(){
  try{ Thread.sleep(700); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
 }
 private static final class Held { volatile Future<?> future; }
}
