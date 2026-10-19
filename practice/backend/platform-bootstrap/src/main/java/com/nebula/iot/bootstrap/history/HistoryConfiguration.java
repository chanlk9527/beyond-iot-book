package com.nebula.iot.bootstrap.history;

import com.nebula.iot.storage.HistoryStore;
import com.nebula.iot.storage.tdengine.TdengineHistoryStore;
import com.zaxxer.hikari.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class HistoryConfiguration {
    /** Owns its pool without exporting another DataSource bean into management database autoconfiguration. */
    public static final class Resource implements AutoCloseable {
        final HikariDataSource pool;
        final TdengineHistoryStore store;
        Resource(String url,String username,String password,String database,int days) {
            if(!url.startsWith("jdbc:TAOS-WS://") || password.isBlank()) throw new IllegalArgumentException("History requires a WebSocket JDBC URL and explicit password");
            var config=new HikariConfig(); config.setJdbcUrl(url); config.setUsername(username); config.setPassword(password);
            config.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver"); config.setMaximumPoolSize(4); config.setMinimumIdle(0);
            config.setConnectionTimeout(5000); config.setValidationTimeout(3000); config.setConnectionTestQuery("SELECT SERVER_VERSION()");
            config.setPoolName("device-history-tdengine"); config.addDataSourceProperty("varcharAsString","true");
            config.addDataSourceProperty("httpConnectTimeout","5000"); config.addDataSourceProperty("messageWaitTimeout","10000");
            pool=new HikariDataSource(config); store=new TdengineHistoryStore(pool,database,days);
            try{store.initialize();}catch(RuntimeException e){pool.close();throw e;}
        }
        @Override public void close(){pool.close();}
    }
    @Bean Object historyProviderValidation(@Value("${nebula.history.provider:disabled}") String provider) {
        if(!java.util.Set.of("disabled","tdengine").contains(provider)) throw new IllegalArgumentException("Unknown history provider: "+provider);
        return new Object();
    }
    @Bean(destroyMethod="close")
    @ConditionalOnExpression("${nebula.runtime.management:true} || ${nebula.runtime.worker:true}")
    @ConditionalOnProperty(name="nebula.history.provider",havingValue="tdengine")
    Resource tdengineHistoryResource(@Value("${nebula.history.tdengine.url}") String url,@Value("${nebula.history.tdengine.username}") String username,
            @Value("${nebula.history.tdengine.password}") String password,@Value("${nebula.history.tdengine.database:nebula_history}") String database,
            @Value("${nebula.history.maximum-retention-days:3650}") int days) {return new Resource(url,username,password,database,days);}
    @Bean
    @ConditionalOnExpression("${nebula.runtime.management:true} || ${nebula.runtime.worker:true}")
    @ConditionalOnProperty(name="nebula.history.provider",havingValue="tdengine")
    HistoryStore historyStore(Resource resource) {return resource.store;}
}
