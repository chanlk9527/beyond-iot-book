package com.nebula.iot.identity.domain;

import java.util.Optional;

public interface AccountRepository {
    Optional<Account> findByUsername(String username);
}
