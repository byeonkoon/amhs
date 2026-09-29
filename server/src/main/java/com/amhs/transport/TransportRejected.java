package com.amhs.transport;
/** Request that cannot be accepted. code is returned to the caller; conflict maps to 409, others to 422. */
public class TransportRejected extends RuntimeException{
 private final String code; private final boolean conflict;
 public TransportRejected(String code,String message,boolean conflict){super(message);this.code=code;this.conflict=conflict;}
 public String code(){return code;}
 public boolean isConflict(){return conflict;}
}
