package com.nebula.iot.video.infrastructure;
import com.nebula.iot.video.application.LiveSessionService.Channel;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("nebula.video")
public class LiveProperties {
    private String provider = "disabled", region;
    private Duration sessionTtl = Duration.ofMinutes(3);
    private int maximumSessions = 100;
    private List<Channel> channels = new ArrayList<>();
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { region = v; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration v) { sessionTtl = v; }
    public int getMaximumSessions() { return maximumSessions; }
    public void setMaximumSessions(int v) { maximumSessions = v; }
    public List<Channel> getChannels() { return channels; }
    public void setChannels(List<Channel> v) { channels = v; }
}
