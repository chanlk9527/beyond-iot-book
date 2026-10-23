package com.nebula.iot.device.infrastructure;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nebula.firmware")
public class FirmwareSigningProperties {
    private java.time.Duration reportMaximumAge=java.time.Duration.ofHours(24);
    public java.time.Duration getReportMaximumAge() { return reportMaximumAge; }
    public void setReportMaximumAge(java.time.Duration value) { reportMaximumAge=value; }
    private Map<String,String> trustedKeys=new HashMap<>();
    public Map<String,String> getTrustedKeys() { return trustedKeys; }
    public void setTrustedKeys(Map<String,String> value) { trustedKeys=value; }
}
