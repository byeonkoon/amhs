package com.amhs.transport;
import java.util.*;
/**
 * Transport work state. Separate from device command state: a sent command never advances this
 * machine by itself, only a reported outcome does.
 *
 * <p>Normal flow: QUEUED → ASSIGNED → TO_SOURCE → LOADING → TO_DESTINATION → UNLOADING → COMPLETED.
 * Before the carrier is picked up a cancel takes effect immediately. Once LOADING has begun the
 * cancel must be confirmed by the device, so it goes through CANCEL_REQUESTED. A failure after
 * pickup leaves the carrier somewhere physical, so it ends in RECOVERY_REQUIRED rather than FAILED.
 */
public enum OrderStatus {
 QUEUED,ASSIGNED,TO_SOURCE,LOADING,TO_DESTINATION,UNLOADING,COMPLETED,
 CANCEL_REQUESTED,CANCELLED,FAILED,RECOVERY_REQUIRED;

 private static final Map<OrderStatus,Set<OrderStatus>> ALLOWED=new EnumMap<>(OrderStatus.class);
 static{
  ALLOWED.put(QUEUED,EnumSet.of(ASSIGNED,CANCELLED,FAILED));
  ALLOWED.put(ASSIGNED,EnumSet.of(TO_SOURCE,QUEUED,CANCELLED,FAILED));
  ALLOWED.put(TO_SOURCE,EnumSet.of(LOADING,CANCEL_REQUESTED,CANCELLED,FAILED));
  // From LOADING onward the carrier may already be held, so failures require recovery.
  ALLOWED.put(LOADING,EnumSet.of(TO_DESTINATION,CANCEL_REQUESTED,RECOVERY_REQUIRED));
  ALLOWED.put(TO_DESTINATION,EnumSet.of(UNLOADING,CANCEL_REQUESTED,RECOVERY_REQUIRED));
  ALLOWED.put(UNLOADING,EnumSet.of(COMPLETED,RECOVERY_REQUIRED));
  ALLOWED.put(CANCEL_REQUESTED,EnumSet.of(CANCELLED,RECOVERY_REQUIRED,COMPLETED));
  ALLOWED.put(COMPLETED,EnumSet.noneOf(OrderStatus.class));
  ALLOWED.put(CANCELLED,EnumSet.noneOf(OrderStatus.class));
  ALLOWED.put(FAILED,EnumSet.noneOf(OrderStatus.class));
  ALLOWED.put(RECOVERY_REQUIRED,EnumSet.of(COMPLETED,CANCELLED,FAILED));
 }
 public boolean isTerminal(){return ALLOWED.get(this).isEmpty();}
 public boolean canTransitionTo(OrderStatus next){return next!=null&&ALLOWED.get(this).contains(next);}
 /** True once the carrier may physically be held, which changes how failures are handled. */
 public boolean carrierMayBeHeld(){return this==LOADING||this==TO_DESTINATION||this==UNLOADING||this==CANCEL_REQUESTED;}
 /**
  * A cancel before pickup ends the work at once; after pickup it must be confirmed by the device.
  * Returns null when cancelling is no longer meaningful, which includes UNLOADING: the carrier is
  * already being handed over, so the order finishes or goes to recovery rather than being cancelled.
  */
 public OrderStatus cancelTarget(){
  if(isTerminal())return null;
  OrderStatus target=carrierMayBeHeld()?CANCEL_REQUESTED:CANCELLED;
  return canTransitionTo(target)?target:null;
 }
 public Set<OrderStatus> allowedNext(){return Collections.unmodifiableSet(ALLOWED.get(this));}
}
