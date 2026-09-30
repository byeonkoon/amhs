package com.amhs.platform;
/**
 * Operation authority.
 *
 * <p>VIEWER reads. OPERATOR runs the work that is already configured. ENGINEER configures the
 * resources and equipment that work runs on. ADMIN changes the track itself, the site-wide
 * options and who may do any of this — the changes whose blast radius is the whole site.
 */
public enum Role {
 VIEWER(0),OPERATOR(1),ENGINEER(2),ADMIN(3);
 private final int rank;
 Role(int rank){this.rank=rank;}
 public boolean allows(Role required){return this.rank>=required.rank;}
 public static Role of(String value){
  try{return valueOf(value);}catch(IllegalArgumentException|NullPointerException e){
   throw new ConfigurationException(422,"UNKNOWN_ROLE");
  }
 }
}
