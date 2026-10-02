package com.amhs.live;
import com.amhs.fleet.FleetState;
import com.amhs.integration.CommandOutbox;
import com.amhs.traffic.TrafficControl;
import com.amhs.transport.TransportAssignment;
import com.amhs.transport.TransportLedger;
import com.amhs.transport.TransportOrders;
import com.amhs.transport.ZoneEvacuation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * A read that could not happen must not look like a read where nothing changed.
 *
 * <p>The two are indistinguishable to a screen unless the server says which it was, and the screen
 * then keeps calling data live that it has no way to refresh. This is the same rule the rest of the
 * system follows for a vehicle nobody can locate and a command whose send nobody can account for:
 * not knowing is its own answer, and it is never reported as "nothing to report".
 */
class ReadFailureIsNotSilenceTest {
 /** Fails the way a database that cannot be reached does: before anything is read. */
 private static final PlatformTransactionManager UNREACHABLE=new PlatformTransactionManager(){
  public TransactionStatus getTransaction(TransactionDefinition definition){
   throw new CannotCreateTransactionException("no connection");
  }
  public void commit(TransactionStatus status){}
  public void rollback(TransactionStatus status){}
 };

 /** Stand-ins only: the transaction fails before any of them is asked anything. */
 private static LiveSite unreachableSite(){
  return new LiveSite(Mockito.mock(FleetState.class),Mockito.mock(TrafficControl.class),
   Mockito.mock(TransportOrders.class),Mockito.mock(TransportAssignment.class),
   Mockito.mock(TransportLedger.class),Mockito.mock(ZoneEvacuation.class),
   Mockito.mock(CommandOutbox.class),UNREACHABLE);
 }

 @Test void aReadThatCouldNotRunNamesEveryPartItCouldNotRead(){
  var site=unreachableSite();
  var reading=site.read(1,List.of("vehicles","holds","orders"));
  assertThat(reading.complete()).isFalse();
  assertThat(reading.failed()).containsExactlyInAnyOrder("vehicles","holds","orders");
  // Nothing is claimed to have been read, so no part can be mistaken for an up-to-date empty one.
  assertThat(reading.parts()).isEmpty();
 }

 @Test void aReadWithNothingAskedForIsCompleteRatherThanFailed(){
  var reading=unreachableSite().read(1,List.of());
  assertThat(reading.complete()).isTrue();
 }
}
