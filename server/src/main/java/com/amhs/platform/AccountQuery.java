package com.amhs.platform;
import java.util.Optional;
public interface AccountQuery {
 /** Resolves an enabled account by the SHA-256 hex of its API token. */
 Optional<Principal> findByTokenHash(String tokenSha256Hex);
}
