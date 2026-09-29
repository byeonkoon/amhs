package com.amhs.transport;
/** Accepted transport request. requestSource plus idempotencyKey identify a retry. */
public record OrderRequest(String requestSource,String idempotencyKey,String sourcePortCode,String destinationPortCode,
 String carrierCode,Integer priority){}
