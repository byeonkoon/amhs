package com.amhs.platform.infrastructure;
import com.amhs.platform.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
@Service
class JdbcAccountQuery implements AccountQuery {
 private final JdbcTemplate jdbc;
 JdbcAccountQuery(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional(readOnly=true)
 public Optional<Principal> findByTokenHash(String tokenSha256Hex){
  if(tokenSha256Hex==null||tokenSha256Hex.length()!=64)return Optional.empty();
  var rows=jdbc.query("SELECT id,site_id,code,role FROM platform.account WHERE token_sha256=? AND enabled=TRUE",
   (r,n)->new Principal(r.getLong(1),r.getLong(2),r.getString(3),Role.valueOf(r.getString(4))),tokenSha256Hex);
  return rows.isEmpty()?Optional.empty():Optional.of(rows.get(0));
 }
}
