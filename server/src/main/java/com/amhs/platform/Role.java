package com.amhs.platform;
/**
 * What a login may do, as four levels.
 *
 * <p>Each level is the one below it plus more, so a check is "at least this" rather than a list of
 * exceptions. The division follows what a mistake costs rather than whether a request writes.
 *
 * <ul>
 * <li>1 VIEWER — watch: the fleet, alarms, history and diagnostics.</li>
 * <li>2 OPERATOR — run the work that is already configured: move vehicles, dispatch, cancel,
 *     extend authority, report state, and see transport and traffic.</li>
 * <li>3 MANAGER — the above, and what is being carried and stored.</li>
 * <li>4 ADMIN — everything, including the track itself, the site-wide options and who may log in.
 *     A mistake at this level reaches every vehicle at once and does not announce itself.</li>
 * </ul>
 */
public enum Role {
 VIEWER(1),OPERATOR(2),MANAGER(3),ADMIN(4);
 private final int level;
 Role(int level){this.level=level;}
 /** 1 to 4, as the screens and the account list name them. */
 public int level(){return level;}
 public boolean allows(Role required){return this.level>=required.level;}
 public static Role of(String value){
  try{return valueOf(value);}catch(IllegalArgumentException|NullPointerException e){
   throw new ConfigurationException(422,"UNKNOWN_ROLE");
  }
 }
 /** Accepts the level as a number too, so a screen may offer "Lv2" without knowing the name. */
 public static Role ofLevel(int level){
  for(Role role:values()) if(role.level==level) return role;
  throw new ConfigurationException(422,"UNKNOWN_ROLE");
 }
}
