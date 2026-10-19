package com.nebula.iot.cluster;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL CURRENT_TIMESTAMP is transaction start, not a lease clock after waiting on a row lock. */
public final class DatabaseTime {
    private final JdbcTemplate jdbc;
    private final String query;
    public DatabaseTime(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        var product=jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        query=switch (Objects.requireNonNull(product)) {
            case "PostgreSQL" -> "SELECT clock_timestamp()";
            case "MySQL" -> "SELECT UTC_TIMESTAMP(3)";
            // H2 is a same-host test adapter only; its SQL clock can be frozen for a whole transaction.
            case "H2" -> null;
            default -> throw new IllegalArgumentException("Unsupported cluster database: " + product);
        };
    }
    public Instant now() {
        if(query==null)return Instant.now();
        return Objects.requireNonNull(jdbc.queryForObject(query,(rs,row)->query.contains("clock_timestamp")
                ?rs.getObject(1,java.time.OffsetDateTime.class).toInstant()
                :rs.getObject(1,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)));
    }
}
