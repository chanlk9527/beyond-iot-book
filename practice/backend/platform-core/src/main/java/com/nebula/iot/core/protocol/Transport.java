package com.nebula.iot.core.protocol;

import com.nebula.iot.core.internal.Checks;
import java.util.Locale;

/** Extensible transport identifier. New plugins do not require editing a central enum. */
public record Transport(String id) {
    public static final Transport MQTT = new Transport("mqtt");
    public static final Transport HTTP = new Transport("http");
    public static final Transport TCP = new Transport("tcp");
    public static final Transport UDP = new Transport("udp");
    public static final Transport COAP = new Transport("coap");
    public static final Transport WEBSOCKET = new Transport("websocket");

    public Transport {
        id = Checks.nonBlank(id, "transportId").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid transport identifier");
        }
    }
}
