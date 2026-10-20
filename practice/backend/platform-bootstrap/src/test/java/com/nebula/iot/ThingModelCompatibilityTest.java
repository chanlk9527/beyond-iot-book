package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.core.model.*;
import com.nebula.iot.device.api.CatalogViews.ModelSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThingModelCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test
    void oldSnapshotsAndNewMetadataRoundTrip() throws Exception {
        var old = json.readValue("""
            {"properties":{"power":{"id":"power","schema":{"type":"BOOLEAN"},"access":"READ_WRITE"}},
             "operations":{"run":{"id":"run","inputs":{}}},"events":{}}
            """, ModelSpec.class);
        assertEquals("", old.properties().get("power").name());
        assertEquals(old, json.readValue(json.writeValueAsString(old), ModelSpec.class));
        var model = json.readValue("""
            {"properties":{"mode":{"id":"mode","name":"模式","description":"当前工作模式",
             "schema":{"type":"ENUM","enumValues":{"heat":"加热","warm":"保温"}},"access":"READ_WRITE"}},
             "operations":{},"events":{}}
            """, ModelSpec.class);
        assertEquals("模式", model.properties().get("mode").name());
        model.properties().get("mode").schema().validate(new Value.TextValue("heat"));
        assertEquals(model, json.readValue(json.writeValueAsString(model), ModelSpec.class));
        assertThrows(Exception.class, () -> json.readValue("""
            {"properties":{"mode":{"id":"mode","schema":{"type":"ENUM","enumValues":{}},"access":"READ_WRITE"}},
             "operations":{},"events":{}}
            """, ModelSpec.class));
    }
    @Test
    void arraysRoundTripThroughWireModelAndDurableCommands() throws Exception {
        var schema = json.readValue("""
            {"type":"ARRAY","items":{"type":"ENUM","enumValues":{"heat":"加热"}},"minItems":1,"maxItems":3}
            """, ValueSchema.class);
        var raw = json.readTree("[\"heat\",\"heat\"]");
        var value = com.nebula.iot.protocol.ValueJson.fromJson(raw);
        schema.validate(value);
        assertEquals(raw, com.nebula.iot.protocol.ValueJson.toJson(value));
        assertEquals(schema, json.readValue(json.writeValueAsString(schema), ValueSchema.class));
        for (var invalid : java.util.List.of("[[1]]", "[null]", "[{}]"))
            assertThrows(IllegalArgumentException.class, () -> com.nebula.iot.protocol.ValueJson.fromJson(json.readTree(invalid)));
        assertThrows(Exception.class, () -> json.readValue("{\"type\":\"DATETIME\"}", ValueSchema.class));
        var now = java.time.Instant.now();
        var request = new com.nebula.iot.core.command.DeviceDownlink(
            new com.nebula.iot.core.identity.MessageId("m1"),
            new com.nebula.iot.core.device.DeviceKey(new com.nebula.iot.core.identity.TenantId("t1"), new com.nebula.iot.core.identity.DeviceId("d1")),
            "v1", now, now.plusSeconds(30), java.util.Optional.empty(), java.util.Optional.empty(),
            new com.nebula.iot.core.command.DownlinkBody.PropertyWrite(java.util.Map.of("modes", value)));
        var commands = new com.nebula.iot.device.infrastructure.CommandJson();
        assertEquals(request, commands.decode(commands.encode(request)));
        var packet = new com.nebula.iot.protocol.official.OfficialMqttCodec().encode(request, com.nebula.iot.core.protocol.Transport.MQTT);
        var encoded = json.readTree(packet.payload().copyBytes());
        assertEquals(raw, encoded.path("data").path("properties").path("modes"));
    }}