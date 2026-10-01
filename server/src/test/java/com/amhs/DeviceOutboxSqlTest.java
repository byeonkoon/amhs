package com.amhs;
import com.amhs.integration.*;
import com.amhs.transport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The outbox exists so a device command and the state change that caused it cannot disagree,
 * and so a send whose outcome is unknown is never quietly repeated.
 */
@SpringBootTest(properties={
 "spring.datasource.url=${AMHS_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5432/amhs_test}",
 "amhs.configuration.writes-enabled=true"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="AMHS_SQL_TEST",matches="true")
class DeviceOutboxSqlTest {
 @Autowired CommandOutbox outbox; @Autowired DeviceGateway gateway; @Autowired JdbcTemplate jdbc;
 @Autowired TransportOrders orders; @Autowired TransactionTemplate tx; @Autowired MockMvc mvc;

 // Rows outlive one run, so names are unique per run and a replay cannot masquerade as a fresh command.
 private final String run=UUID.randomUUID().toString().substring(0,8);
 private String name(String suffix){ return run+"-"+suffix; }
 // Commands are ordered per vehicle, and rows left from earlier runs stay in the table. A vehicle no
 // earlier row mentions keeps each case about its own commands.
 private static long vehicle(){ return ThreadLocalRandom.current().nextLong(1_000_000_000L,Long.MAX_VALUE); }

 private long order(String key){
  return tx.execute(s->orders.accept(1,
   new OrderRequest("TEST",name(key),"OHT-A","OHT-B",name(key),50),"tester","trace").order().id());
 }
 private DeviceCommand enqueue(long orderId,String commandId){
  return enqueue(orderId,vehicle(),commandId,"MOVE");
 }
 private DeviceCommand enqueue(long orderId,long vehicleId,String commandId,String type){
  return tx.execute(s->outbox.enqueue(orderId,vehicleId,commandId,type,"{}"));
 }
 private DeviceCommand reload(long orderId,String commandId){
  return outbox.byOrder(1,orderId).stream().filter(c->c.commandId().equals(commandId)).findFirst().orElseThrow();
 }
 private int rows(String commandId){
  return jdbc.queryForObject("SELECT count(*) FROM integration.command_outbox WHERE command_id=?",Integer.class,commandId);
 }
 private java.util.List<DeviceCommand> claimAt(long siteId,int limit,int maxAttempts){
  return tx.execute(s->outbox.claim(siteId,limit,maxAttempts));
 }
 private java.util.List<DeviceCommand> claim(int limit,int maxAttempts){
  return tx.execute(s->outbox.claim(1,limit,maxAttempts));
 }
 private void makeClaimable(String commandId){
  jdbc.update("UPDATE integration.command_outbox SET available_at=now()-make_interval(secs=>1) WHERE command_id=?",commandId);
 }

 @Test void aCommandWithoutTheTransactionThatCausedItIsRefused(){
  long id=order("mandatory"); String cmd=name("cmd-no-tx");
  // With no transaction there is no state change to agree with, so the write is refused instead
  // of leaving a command the ledger never committed to.
  assertThatThrownBy(()->outbox.enqueue(id,vehicle(),cmd,"MOVE","{}")).isInstanceOf(IllegalTransactionStateException.class);
  assertThat(rows(cmd)).isZero();
 }

 @Test void aRolledBackStateChangeLeavesNoCommand(){
  long id=order("rollback"); String cmd=name("cmd-rolled-back");
  assertThatThrownBy(()->tx.execute(s->{
   outbox.enqueue(id,vehicle(),cmd,"MOVE","{}");
   throw new IllegalStateException("caller failed after writing the command");
  })).isInstanceOf(IllegalStateException.class);
  assertThat(rows(cmd)).isZero();
 }

 @Test void theSameCommandIdNeverBecomesTwoCommands(){
  long id=order("idempotent"); String cmd=name("cmd-once");
  var first=enqueue(id,cmd); var again=enqueue(id,cmd);
  assertThat(again.id()).isEqualTo(first.id());
  assertThat(rows(cmd)).isEqualTo(1);
 }

 @Test void deliveredCommandsAreNeverClaimedAgain(){
  long id=order("delivered"); String cmd=name("cmd-delivered");
  enqueue(id,cmd);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).contains(cmd);
  assertThat(reload(id,cmd).status()).isEqualTo(CommandStatus.DISPATCHING);
  assertThat(reload(id,cmd).attempt()).isEqualTo(1);
  tx.execute(s->outbox.settle(cmd,DeviceChannel.Verdict.delivered(),Duration.ofSeconds(60)));
  assertThat(reload(id,cmd).status()).isEqualTo(CommandStatus.SENT);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).doesNotContain(cmd);
 }

 @Test void aFailedSendIsRetriedOnlyAfterItsDelay(){
  long id=order("failed"); String cmd=name("cmd-failed");
  enqueue(id,cmd);
  claim(50,5);
  tx.execute(s->outbox.settle(cmd,DeviceChannel.Verdict.notDelivered("LINK_DOWN"),Duration.ofSeconds(60)));
  assertThat(reload(id,cmd).status()).isEqualTo(CommandStatus.FAILED);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).doesNotContain(cmd);
  makeClaimable(cmd);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).contains(cmd);
  assertThat(reload(id,cmd).attempt()).isEqualTo(2);
 }

 @Test void anUnknownOutcomeIsNeverSentAgainAndStaysVisible(){
  long id=order("doubt"); String cmd=name("cmd-doubtful");
  enqueue(id,cmd);
  claim(50,5);
  tx.execute(s->outbox.settle(cmd,DeviceChannel.Verdict.inDoubt("NO_RESPONSE"),Duration.ofSeconds(60)));
  var doubtful=reload(id,cmd);
  assertThat(doubtful.status()).isEqualTo(CommandStatus.DISPATCHING);
  assertThat(doubtful.status().inDoubt()).isTrue();
  assertThat(doubtful.lastError()).isEqualTo("NO_RESPONSE");
  // Even with every timer expired, a command the device may hold is not offered again.
  makeClaimable(cmd);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).doesNotContain(cmd);
  // The operator's list is oldest first and capped, so membership is checked against this
  // order's own commands and the shared list is checked for the ordering it promises.
  assertThat(outbox.byOrder(1,id)).anyMatch(c -> c.commandId().equals(cmd)&&c.status().inDoubt());
  assertThat(outbox.inDoubt(1,200)).isSortedAccordingTo(java.util.Comparator.comparing(DeviceCommand::createdAt));
 }

 @Test void receiptResolvesDoubtButNotBeforeAnythingWasSent(){
  long id=order("ack"); String cmd=name("cmd-ack");
  enqueue(id,cmd);
  assertThatThrownBy(()->outbox.acknowledge(cmd)).hasMessage("COMMAND_NOT_SENT");
  claim(50,5);
  tx.execute(s->outbox.settle(cmd,DeviceChannel.Verdict.inDoubt("NO_RESPONSE"),Duration.ofSeconds(60)));
  assertThat(outbox.acknowledge(cmd).status()).isEqualTo(CommandStatus.ACCEPTED);
  assertThat(outbox.acknowledge(cmd).status()).isEqualTo(CommandStatus.ACCEPTED);
  assertThat(outbox.inDoubt(1,200)).extracting(DeviceCommand::commandId).doesNotContain(cmd);
 }

 @Test void retriesStopAtTheAttemptLimitInsteadOfLoopingForever(){
  long id=order("attempts"); String cmd=name("cmd-capped");
  enqueue(id,cmd);
  for(int i=0;i<2;i++){
   claim(50,2);
   tx.execute(s->outbox.settle(cmd,DeviceChannel.Verdict.notDelivered("LINK_DOWN"),Duration.ZERO));
  }
  assertThat(reload(id,cmd).attempt()).isEqualTo(2);
  makeClaimable(cmd);
  assertThat(claim(50,2)).extracting(DeviceCommand::commandId).doesNotContain(cmd);
 }

 @Test void publishingIsScopedToOneSite(){
  long id=order("scoped"); String cmd=name("cmd-scoped");
  enqueue(id,cmd);
  // The command belongs to site 1 through its order, so another site's sweep must not take it.
  assertThat(claimAt(2,50,5)).extracting(DeviceCommand::commandId).doesNotContain(cmd);
  assertThat(reload(id,cmd).status()).isEqualTo(CommandStatus.PENDING);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).contains(cmd);
 }

 @Test void withoutAnAdapterNothingLeavesTheSystemAndTheCommandStaysRetryable(){
  long id=order("gateway"); String cmd=name("cmd-gateway");
  enqueue(id,cmd);
  var published=gateway.publishPending(1);
  assertThat(published.claimed()).isPositive();
  assertThat(published.delivered()).isZero();
  var command=reload(id,cmd);
  assertThat(command.status()).isEqualTo(CommandStatus.FAILED);
  assertThat(command.lastError()).isEqualTo("ADAPTER_NOT_IMPLEMENTED");
  assertThat(command.status().retryable()).isTrue();
 }

 @Test @Transactional void commandsBelongToTheSiteThatOwnsTheirOrder() throws Exception {
  long id=orders.accept(1,new OrderRequest("TEST",name("http"),"OHT-A","OHT-B",name("http"),50),"tester","trace").order().id();
  String cmd=name("cmd-http");
  outbox.enqueue(id,vehicle(),cmd,"MOVE","{}");
  mvc.perform(get("/api/v1/sites/1/transport-orders/"+id+"/device-commands"))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].commandId").value(cmd))
   .andExpect(jsonPath("$[0].status").value("PENDING"));
  mvc.perform(get("/api/v1/sites/2/transport-orders/"+id+"/device-commands"))
   .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
 }

 @Test void aLaterCommandForTheSameVehicleWaitsUntilTheEarlierOneHasLeft(){
  long id=order("vehicle-order"); long vehicle=vehicle();
  String move=name("cmd-first-move"), more=name("cmd-then-more");
  enqueue(id,vehicle,move,"MOVE");
  enqueue(id,vehicle,more,"MORE");
  // Only the first goes: a MORE that overtook its MOVE would extend a movement the vehicle never got.
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).contains(move).doesNotContain(more);
  // Claimed with its outcome still unknown, the MOVE keeps holding the MORE back.
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).doesNotContain(more);
  tx.execute(s->outbox.settle(move,DeviceChannel.Verdict.notDelivered("LINK_DOWN"),Duration.ofSeconds(60)));
  // Failed and waiting for its retry: still first in line.
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).doesNotContain(more);
  makeClaimable(move);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).contains(move).doesNotContain(more);
  tx.execute(s->outbox.settle(move,DeviceChannel.Verdict.delivered(),Duration.ZERO));
  // On the wire now, so what follows may follow it.
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).contains(more);
 }

 @Test void anotherVehicleIsNotHeldBackAndABatchComesBackOldestFirst(){
  long id=order("two-vehicles");
  String a=name("cmd-vehicle-a"), b=name("cmd-vehicle-b");
  enqueue(id,vehicle(),a,"MOVE");
  enqueue(id,vehicle(),b,"MOVE");
  var claimed=claim(200,5).stream().filter(c->c.commandId().equals(a)||c.commandId().equals(b)).toList();
  assertThat(claimed).extracting(DeviceCommand::commandId).containsExactly(a,b);
  assertThat(claim(200,5)).isSortedAccordingTo(java.util.Comparator.comparingLong(DeviceCommand::id));
 }

 @Test void withdrawingTakesBackWhatHasNotLeftAndSaysWhetherAnythingHas(){
  long id=order("withdraw"); long vehicle=vehicle();
  String sent=name("cmd-already-out"), pending=name("cmd-not-yet");
  enqueue(id,vehicle,sent,"MOVE");
  claim(50,5);
  tx.execute(s->outbox.settle(sent,DeviceChannel.Verdict.delivered(),Duration.ZERO));
  enqueue(id,vehicle,pending,"MORE");
  var withdrawal=tx.execute(s->outbox.withdraw(id,"TEST"));
  assertThat(withdrawal.withdrawn()).isEqualTo(1);
  assertThat(withdrawal.anyLeft()).isTrue();
  assertThat(reload(id,pending).status()).isEqualTo(CommandStatus.WITHDRAWN);
  assertThat(reload(id,sent).status()).isEqualTo(CommandStatus.SENT);
  // Withdrawn is final: never claimed, never acknowledged, not outstanding.
  makeClaimable(pending);
  assertThat(claim(50,5)).extracting(DeviceCommand::commandId).doesNotContain(pending);
  assertThatThrownBy(()->outbox.acknowledge(pending)).hasMessage("COMMAND_NOT_SENT");
  assertThat(outbox.unresolved(1,id,"MORE")).isEmpty();
  // Withdrawal must share the caller's transaction, like enqueue.
  assertThatThrownBy(()->outbox.withdraw(id,"TEST")).isInstanceOf(IllegalTransactionStateException.class);
 }
}
