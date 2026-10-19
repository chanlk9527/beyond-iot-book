package com.nebula.iot.device.infrastructure;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nebula.iot.core.command.*;
import com.nebula.iot.core.model.Value;

/** Internal schema v1: only the two management command types currently exposed by the API. */
public final class CommandJson {
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="kind") private interface BodyType {}
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="type") private interface ValueType {}
    public CommandJson() {
        json.addMixIn(DownlinkBody.class,BodyType.class);json.addMixIn(Value.class,ValueType.class);
        json.registerSubtypes(new NamedType(DownlinkBody.PropertyWrite.class,"property-write"),new NamedType(DownlinkBody.OperationInvoke.class,"operation"),
                new NamedType(Value.BooleanValue.class,"boolean"),new NamedType(Value.IntegerValue.class,"integer"),new NamedType(Value.DecimalValue.class,"decimal"),new NamedType(Value.TextValue.class,"text"));
    }
    public String encode(DeviceDownlink request) {
        if(!(request.body() instanceof DownlinkBody.PropertyWrite || request.body() instanceof DownlinkBody.OperationInvoke))throw new IllegalArgumentException("Unsupported durable command");
        try {var root=json.createObjectNode();root.put("schemaVersion",1);root.set("command",json.valueToTree(request));return json.writeValueAsString(root);}
        catch(Exception error){throw new IllegalArgumentException("Cannot encode command",error);}
    }
    public DeviceDownlink decode(String value) {
        try {var root=json.readTree(value);if(root.path("schemaVersion").asInt()!=1)throw new IllegalArgumentException("Unsupported command schema");return json.treeToValue(root.required("command"),DeviceDownlink.class);}
        catch(Exception error){throw new IllegalArgumentException("Cannot decode command",error);}
    }
}
