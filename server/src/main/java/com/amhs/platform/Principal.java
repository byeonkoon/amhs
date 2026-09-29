package com.amhs.platform;
/** Resolved requester. accountId is null for the local development token, which has no account row. */
public record Principal(Long accountId,long siteId,String code,Role role){
 public static Principal localAdmin(){return new Principal(null,0L,"local-dev",Role.ADMIN);}
 public static Principal anonymous(){return new Principal(null,0L,"anonymous",Role.VIEWER);}
}
