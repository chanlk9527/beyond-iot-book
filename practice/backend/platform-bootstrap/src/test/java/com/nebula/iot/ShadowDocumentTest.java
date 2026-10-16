package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.domain.ShadowDocument;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowDocumentTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-16T00:00:00Z");
    @Test void numericRepresentationsConvergeAndNewTargetsRemainPending() throws Exception {
        var doc = ShadowDocument.empty("v1")
                .withReport(Map.of("temperature",json.readTree("20.0")),"m1",now,now)
                .withDesired(Map.of("temperature",json.readTree("20")),now);
        assertTrue(doc.view().delta().isEmpty());
        assertFalse(doc.withDesired(Map.of("temperature",json.readTree("21")),now).view().delta().isEmpty());
    }
    @Test void equalObservationTimesHaveDeterministicResultsInEitherDeliveryOrder() throws Exception {
        var empty = ShadowDocument.empty("v1");
        var first = Map.of("power",json.readTree("true"));
        var second = Map.of("power",json.readTree("false"));
        var ab = empty.withReport(first,"a",now,now).withReport(second,"b",now,now);
        var ba = empty.withReport(second,"b",now,now).withReport(first,"a",now,now);
        assertEquals(ab.reported(),ba.reported());
        assertFalse(ab.reported().get("power").value().booleanValue());
    }
    @Test void staleDataDoesNotIncreaseVersionOrClearAnUnmatchedTarget() throws Exception {
        var current = ShadowDocument.empty("v1").withDesired(Map.of("power",json.readTree("false")),now)
                .withReport(Map.of("power",json.readTree("true")),"new",now,now);
        var stale = current.withReport(Map.of("power",json.readTree("false")),"old",now.minusSeconds(1),now.plusSeconds(1));
        assertSame(current,stale);
        assertFalse(stale.view().delta().isEmpty());
    }
}

