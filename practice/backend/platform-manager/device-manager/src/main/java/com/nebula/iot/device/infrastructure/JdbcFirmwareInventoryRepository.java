package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcFirmwareInventoryRepository implements FirmwareInventoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcFirmwareInventoryRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public java.util.Map<String,FirmwareInventoryDocument> readMany(java.util.List<String> ids) {
        if(ids.isEmpty()) return java.util.Map.of();
        var result=new java.util.HashMap<String,FirmwareInventoryDocument>();
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        jdbc.query("SELECT device_id,document_json FROM device_firmware_inventory WHERE device_id IN ("+placeholders+")",
                (org.springframework.jdbc.core.RowCallbackHandler)r->result.put(r.getString(1),decode(r.getString(2))),ids.toArray());
        return result;
    }
    public FirmwareInventoryDocument read(String scope,String id) {
        return jdbc.query("SELECT document_json FROM device_firmware_inventory WHERE scope_key=? AND device_id=?",(r,n)->decode(r.getString(1)),scope,id)
                .stream().findFirst().orElse(FirmwareInventoryDocument.empty());
    }
    public void save(String scope,String id,FirmwareInventoryDocument doc) {
        String value;
        try { value=json.writeValueAsString(doc); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid inventory",e); }
        if (value.length()>32000) throw new IllegalArgumentException("Inventory too large");
        if (jdbc.update("UPDATE device_firmware_inventory SET document_json=? WHERE scope_key=? AND device_id=?",value,scope,id)==0)
            jdbc.update("INSERT INTO device_firmware_inventory(scope_key,device_id,document_json) VALUES(?,?,?)",scope,id,value);
    }
    private FirmwareInventoryDocument decode(String value) {
        try { return json.readValue(value,FirmwareInventoryDocument.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored inventory",e); }
    }
    public String fingerprint(String id,String messageId,String kind) {
        return jdbc.query("SELECT fingerprint FROM device_firmware_report WHERE device_id=? AND message_id=? AND report_kind=?",(r,n)->r.getString(1),id,messageId,kind)
                .stream().findFirst().orElse(null);
    }
    public void remember(String id,String messageId,String kind,String fingerprint,long receivedAt) {
        jdbc.update("INSERT INTO device_firmware_report(device_id,message_id,report_kind,fingerprint,received_at) VALUES(?,?,?,?,?)",id,messageId,kind,fingerprint,receivedAt);
    }
    public void cleanup(long before) {
        var rows=jdbc.query("SELECT device_id,message_id,report_kind FROM device_firmware_report WHERE received_at<? ORDER BY received_at LIMIT 1000",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},before);
        for (var row:rows) jdbc.update("DELETE FROM device_firmware_report WHERE device_id=? AND message_id=? AND report_kind=? AND received_at<?",row[0],row[1],row[2],before);
    }
}
