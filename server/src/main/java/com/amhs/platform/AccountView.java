package com.amhs.platform;
/**
 * An account as it may be shown.
 *
 * <p>It deliberately carries neither the token nor its hash. {@code hasToken} says whether one was
 * issued, which is all a screen needs: the token itself is shown once at issue and is never
 * readable again, so nothing here can leak it later.
 */
public record AccountView(long id,long siteId,String code,String displayName,Role role,
                          boolean enabled,boolean hasToken,long version){}
