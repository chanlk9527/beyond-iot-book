package com.nebula.iot.device.domain;

import com.nebula.iot.device.api.FirmwareViews.*;
import java.util.List;

public interface FirmwareRepository {
    List<Channel> channels(String productId);
    void insertChannel(String productId, String scope, Channel channel);
    List<Version> versions(String productId, int channel, int limit, long offset);
    long count(String productId, int channel);
    Version version(String productId, int channel, String id);
    void insertVersion(String productId, Version version);
    int status(String productId, String id, boolean enabled, long revision);
    FirmwareFile file(String productId, int channel, String id);
    void insertFile(String productId, int channel, FirmwareFile file);
}
