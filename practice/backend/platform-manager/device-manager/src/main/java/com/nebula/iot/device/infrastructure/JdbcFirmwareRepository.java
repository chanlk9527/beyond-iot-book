package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.api.FirmwareViews.*;
import com.nebula.iot.device.domain.FirmwareRepository;
import com.nebula.iot.device.domain.FirmwareFile;
import com.nebula.iot.file.ObjectStorage.Reference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcFirmwareRepository implements FirmwareRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcFirmwareRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public List<Channel> channels(String productId) {
        return jdbc.query("SELECT * FROM device_firmware_channel WHERE product_id=? ORDER BY channel_id",
                (r,n) -> new Channel(r.getInt("channel_id"), r.getString("component"), r.getString("name"), r.getString("version_rule"), r.getLong("created_at")), productId);
    }
    public void insertChannel(String productId, String scope, Channel c) {
        jdbc.update("INSERT INTO device_firmware_channel(product_id,scope_key,channel_id,component,name,version_rule,created_at) VALUES(?,?,?,?,?,?,?)",
                productId, scope, c.channel(), c.component(), c.name(), c.versionRule(), c.createdAt());
    }
    private Version map(ResultSet r, int n) throws SQLException {
        try {
            return new Version(r.getString("id"), r.getInt("channel_id"), r.getString("version"), r.getString("description"),
                    List.of(json.readValue(r.getString("source_versions"), String[].class)),
                    List.of(json.readValue(r.getString("hardware_versions"), String[].class)),
                    json.readValue(r.getString("artifact"), Artifact.class), r.getBoolean("enabled"), r.getLong("revision"), r.getLong("created_at"));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Stored firmware metadata is invalid", e); }
    }
    public List<Version> versions(String productId, int channel, int limit, long offset) {
        return jdbc.query("SELECT * FROM device_firmware_version WHERE product_id=? AND channel_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map, productId, channel, limit, offset);
    }
    public long count(String productId, int channel) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM device_firmware_version WHERE product_id=? AND channel_id=?", Long.class, productId, channel);
    }
    public Version version(String productId, int channel, String id) {
        return jdbc.query("SELECT * FROM device_firmware_version WHERE product_id=? AND channel_id=? AND id=?", this::map, productId, channel, id).stream().findFirst().orElse(null);
    }
    public void insertVersion(String productId, Version v) {
        jdbc.update("INSERT INTO device_firmware_version(id,product_id,channel_id,version,description,source_versions,hardware_versions,artifact,enabled,revision,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                v.id(), productId, v.channel(), v.version(), v.description(), encode(v.sourceVersions()), encode(v.hardwareVersions()), encode(v.artifact()), v.enabled(), v.revision(), v.createdAt());
    }
    public int status(String productId, String id, boolean enabled, long revision) {
        return jdbc.update("UPDATE device_firmware_version SET enabled=?,revision=revision+1 WHERE product_id=? AND id=? AND revision=?", enabled, productId, id, revision);
    }
    public FirmwareFile file(String productId,int channel,String id) {
        return jdbc.query("SELECT * FROM device_firmware_file WHERE product_id=? AND channel_id=? AND id=?", (r,n)-> {
            try { return new FirmwareFile(r.getString("id"),json.readValue(r.getString("storage_reference"),Reference.class),r.getLong("size_bytes"),r.getString("sha256"),r.getString("key_id"),r.getString("signature"),r.getLong("created_at")); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored file reference",e); }
        },productId,channel,id).stream().findFirst().orElse(null);
    }
    public void insertFile(String productId,int channel,FirmwareFile f) {
        jdbc.update("INSERT INTO device_firmware_file(id,product_id,channel_id,storage_reference,size_bytes,sha256,key_id,signature,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                f.id(),productId,channel,encode(f.reference()),f.size(),f.sha256(),f.keyId(),f.signature(),f.createdAt());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid firmware metadata", e); }
    }
}
