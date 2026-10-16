package com.nebula.iot.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.device.api.CatalogViews.ModelVersion;
import com.nebula.iot.device.domain.CatalogRepository;
import com.nebula.iot.device.infrastructure.cache.ThreeLevelCache;

/** Public model-focused adapter; callers receive parsed, immutable model data. */
public final class ThingModelCache {
    private final CatalogRepository source;
    private final ObjectMapper json;
    private final ThreeLevelCache<ModelKey, ModelVersion> cache;

    public ThingModelCache(CatalogRepository source, ObjectMapper json, ThreeLevelCache<ModelKey, ModelVersion> cache) {
        this.source = source; this.json = json; this.cache = cache;
    }

    public ModelVersion get(String productId, String version) {
        return get(new ModelKey(productId, version));
    }

    public ModelVersion get(ModelKey key) {
        return cache.get(key, () -> {
            var row = source.model(key.productId(), key.version());
            if (row == null) return null;
            try { return new ModelVersion(row.version(), json.readValue(row.modelJson(), com.nebula.iot.device.api.CatalogViews.ModelSpec.class), row.publishedAt(), row.deviceCount()); }
            catch (Exception invalid) { throw new IllegalStateException("Stored model is invalid", invalid); }
        });
    }

    public void invalidate(String productId, String version) { cache.evict(new ModelKey(productId, version)); }
    public void invalidateAll() { cache.clear(); }
    public ThreeLevelCache.CacheStats stats() { return cache.stats(); }
    public record ModelKey(String productId, String version) { }
}
