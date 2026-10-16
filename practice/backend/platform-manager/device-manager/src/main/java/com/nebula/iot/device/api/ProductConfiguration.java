package com.nebula.iot.device.api;

import java.net.URI;
import java.util.Set;

/** Product catalogue and access planning metadata, independent of model versions. */
public record ProductConfiguration(String category, String manufacturer, String modelNumber,
        String deviceType, String networkWay, String transportProtocol, String messageProtocol,
        String dataFormat, String firmwareVersion, String supportUrl, String installationNotes) {
    public ProductConfiguration {
        category = text(category, 100); manufacturer = text(manufacturer, 100);
        modelNumber = text(modelNumber, 100); firmwareVersion = text(firmwareVersion, 100);
        installationNotes = text(installationNotes, 1000); supportUrl = text(supportUrl, 1000);
        deviceType = option(deviceType, "DIRECT", Set.of("DIRECT", "GATEWAY", "CHILD"));
        networkWay = option(networkWay, "OTHER", Set.of("ETHERNET", "WIFI", "CELLULAR", "NB_IOT", "LORA", "OTHER"));
        transportProtocol = option(transportProtocol, "MQTT", Set.of("MQTT"));
        messageProtocol = option(messageProtocol, "nebula-mqtt-v1", Set.of("nebula-mqtt-v1"));
        dataFormat = option(dataFormat, "JSON", Set.of("JSON"));
        if (!supportUrl.isEmpty()) {
            URI uri = URI.create(supportUrl);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException("Support URL must be an HTTP(S) URL");
        }
    }
    private static String text(String v, int max) {
        if (v == null) return "";
        if (v.length() > max) throw new IllegalArgumentException("Product configuration text is too long");
        return v.strip();
    }
    private static String option(String v, String fallback, Set<String> allowed) {
        if (v == null) return fallback;
        if (!allowed.contains(v)) throw new IllegalArgumentException("Unsupported product configuration option");
        return v;
    }
    public static ProductConfiguration defaults() {
        return new ProductConfiguration(null,null,null,null,null,null,null,null,null,null,null);
    }
}
