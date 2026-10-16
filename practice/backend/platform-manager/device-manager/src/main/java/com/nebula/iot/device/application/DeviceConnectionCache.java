package com.nebula.iot.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.domain.CatalogRepository;
import com.nebula.iot.device.domain.CatalogRows.ConnectionRow;
import com.nebula.iot.device.infrastructure.cache.ThreeLevelCache;

/** Device-specific adapter over the shared three-level cache infrastructure. */
public final class DeviceConnectionCache {
    private final CatalogRepository source;
    private final ThreeLevelCache<String, ConnectionRow> cache;

    public DeviceConnectionCache(CatalogRepository source, ThreeLevelCache<String, ConnectionRow> cache) {
        this.source = source;
        this.cache = cache;
    }

    public ConnectionRow get(String deviceId) { return cache.get(deviceId, () -> source.connection(deviceId)); }
    public void invalidate(String deviceId) { cache.evict(deviceId); }
    public void invalidateAll() { cache.clear(); }
    public ThreeLevelCache.CacheStats stats() { return cache.stats(); }
}
