package com.nebula.iot.identity.infrastructure;

import com.nebula.iot.identity.domain.Account;
import com.nebula.iot.identity.domain.AccountRepository;
import java.util.Optional;

public final class MyBatisAccountRepository implements AccountRepository {
    private final AccountMapper mapper;
    public MyBatisAccountRepository(AccountMapper mapper) { this.mapper = mapper; }
    @Override public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username));
    }
}
