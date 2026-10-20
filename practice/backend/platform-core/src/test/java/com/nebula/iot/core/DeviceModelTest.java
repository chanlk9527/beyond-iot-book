package com.nebula.iot.core;

import com.nebula.iot.core.device.ProductKey;
import com.nebula.iot.core.identity.*;
import com.nebula.iot.core.model.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeviceModelTest {
    private static final ValueSchema BRIGHTNESS = new ValueSchema(ValueType.INTEGER,
            BigDecimal.ONE, BigDecimal.valueOf(100), null);

    private DeviceModel warmer() {
        return new DeviceModel(new ProductKey(new TenantId("tenant-a"), new ProductId("warmer")), "v1",
                Map.of("power", new PropertyDefinition("power", ValueSchema.of(ValueType.BOOLEAN), PropertyAccess.READ_ONLY),
                        "heatingLevel", new PropertyDefinition("heatingLevel", BRIGHTNESS, PropertyAccess.READ_WRITE),
                        "writeOnly", new PropertyDefinition("writeOnly", BRIGHTNESS, PropertyAccess.WRITE_ONLY)),
                Map.of("overheat", new EventDefinition("overheat", Map.of(
                        "temperature", new FieldDefinition("temperature", ValueSchema.of(ValueType.DECIMAL), true)))),
                Map.of("setHeatingLevel", new OperationDefinition("setHeatingLevel", Map.of(
                        "heatingLevel", new FieldDefinition("heatingLevel", BRIGHTNESS, true)))));
    }

    @Test
    void partialReportsAndValidOperationAreAccepted() {
        var model = warmer();
        assertDoesNotThrow(() -> model.validateReport(Map.of("heatingLevel", new Value.IntegerValue(70))));
        assertDoesNotThrow(() -> model.validateReport(Map.of("power", new Value.BooleanValue(false))));
        assertDoesNotThrow(() -> model.validateOperation("setHeatingLevel", Map.of("heatingLevel", new Value.IntegerValue(70))));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 101, Long.MIN_VALUE, Long.MAX_VALUE})
    void outOfRangeReportsAndCommandsAreRejected(long heatingLevel) {
        var values = Map.<String, Value>of("heatingLevel", new Value.IntegerValue(heatingLevel));
        assertThrows(IllegalArgumentException.class, () -> warmer().validateReport(values));
        assertThrows(IllegalArgumentException.class, () -> warmer().validateOperation("setHeatingLevel", values));
    }

    @Test
    void missingRequiredUnknownAndWrongTypedInputsAreRejected() {
        var model = warmer();
        assertThrows(IllegalArgumentException.class, () -> model.validateOperation("setHeatingLevel", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> model.validateOperation("missing", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> model.validateOperation("setHeatingLevel", Map.of(
                "heatingLevel", new Value.TextValue("70"))));
        assertThrows(IllegalArgumentException.class, () -> model.validateOperation("setHeatingLevel", Map.of(
                "heatingLevel", new Value.IntegerValue(70), "extra", new Value.BooleanValue(true))));
    }

    @Test
    void nonReportableAndUnknownPropertiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> warmer().validateReport(Map.of("writeOnly", new Value.IntegerValue(1))));
        assertThrows(IllegalArgumentException.class, () -> warmer().validateReport(Map.of("unknown", new Value.BooleanValue(true))));
    }

    @Test
    void eventsValidateRequiredFieldsAndPermitIntegerToDecimalWidening() {
        assertDoesNotThrow(() -> warmer().validateEvent("overheat", Map.of("temperature", new Value.IntegerValue(80))));
        assertThrows(IllegalArgumentException.class, () -> warmer().validateEvent("overheat", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> warmer().validateEvent("unknown", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BRIGHTNESS.validate(new Value.DecimalValue(new BigDecimal("70.0"))));
    }

    @Test
    void definitionMapsAreSnapshotsAndMismatchedKeysAreRejected() {
        var definitions = new HashMap<String, FieldDefinition>();
        definitions.put("heatingLevel", new FieldDefinition("heatingLevel", BRIGHTNESS, true));
        var operation = new OperationDefinition("setHeatingLevel", definitions);
        definitions.clear();
        assertEquals(1, operation.inputs().size());
        assertThrows(UnsupportedOperationException.class, () -> operation.inputs().clear());
        assertThrows(IllegalArgumentException.class, () -> new OperationDefinition("setHeatingLevel", Map.of(
                "wrong", new FieldDefinition("heatingLevel", BRIGHTNESS, true))));
        assertThrows(IllegalArgumentException.class, () -> new DeviceModel(warmer().product(), "v1", Map.of(
                "wrong", new PropertyDefinition("heatingLevel", BRIGHTNESS, PropertyAccess.READ_ONLY)), Map.of(), Map.of()));
    }

    @Test
    void invalidSchemaConstraintsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.INTEGER, BigDecimal.TEN, BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.INTEGER, new BigDecimal("1.1"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.BOOLEAN, BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.DECIMAL, null, null, 3));
    }

    @Test
    void decimalBoundariesAndUnicodeTextLengthAreExact() {
        var decimal = new ValueSchema(ValueType.DECIMAL, new BigDecimal("0.1"), new BigDecimal("0.3"), null);
        assertDoesNotThrow(() -> decimal.validate(new Value.DecimalValue(new BigDecimal("0.3"))));
        assertThrows(IllegalArgumentException.class, () -> decimal.validate(new Value.DecimalValue(new BigDecimal("0.3000000000000000001"))));
        var text = new ValueSchema(ValueType.TEXT, null, null, 1);
        assertDoesNotThrow(() -> text.validate(new Value.TextValue("\uD83D\uDCA1")));
        assertThrows(IllegalArgumentException.class, () -> text.validate(new Value.TextValue("ab")));
    }
    @Test
    void enumsAndArraysValidateTheirWireValues() {
        var options = new HashMap<>(Map.of("heat", "加热", "warm", "保温"));
        var schema = new ValueSchema(ValueType.ENUM, null, null, null, options, null);
        options.clear();
        assertEquals(2, schema.enumValues().size());
        assertDoesNotThrow(() -> schema.validate(new Value.TextValue("heat")));
        assertThrows(IllegalArgumentException.class, () -> schema.validate(new Value.TextValue("unknown")));
        assertThrows(IllegalArgumentException.class, () -> schema.validate(new Value.IntegerValue(1)));
        assertThrows(IllegalArgumentException.class, () -> ValueSchema.of(ValueType.ENUM));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.TEXT, null, null, null, Map.of("a", "A"), ""));
        var items = new ValueSchema(ValueType.INTEGER, BigDecimal.ZERO, BigDecimal.TEN, null);
        var array = new ValueSchema(ValueType.ARRAY, null, null, null, null, null, items, 1, 2);
        assertDoesNotThrow(() -> array.validate(new Value.ArrayValue(java.util.List.of(new Value.IntegerValue(3)))));
        assertThrows(IllegalArgumentException.class, () -> array.validate(new Value.ArrayValue(java.util.List.of())));
        assertThrows(IllegalArgumentException.class, () -> array.validate(new Value.ArrayValue(java.util.List.of(new Value.IntegerValue(11)))));
        assertThrows(IllegalArgumentException.class, () -> array.validate(new Value.ArrayValue(java.util.List.of(new Value.TextValue("3")))));
        assertThrows(IllegalArgumentException.class, () -> array.validate(new Value.ArrayValue(java.util.List.of(new Value.IntegerValue(1), new Value.IntegerValue(2), new Value.IntegerValue(3)))));
        assertThrows(IllegalArgumentException.class, () -> ValueSchema.of(ValueType.ARRAY));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.ARRAY, null, null, null, null, null, array, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.ARRAY, null, null, null, null, null, items, 5, 2));
        assertThrows(IllegalArgumentException.class, () -> new Value.ArrayValue(java.util.List.of(new Value.ArrayValue(java.util.List.of()))));
    }
    @Test
    void metadataSupportsLegacyModelsAndLimitsUserInput() {
        assertEquals("", new OperationDefinition("run", Map.of()).name());
        assertEquals("", new PropertyDefinition("power", ValueSchema.of(ValueType.BOOLEAN), PropertyAccess.READ_WRITE).description());
        var schema = new ValueSchema(ValueType.DECIMAL, null, null, null, null, "℃");
        var property = new PropertyDefinition("temperature", schema, PropertyAccess.READ_ONLY, "温度", "当前测量温度");
        assertEquals("温度", property.name());
        assertEquals("℃", property.schema().unit());
        assertThrows(IllegalArgumentException.class, () -> new EventDefinition("alarm", Map.of(), "x".repeat(101), ""));
        assertThrows(IllegalArgumentException.class, () -> new ValueSchema(ValueType.BOOLEAN, null, null, null, null, "℃"));
    }
}
