package com.amhs.platform;
/** One operation audit record. result is ALLOWED, DENIED or FAILED. */
public record AuditEntry(Principal actor,String action,String target,String result,int statusCode,String reason,String traceId){}
