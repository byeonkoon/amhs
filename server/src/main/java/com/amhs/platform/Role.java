package com.amhs.platform;
/** Operation authority. VIEWER reads only; OPERATOR issues transport commands; ADMIN changes configuration. */
public enum Role {
 VIEWER(0),OPERATOR(1),ADMIN(2);
 private final int rank;
 Role(int rank){this.rank=rank;}
 public boolean allows(Role required){return this.rank>=required.rank;}
}
