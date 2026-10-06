package com.amhs.transport;

/**
 * What a transfer report is about: the pickup at the source or the drop at the destination. A
 * device knows which action it was given; a report that says so is never taken for the other one,
 * however late it arrives.
 */
public enum TransferAction {
 PICKUP, DROP;

 /** The action an order is in the middle of, or null outside a transfer. */
 public static TransferAction of(OrderStatus stage){
  return stage==OrderStatus.LOADING?PICKUP:stage==OrderStatus.UNLOADING?DROP:null;
 }
}
