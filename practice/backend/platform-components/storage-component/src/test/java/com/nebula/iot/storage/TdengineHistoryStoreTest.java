package com.nebula.iot.storage;

import com.nebula.iot.storage.HistoryStore.*;
import com.nebula.iot.storage.tdengine.TdengineHistoryStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TdengineHistoryStoreTest {
    DataSource source; Connection connection; Statement statement; PreparedStatement prepared;
    TdengineHistoryStore store;
    final Instant at=Instant.parse("2026-10-19T00:00:00Z");
    Series series(Kind kind,String id){return new Series("tenant","device","product",kind,id);}
    @BeforeEach void setup() throws Exception {
        source=mock(DataSource.class);connection=mock(Connection.class);statement=mock(Statement.class);prepared=mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        store=new TdengineHistoryStore(source,"test_history",3650);
    }
    @Test void identitiesIncludeTenantProductKindAndIdentifier(){
        var s=series(Kind.PROPERTY,"power");var name=TdengineHistoryStore.tableName(s);
        assertTrue(name.matches("s_[a-f0-9]{64}"));assertEquals(name,TdengineHistoryStore.tableName(s));
        assertNotEquals(name,TdengineHistoryStore.tableName(series(Kind.EVENT,"power")));
        assertNotEquals(name,TdengineHistoryStore.tableName(new Series("other","device","product",Kind.PROPERTY,"power")));
        assertThrows(IllegalArgumentException.class,()->new Series("tenant","device","product",Kind.PROPERTY,"x';DROP DATABASE test"));
    }
    @Test void initializesCompositeIdentityAndBindsAllObservationValues() throws Exception {
        store.initialize();verify(statement).executeUpdate(contains("message_id VARCHAR(128) COMPOSITE KEY"));
        String hostile="id'\\;DROP DATABASE x";
        store.append(List.of(new Observation(series(Kind.PROPERTY,"temperature"),at,hostile,at,"v1","DECIMAL","25.1234567890123456789")));
        verify(prepared).setString(2,hostile);verify(prepared).setString(6,"25.1234567890123456789");
        verify(prepared).setLong(1,at.toEpochMilli());verify(prepared).executeUpdate();
        verify(connection).prepareStatement("INSERT INTO test_history."+TdengineHistoryStore.tableName(series(Kind.PROPERTY,"temperature"))+" VALUES (?,?,?,?,?,?)");
    }
    @Test void pagesEqualTimestampsUsingMessageIdsAndScopesDeletesToExactSeries() throws Exception {
        var rs=mock(ResultSet.class);when(prepared.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true,true,true,false);when(rs.getLong(1)).thenReturn(at.toEpochMilli());when(rs.getLong(3)).thenReturn(at.toEpochMilli());
        when(rs.getString(2)).thenReturn("c","b","a");when(rs.getString(4)).thenReturn("v1");when(rs.getString(5)).thenReturn("TEXT");when(rs.getString(6)).thenReturn("\"value\"");
        var cursor=new Cursor(at,"z' OR 1=1");var s=series(Kind.EVENT,"fault");
        var page=store.query(new Query(s,at.minusSeconds(1),at.plusSeconds(1),cursor,2));
        assertEquals(List.of("c","b"),page.items().stream().map(Observation::messageId).toList());assertEquals(new Cursor(at,"b"),page.next());
        verify(prepared).setString(5,cursor.messageId());
        verify(connection).prepareStatement(contains("ORDER BY ts DESC,message_id DESC LIMIT 3"));
        store.deleteBefore(s,at);
        verify(statement).executeUpdate("DELETE FROM test_history."+TdengineHistoryStore.tableName(s)+" WHERE ts < "+at.toEpochMilli());
        verify(connection,never()).prepareStatement(startsWith("DELETE"));
    }
    @Test void failuresNeverMasqueradeAsEmptyHistoryAndCapabilitiesAreHonest() throws Exception {
        when(source.getConnection()).thenThrow(new SQLException("password=secret"));
        var error=assertThrows(HistoryStorageException.class,()->store.query(new Query(series(Kind.PROPERTY,"power"),at.minusSeconds(1),at,null,10)));
        assertFalse(error.getMessage().contains("secret"));assertFalse(store.capabilities().countRetention());assertFalse(store.capabilities().persistentAggregates());
    }
}
