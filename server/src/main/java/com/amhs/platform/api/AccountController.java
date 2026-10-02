package com.amhs.platform.api;
import com.amhs.platform.AccountDirectory;
import com.amhs.platform.AccountSettings;
import com.amhs.platform.AccountView;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Accounts and what each may do. Administrator-only, which the path itself establishes.
 *
 * <p>A newly issued token is in the response to the call that created it and nowhere else. It is
 * never readable again: the account list says only whether a token exists.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/accounts")
class AccountController {
    private final AccountDirectory accounts;
    AccountController(AccountDirectory accounts) { this.accounts=accounts; }

    @GetMapping
    List<AccountView> list(@PathVariable long siteId) { return accounts.list(siteId); }

    /** 201 with the token. Show it to the person once; it cannot be fetched afterwards. */
    @PostMapping
    AccountDirectory.Issued create(@PathVariable long siteId,@RequestBody AccountSettings settings) {
        return accounts.create(siteId,settings);
    }

    @PutMapping("/{accountId}")
    AccountView update(@PathVariable long siteId,@PathVariable long accountId,
                       @RequestParam long expectedVersion,@RequestBody AccountSettings settings) {
        return accounts.update(siteId,accountId,expectedVersion,settings);
    }

    /** Replaces the token; the old one stops working immediately. */
    @PostMapping("/{accountId}/token")
    AccountDirectory.Issued reissue(@PathVariable long siteId,@PathVariable long accountId) {
        return accounts.reissueToken(siteId,accountId);
    }

    /** Stops the account getting in without deleting it, so its audit trail keeps its owner. */
    @DeleteMapping("/{accountId}/token")
    AccountView revoke(@PathVariable long siteId,@PathVariable long accountId) {
        return accounts.revokeToken(siteId,accountId);
    }
}
