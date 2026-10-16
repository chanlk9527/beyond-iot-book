package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DirectorySeedMigrationTest {
 @Test void seedsDatabaseForExistingScopesAndPreservesMaintainedDirectories()throws Exception{
  var source=new DriverManagerDataSource("jdbc:h2:mem:directory-seed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
  String[] locations={"classpath:db/migration/identity","classpath:db/migration/tenant","classpath:db/migration/device"};
  Flyway.configure().dataSource(source).locations(locations).target("11").load().migrate();
  var jdbc=new JdbcTemplate(source);
  jdbc.update("INSERT INTO tenant(id,name) VALUES ('seed-tenant','Seed'),('customized','Customized')");
  jdbc.update("INSERT INTO device_product_directory(scope_key,tree_json,revision) VALUES ('customized','{\"revision\":7,\"items\":[]}',7)");
  var flyway=Flyway.configure().dataSource(source).locations(locations).target("12").load();
  flyway.migrate();
  assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM device_product_directory",Integer.class));
  var json=new ObjectMapper();
  for(String scope:new String[]{"platform","seed-tenant"}){
   var tree=json.readTree(jdbc.queryForObject("SELECT tree_json FROM device_product_directory WHERE scope_key=?",String.class,scope));
   assertEquals(4,tree.path("items").size());int children=0;
   for(var parent:tree.path("items"))children+=parent.path("children").size();
   assertEquals(14,children);
   assertEquals(1,jdbc.queryForObject("SELECT revision FROM device_product_directory WHERE scope_key=?",Long.class,scope));
  }
  assertEquals(7,jdbc.queryForObject("SELECT revision FROM device_product_directory WHERE scope_key='customized'",Long.class));
  assertEquals(0,json.readTree(jdbc.queryForObject("SELECT tree_json FROM device_product_directory WHERE scope_key='customized'",String.class)).path("items").size());
  assertEquals(0,flyway.migrate().migrationsExecuted);
 }
}
