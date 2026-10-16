package db.migration.device;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.*;
/** Give the existing infant monitor category its concise semantic code. */
public class V15__monitor_category_code extends BaseJavaMigration {
 @Override public void migrate(Context context)throws Exception{
  var db=context.getConnection();var json=new ObjectMapper();
  try(var select=db.prepareStatement("SELECT scope_key,tree_json FROM device_product_directory");var rows=select.executeQuery()){
   while(rows.next()){
    var tree=json.readTree(rows.getString(2));boolean changed=false;
    for(var p:tree.path("items"))for(var c:p.path("children"))if(c.path("id").asText().equals("rest-wece")&&c.path("label").asText().equals("婴儿监视器")){((ObjectNode)c).put("id","rest-moni");changed=true;}
    if(changed)try(var update=db.prepareStatement("UPDATE device_product_directory SET tree_json=?,revision=revision+1 WHERE scope_key=?")){update.setString(1,json.writeValueAsString(tree));update.setString(2,rows.getString(1));update.executeUpdate();}
   }
  }
  try(var select=db.prepareStatement("SELECT id,configuration FROM device_product");var rows=select.executeQuery()){
   while(rows.next()){var config=json.readTree(rows.getString(2));if(config.path("category").asText().equals("rest-wece")){((ObjectNode)config).put("category","rest-moni");try(var update=db.prepareStatement("UPDATE device_product SET configuration=?,revision=revision+1 WHERE id=?")){update.setString(1,json.writeValueAsString(config));update.setString(2,rows.getString(1));update.executeUpdate();}}}
  }
 }
}