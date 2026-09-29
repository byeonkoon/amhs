package com.amhs;
import com.amhs.transport.OrderStatus;
import org.junit.jupiter.api.Test;
import static com.amhs.transport.OrderStatus.*;
import static org.assertj.core.api.Assertions.*;
class TransportStateMachineTest {
 @Test void normalFlowIsAllowedStepByStep(){
  assertThat(QUEUED.canTransitionTo(ASSIGNED)).isTrue();
  assertThat(ASSIGNED.canTransitionTo(TO_SOURCE)).isTrue();
  assertThat(TO_SOURCE.canTransitionTo(LOADING)).isTrue();
  assertThat(LOADING.canTransitionTo(TO_DESTINATION)).isTrue();
  assertThat(TO_DESTINATION.canTransitionTo(UNLOADING)).isTrue();
  assertThat(UNLOADING.canTransitionTo(COMPLETED)).isTrue();
 }
 @Test void stepsCannotBeSkipped(){
  assertThat(QUEUED.canTransitionTo(COMPLETED)).isFalse();
  assertThat(QUEUED.canTransitionTo(LOADING)).isFalse();
  assertThat(ASSIGNED.canTransitionTo(TO_DESTINATION)).isFalse();
  assertThat(TO_SOURCE.canTransitionTo(UNLOADING)).isFalse();
 }
 @Test void terminalStatesDoNotMove(){
  for(var terminal:new OrderStatus[]{COMPLETED,CANCELLED,FAILED}){
   assertThat(terminal.isTerminal()).isTrue();
   for(var any:OrderStatus.values())assertThat(terminal.canTransitionTo(any)).isFalse();
  }
 }
 @Test void cancelBeforePickupEndsImmediatelyAfterPickupWaitsForDevice(){
  assertThat(QUEUED.cancelTarget()).isEqualTo(CANCELLED);
  assertThat(ASSIGNED.cancelTarget()).isEqualTo(CANCELLED);
  assertThat(TO_SOURCE.cancelTarget()).isEqualTo(CANCELLED);
  assertThat(LOADING.cancelTarget()).isEqualTo(CANCEL_REQUESTED);
  assertThat(TO_DESTINATION.cancelTarget()).isEqualTo(CANCEL_REQUESTED);
  assertThat(UNLOADING.cancelTarget()).isNull();
  assertThat(COMPLETED.cancelTarget()).isNull();
 }
 @Test void failureAfterPickupRequiresRecoveryNotPlainFailure(){
  assertThat(LOADING.canTransitionTo(FAILED)).isFalse();
  assertThat(LOADING.canTransitionTo(RECOVERY_REQUIRED)).isTrue();
  assertThat(TO_DESTINATION.canTransitionTo(FAILED)).isFalse();
  assertThat(TO_DESTINATION.canTransitionTo(RECOVERY_REQUIRED)).isTrue();
  assertThat(UNLOADING.canTransitionTo(RECOVERY_REQUIRED)).isTrue();
  // Before pickup a plain failure is fine because no carrier is held.
  assertThat(QUEUED.canTransitionTo(FAILED)).isTrue();
  assertThat(TO_SOURCE.canTransitionTo(FAILED)).isTrue();
 }
 @Test void recoveryIsResolvedByAnOperatorDecision(){
  assertThat(RECOVERY_REQUIRED.isTerminal()).isFalse();
  assertThat(RECOVERY_REQUIRED.allowedNext()).containsExactlyInAnyOrder(COMPLETED,CANCELLED,FAILED);
 }
 @Test void carrierMayBeHeldFromLoadingOnward(){
  assertThat(QUEUED.carrierMayBeHeld()).isFalse();
  assertThat(TO_SOURCE.carrierMayBeHeld()).isFalse();
  assertThat(LOADING.carrierMayBeHeld()).isTrue();
  assertThat(UNLOADING.carrierMayBeHeld()).isTrue();
 }
}
