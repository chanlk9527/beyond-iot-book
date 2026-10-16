package com.nebula.iot.identity.application;

import com.nebula.iot.identity.api.AccountDirectory;
import com.nebula.iot.identity.api.CurrentAccount;
import com.nebula.iot.identity.domain.AccountRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public class AccountService implements AccountDirectory, UserDetailsService {
    private final AccountRepository accounts;
    private final com.nebula.iot.identity.domain.RbacRepository rbac;
    private final java.util.function.Function<String, String> names;
        public AccountService(AccountRepository accounts, java.util.function.Function<String, String> names, com.nebula.iot.identity.domain.RbacRepository rbac) {
        this.accounts = accounts;
        this.rbac = rbac;
        this.names = names;
    }
    @Override public void requirePlatformAdmin(String username) {
        if (!requireEnabled(username).platformAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Platform administrator required");
        }
    }
    @Override public void requirePermission(String username, String permission) {
        if (!com.nebula.iot.identity.api.Permissions.ALL.contains(permission)
                || !requireEnabled(username).permissions().contains(permission)) {
            throw new org.springframework.security.access.AccessDeniedException("Permission required");
        }
    }
    @Override public String usernameById(String id) { return names.apply(id); }
    @Override public CurrentAccount requireEnabled(String username) {
        var account = accounts.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
        if (!account.enabled()) throw new DisabledException("Account unavailable");
        var configured=rbac.menus();
        var codes=configured.stream().map(com.nebula.iot.identity.domain.MenuRow::code).collect(java.util.stream.Collectors.toSet());
        var permissions=(account.platformAdmin() ? com.nebula.iot.identity.api.Permissions.ALL : java.util.Set.copyOf(rbac.effectivePermissions(account.id())))
            .stream().filter(permission -> !permission.startsWith("page:") || codes.contains(permission)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var navigation=configured.stream().filter(menu -> permissions.contains(menu.code()))
            .map(menu -> new com.nebula.iot.identity.api.Menus.Navigation(menu.code(),menu.name(),menu.groupName(),menu.path(),menu.sortOrder())).toList();
        return new CurrentAccount(account.id(),account.username(),account.platformAdmin(),permissions,account.tenantId(),navigation);
    }
    @Override public UserDetails loadUserByUsername(String username) {
        var account = accounts.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
        return User.withUsername(account.username()).password(account.passwordHash())
                .disabled(!account.enabled()).authorities("AUTHENTICATED").build();
    }
}
