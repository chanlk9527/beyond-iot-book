package com.nebula.iot.identity.interfaces;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.identity.api.CurrentAccount;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class AccountController {
    private final AccountDirectory accounts;
    public AccountController(AccountDirectory accounts) { this.accounts = accounts; }
    @GetMapping("/api/management/me")
    public CurrentAccount current(Principal principal) { return accounts.requireEnabled(principal.getName()); }
}
