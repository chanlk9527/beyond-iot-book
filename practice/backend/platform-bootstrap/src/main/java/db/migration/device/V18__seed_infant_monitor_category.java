package db.migration.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extend the initial directory for both fresh databases and existing installations. */
public class V18__seed_infant_monitor_category extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var db = context.getConnection();
        var json = new ObjectMapper();
        try (var select = db.prepareStatement("SELECT scope_key,tree_json FROM device_product_directory");
                var rows = select.executeQuery()) {
            while (rows.next()) {
                var tree = json.readTree(rows.getString(2));
                boolean changed = false;
                for (var parent : tree.path("items")) {
                    if (!parent.path("id").asText().equals("rest")) continue;
                    if (!(parent.path("children") instanceof ArrayNode children)) continue;
                    boolean exists = false;
                    for (var child : children) {
                        if (child.path("id").asText().equals("rest-moni")
                                || child.path("label").asText().equals("婴儿监视器")) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        var monitor = children.addObject();
                        monitor.put("id", "rest-moni");
                        monitor.put("label", "婴儿监视器");
                        monitor.putArray("children");
                        changed = true;
                    }
                }
                if (changed) {
                    try (var update = db.prepareStatement(
                            "UPDATE device_product_directory SET tree_json=?,revision=revision+1 WHERE scope_key=?")) {
                        update.setString(1, json.writeValueAsString(tree));
                        update.setString(2, rows.getString(1));
                        update.executeUpdate();
                    }
                }
            }
        }
    }
}
