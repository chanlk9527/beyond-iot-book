package com.nebula.iot.device.infrastructure;

import com.nebula.iot.device.api.ProductionViews.*;
import com.nebula.iot.device.domain.ProductionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcProductionRepository implements ProductionRepository {
    private final JdbcTemplate jdbc;
    public JdbcProductionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = "SELECT b.*,p.name AS product_name FROM device_production_batch b JOIN device_product p ON p.id=b.product_id ";
    private static final String FILTER = "WHERE (COALESCE(?, '')='' OR b.scope_key=?) AND (POSITION(LOWER(?) IN LOWER(b.code))>0 OR POSITION(LOWER(?) IN LOWER(b.name))>0) ";
    private Batch map(ResultSet r, int n) throws SQLException {
        return new Batch(r.getString("id"),r.getString("tenant_id"),r.getString("scope_key"),r.getString("code"),r.getString("name"),
                r.getString("product_id"),r.getString("product_name"),r.getString("model_version"),r.getInt("quantity"),
                r.getString("hardware_version"),r.getString("firmware_version"),r.getString("status"),r.getLong("revision"),
                r.getString("recipient"),r.getString("delivery_ref"),r.getLong("created_at"),r.getObject("delivered_at",Long.class));
    }
    public List<Batch> batches(String scope,String query,int limit,long offset) {
        return jdbc.query(SELECT+FILTER+"ORDER BY b.created_at DESC,b.id LIMIT ? OFFSET ?",this::map,scope,scope,query,query,limit,offset);
    }
    public long count(String scope,String query) { return jdbc.queryForObject("SELECT COUNT(*) FROM device_production_batch b "+FILTER,Long.class,scope,scope,query,query); }
    public Batch batch(String id,boolean lock) {
        // Lock only the batch row, consistently before the product row in all mutations.
        if(lock) return jdbc.query("SELECT b.*,(SELECT p.name FROM device_product p WHERE p.id=b.product_id) AS product_name FROM device_production_batch b WHERE b.id=? FOR UPDATE",this::map,id).stream().findFirst().orElse(null);
        return jdbc.query(SELECT+"WHERE b.id=?",this::map,id).stream().findFirst().orElse(null);
    }
    public void insert(Batch b) {
        jdbc.update("INSERT INTO device_production_batch(id,scope_key,tenant_id,code,name,product_id,model_version,quantity,hardware_version,firmware_version,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                b.id(),b.scopeKey(),b.tenantId(),b.code(),b.name(),b.productId(),b.modelVersion(),b.quantity(),b.hardwareVersion(),b.firmwareVersion(),b.status(),b.createdAt());
    }
    public void advance(String id,String status) { jdbc.update("UPDATE device_production_batch SET status=?,revision=revision+1 WHERE id=?",status,id); }
    public void deliver(String id,String recipient,String reference,long now) {
        jdbc.update("UPDATE device_production_batch SET status='DELIVERED',recipient=?,delivery_ref=?,delivered_at=?,revision=revision+1 WHERE id=?",recipient,reference,now,id);
    }
    public List<Item> items(String batchId) {
        return jdbc.query("SELECT i.*,d.code,d.name,d.lifecycle,d.credential_version FROM device_production_item i JOIN device_registration d ON d.id=i.device_id WHERE i.batch_id=? ORDER BY i.sequence_number",
                (r,n)->new Item(r.getString("device_id"),r.getString("code"),r.getString("name"),r.getInt("sequence_number"),r.getString("lifecycle"),r.getLong("credential_version"),
                        r.getObject("burned_version",Long.class),r.getObject("tested_version",Long.class),r.getString("test_result")),batchId);
    }
    public void addItem(String batchId,String deviceId,int sequence) { jdbc.update("INSERT INTO device_production_item(batch_id,device_id,sequence_number) VALUES(?,?,?)",batchId,deviceId,sequence); }
    public void burned(String batchId,String deviceId,long version) {
        jdbc.update("UPDATE device_production_item SET burned_version=?,tested_version=NULL,test_result=NULL WHERE batch_id=? AND device_id=?",version,batchId,deviceId);
    }
    public void tested(String batchId,String deviceId,long version,String result) {
        jdbc.update("UPDATE device_production_item SET tested_version=?,test_result=? WHERE batch_id=? AND device_id=?",version,result,batchId,deviceId);
    }
    public List<Event> events(String batchId,int page) {
        return jdbc.query("SELECT * FROM device_production_event WHERE batch_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100 OFFSET ?",
                (r,n)->new Event(r.getString("id"),r.getString("device_id"),r.getString("actor"),r.getString("action"),r.getString("station"),r.getString("result"),r.getString("note"),r.getObject("credential_version",Long.class),r.getLong("occurred_at")),batchId,((long)page-1)*100);
    }
    public long eventCount(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM device_production_event WHERE batch_id=?",Long.class,id); }
    public void event(String batchId,Event e) {
        jdbc.update("INSERT INTO device_production_event(id,batch_id,device_id,actor,action,station,result,note,credential_version,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                e.id(),batchId,e.deviceId(),e.actor(),e.action(),e.station(),e.result(),e.note(),e.credentialVersion(),e.occurredAt());
    }
}
