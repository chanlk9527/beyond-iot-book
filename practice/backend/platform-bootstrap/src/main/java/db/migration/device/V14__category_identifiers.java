package db.migration.device;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Assign immutable English identifiers without replacing existing labels or product metadata. */
public class V14__category_identifiers extends BaseJavaMigration {
 private static final Map<String,String> CODES=Map.ofEntries(
  Map.entry("喂养电器","feed"),Map.entry("清洁消毒","clen"),Map.entry("睡眠环境","rest"),Map.entry("护理电器","care"),
  Map.entry("暖奶器","warm"),Map.entry("恒温调奶器","temp"),Map.entry("自动冲奶机","mix"),Map.entry("辅食料理机","prep"),Map.entry("电动吸乳器","pump"),
  Map.entry("奶瓶消毒烘干器","ster"),Map.entry("奶瓶清洗机","wash"),Map.entry("婴儿衣物烘干机","dry"),
  Map.entry("婴儿房加湿器","humi"),Map.entry("婴儿房空气净化器","pure"),Map.entry("白噪音安抚器","nois"),Map.entry("婴儿监视器","moni"),
  Map.entry("婴儿理发器","trim"),Map.entry("电动吸鼻器","nasp"),Map.entry("婴儿恒温浴盆","bath"));
 private String code(String label,Set<String> used){
  String known=CODES.get(label);if(known!=null&&used.add(known))return known;
  int n=Math.floorMod(label.hashCode(),26*26*26*26);
  for(int i=0;i<26*26*26*26;i++){int value=(n+i)%(26*26*26*26);char[] chars=new char[4];for(int k=3;k>=0;k--){chars[k]=(char)('a'+value%26);value/=26;}String candidate=new String(chars);if(used.add(candidate))return candidate;}
  throw new IllegalStateException("Category identifier space exhausted");
 }
 @Override public void migrate(Context context)throws Exception{
  var db=context.getConnection();var json=new ObjectMapper();Map<String,Map<String,String>> paths=new HashMap<>();
  try(var select=db.prepareStatement("SELECT scope_key,tree_json FROM device_product_directory");var rows=select.executeQuery()){
   while(rows.next()){
    String scope=rows.getString(1);ObjectNode tree=(ObjectNode)json.readTree(rows.getString(2));Map<String,String> names=new HashMap<>();Set<String> parents=new HashSet<>();
    for(JsonNode parent:tree.withArray("items")){
     String pid=code(parent.path("label").asText(),parents);((ObjectNode)parent).put("id",pid);Set<String> children=new HashSet<>();
     for(JsonNode child:parent.path("children")){String id=pid+"-"+code(child.path("label").asText(),children);((ObjectNode)child).put("id",id);names.put(parent.path("label").asText()+" / "+child.path("label").asText(),id);}
    }
    paths.put(scope,names);
    try(var update=db.prepareStatement("UPDATE device_product_directory SET tree_json=?,revision=revision+1 WHERE scope_key=?")){update.setString(1,json.writeValueAsString(tree));update.setString(2,scope);update.executeUpdate();}
   }
  }
  try(var select=db.prepareStatement("SELECT id,scope_key,configuration FROM device_product");var rows=select.executeQuery()){
   while(rows.next()){
    ObjectNode config=(ObjectNode)json.readTree(rows.getString(3));String previous=config.path("category").asText();
    String id=paths.getOrDefault(rows.getString(2),Map.of()).get(previous);
    if(id==null)id=paths.getOrDefault("platform",Map.of()).get(previous);
    if(id!=null){config.put("category",id);try(var update=db.prepareStatement("UPDATE device_product SET configuration=?,revision=revision+1 WHERE id=?")){update.setString(1,json.writeValueAsString(config));update.setString(2,rows.getString(1));update.executeUpdate();}}
   }
  }
 }
}