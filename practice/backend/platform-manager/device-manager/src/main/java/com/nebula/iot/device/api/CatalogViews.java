package com.nebula.iot.device.api;

import java.util.*;
import com.nebula.iot.core.model.*;
public final class CatalogViews {
    private CatalogViews() {}
    public record ModelSpec(Map<String,PropertyDefinition> properties,Map<String,EventDefinition> events,Map<String,OperationDefinition> operations) {
        public ModelSpec {
            properties=Map.copyOf(Objects.requireNonNull(properties,"properties"));
            events=Map.copyOf(Objects.requireNonNull(events,"events"));
            operations=Map.copyOf(Objects.requireNonNull(operations,"operations"));
            if(properties.size()>100||events.size()>100||operations.size()>100)throw new IllegalArgumentException("Too many model definitions");
            properties.forEach((id,p)->matching(id,p.id()));events.forEach((id,e)->matching(id,e.id()));operations.forEach((id,o)->matching(id,o.id()));
            events.values().forEach(e->fields(e.fields()));operations.values().forEach(o->fields(o.inputs()));
        }
        private static void matching(String key,String id){if(!key.equals(id)||!id.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid or mismatched model identifier");}
        private static void fields(Map<String,FieldDefinition> values){if(values.size()>100)throw new IllegalArgumentException("Too many fields");values.forEach((id,v)->matching(id,v.id()));}
        public static ModelSpec empty(){return new ModelSpec(Map.of(),Map.of(),Map.of());}
    }
    public record Product(String id,String tenantId,String code,String name,String description,boolean enabled,String latestVersion,long revision,ModelSpec draft,ProductConfiguration configuration) {}
    public record ModelVersion(String version,ModelSpec model,java.time.OffsetDateTime publishedAt,long deviceCount) {}
    public record Device(String id,String tenantId,String productId,String productName,String code,String name,String modelVersion,String lifecycle,long credentialVersion,long revision) {}
    public record DeviceDetail(Device device,Product product,ModelVersion model) {}
    public record PropertySnapshot(String id,String type,String access,String value,String updatedAt,boolean available) {}
    public record EventSnapshot(String id,String status,String receivedAt,String payload) {}
    public record DeviceRuntimeSnapshot(List<PropertySnapshot> properties,List<EventSnapshot> events) {}
    public record Credential(Device device,String clientId,String username,String secret) {
        @Override public String toString(){return "Credential[redacted]";}
    }
    public record Page<T>(List<T> items,long total,int page,int size) {}
}
