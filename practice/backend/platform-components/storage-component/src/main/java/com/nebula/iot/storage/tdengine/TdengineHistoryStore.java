package com.nebula.iot.storage.tdengine;

import com.nebula.iot.storage.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** One deterministic subtable per device/attribute or device/event type; no per-product wide schema. */
public final class TdengineHistoryStore implements HistoryStore {
    private final DataSource source;
    private final String database;
    private final int retentionDays;
    public TdengineHistoryStore(DataSource source, String database, int retentionDays) {
        if (database == null || !database.matches("[a-z][a-z0-9_]{0,31}") || retentionDays < 1 || retentionDays > 36500)
            throw new IllegalArgumentException("Invalid TDengine history configuration");
        this.source = Objects.requireNonNull(source); this.database = database; this.retentionDays = retentionDays;
    }
    public void initialize() {
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.setQueryTimeout(10);
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS " + database + " PRECISION 'ms' DURATION 1 KEEP " + (retentionDays + 3));
            s.executeUpdate("CREATE STABLE IF NOT EXISTS " + database + ".observations_v1 (ts TIMESTAMP, message_id VARCHAR(128) COMPOSITE KEY, received_at TIMESTAMP, model_version VARCHAR(32), value_type VARCHAR(16), payload VARCHAR(16384)) TAGS (tenant_id VARCHAR(64), device_id VARCHAR(64), product_id VARCHAR(64), kind VARCHAR(16), identifier VARCHAR(64))");
        } catch (SQLException e) { throw new HistoryStorageException(e); }
    }
    @Override public Capabilities capabilities() { return new Capabilities("tdengine", true, true, false, false, false, false, retentionDays); }
    public static String tableName(Series series) {
        try {
            String identity = String.join("\n", series.tenant(), series.device(), series.product(), series.kind().name(), series.identifier());
            return "s_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String table(Series series) { return database + "." + tableName(series); }
    private static String literal(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    private void ensureTable(Connection c, Series series) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(10);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + table(series) + " USING " + database + ".observations_v1 TAGS ("
                    + String.join(",", List.of(series.tenant(), series.device(), series.product(), series.kind().name(), series.identifier()).stream().map(TdengineHistoryStore::literal).toList()) + ")");
        }
    }
    @Override public void append(List<Observation> observations) {
        if (observations.size() > 100) throw new IllegalArgumentException("History batch exceeds 100 observations");
        if (observations.isEmpty()) return;
        try (var c = source.getConnection()) {
            for (var o : observations) {
                ensureTable(c, o.series());
                try (var p = c.prepareStatement("INSERT INTO " + table(o.series()) + " VALUES (?,?,?,?,?,?)")) {
                    p.setQueryTimeout(10);
                    p.setLong(1, o.occurredAt().toEpochMilli()); p.setString(2, o.messageId()); p.setLong(3, o.receivedAt().toEpochMilli());
                    p.setString(4, o.modelVersion()); p.setString(5, o.valueType()); p.setString(6, o.payload()); p.executeUpdate();
                }
            }
        } catch (SQLException e) { throw new HistoryStorageException(e); }
    }
    @Override public Page query(Query q) {
        try (var c = source.getConnection()) {
            // Empty series is a real empty table, never a blanket SQL-error-to-empty fallback.
            ensureTable(c, q.series());
            String cursor = q.before() == null ? "" : " AND (ts < ? OR (ts = ? AND message_id < ?))";
            try (var p = c.prepareStatement("SELECT ts,message_id,received_at,model_version,value_type,payload FROM " + table(q.series())
                    + " WHERE ts >= ? AND ts < ?" + cursor + " ORDER BY ts DESC,message_id DESC LIMIT " + (q.limit() + 1))) {
                p.setQueryTimeout(10); p.setLong(1, q.from().toEpochMilli()); p.setLong(2, q.to().toEpochMilli());
                if (q.before() != null) { p.setLong(3, q.before().occurredAt().toEpochMilli()); p.setLong(4, q.before().occurredAt().toEpochMilli()); p.setString(5, q.before().messageId()); }
                var items = new ArrayList<Observation>();
                try (var rs = p.executeQuery()) {
                    while (rs.next()) items.add(new Observation(q.series(), Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2),
                            Instant.ofEpochMilli(rs.getLong(3)), rs.getString(4), rs.getString(5), rs.getString(6)));
                }
                boolean more = items.size() > q.limit();
                if (more) items.removeLast();
                var last = items.isEmpty() ? null : items.getLast();
                return new Page(items, more ? new Cursor(last.occurredAt(), last.messageId()) : null);
            }
        } catch (SQLException e) { throw new HistoryStorageException(e); }
    }
    @Override public void deleteBefore(Series series, Instant cutoff) {
        try (var c = source.getConnection()) {
            ensureTable(c, series);
            // The WebSocket driver's bound executeUpdate supports INSERT only. These SQL parts are
            // generated identifiers and an epoch long, with no user-supplied SQL text.
            try (var s = c.createStatement()) {
                s.setQueryTimeout(10);
                s.executeUpdate("DELETE FROM " + table(series) + " WHERE ts < " + cutoff.toEpochMilli());
            }
        } catch (SQLException e) { throw new HistoryStorageException(e); }
    }
}
