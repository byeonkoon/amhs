package com.amhs.platform;
/**
 * An account as it may be shown.
 *
 * <p>{@code canLogIn} says a password has been set. It deliberately does not carry the password or
 * its stored derivation: the only thing anyone needs to know from a listing is whether this
 * account can be used, and nothing here could give the password away even by accident.
 */
public record AccountView(long id,long siteId,String code,String displayName,Role role,
                          boolean enabled,boolean canLogIn,long version){
 /** 1 to 4, so a screen can speak in levels without knowing the names. */
 public int level(){ return role.level(); }
}
