package com.amhs.platform.api;
import com.amhs.platform.AccountDirectory;
import com.amhs.platform.AccountSettings;
import com.amhs.platform.AccountView;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Accounts and what each may do. Administrator-only, which the path itself establishes.
 *
 * <p>A password goes in and never comes out: the listing says only whether one has been set.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/configuration/accounts")
class AccountController {
    private final AccountDirectory accounts;
    AccountController(AccountDirectory accounts) { this.accounts=accounts; }

    /** Settings plus the first password, which is the one thing not kept afterwards. */
    public record NewAccount(String code, String displayName, String role, boolean enabled, String password) {
        AccountSettings settings() { return new AccountSettings(code,displayName,role,enabled); }
    }
    public record NewPassword(String password) {}

    @GetMapping
    List<AccountView> list(@PathVariable long siteId) { return accounts.list(siteId); }

    @PostMapping
    AccountView create(@PathVariable long siteId,@RequestBody NewAccount body) {
        return accounts.create(siteId,body.settings(),body.password());
    }

    @PutMapping("/{accountId}")
    AccountView update(@PathVariable long siteId,@PathVariable long accountId,
                       @RequestParam long expectedVersion,@RequestBody AccountSettings settings) {
        return accounts.update(siteId,accountId,expectedVersion,settings);
    }

    /** Sets a new password and ends the account's live sessions, so the old one stops working. */
    @PutMapping("/{accountId}/password")
    AccountView setPassword(@PathVariable long siteId,@PathVariable long accountId,@RequestBody NewPassword body) {
        return accounts.setPassword(siteId,accountId,body.password());
    }
}
