package com.nebula.iot.storage;

import com.nebula.iot.storage.HistoryStore.*;
import com.nebula.iot.storage.tdengine.TdengineHistoryStore;
import com.zaxxer.hikari.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real server acceptance; never treats a JDBC mock as TDengine validation. */
@EnabledIfEnvironmentVariable(named="TDENGINE_TEST_URL",matches=".+")
class TdengineLiveTest {
    @Test void realServerPersistsRetriesSameTimestampsEventsAndCursors() throws Exception {
        String db="nebula_it_"+Long.toUnsignedString(System.nanoTime());
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TDENGINE_TEST_URL"));
        config.setUsername(System.getenv().getOrDefault("TDENGINE_TEST_USERNAME","root"));
        config.setPassword(System.getenv("TDENGINE_TEST_PASSWORD"));config.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        config.setMaximumPoolSize(2);config.setConnectionTestQuery("SELECT SERVER_VERSION()");config.addDataSourceProperty("varcharAsString","true");
        try(var pool=new HikariDataSource(config)) {
            var store=new TdengineHistoryStore(pool,db,30);
            try {
                store.initialize();var at=Instant.ofEpochMilli(Instant.now().toEpochMilli());
                var property=new Series("ta","d1","p1",Kind.PROPERTY,"power");var event=new Series("ta","d1","p1",Kind.EVENT,"power");
                var a=new Observation(property,at,"a",at,"v1","INTEGER","9223372036854775807");
                var b=new Observation(property,at,"b",at,"v1","DECIMAL","25.1234567890123456789");
                var e=new Observation(event,at,"e",at,"v1","EVENT","{\"code\":\"故障'\\\\\"}");
                store.append(List.of(a,b,e));store.append(List.of(a,b,e));
                var q=new Query(property,at.minusSeconds(1),at.plusSeconds(1),null,1);var first=store.query(q);
                assertEquals(List.of(b),first.items());assertNotNull(first.next());
                assertEquals(List.of(a),store.query(new Query(property,q.from(),q.to(),first.next(),1)).items());
                assertEquals(List.of(e),store.query(new Query(event,q.from(),q.to(),null,10)).items());
                store.deleteBefore(property,at.plusMillis(1));assertTrue(store.query(q).items().isEmpty());
                assertEquals(1,store.query(new Query(event,q.from(),q.to(),null,10)).items().size());
            } finally {
                // Only this test's generated database is removed.
                try(var c=pool.getConnection();var s=c.createStatement()){s.executeUpdate("DROP DATABASE IF EXISTS "+db);}
            }
        }
    }
}
