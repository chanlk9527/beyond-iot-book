package com.nebula.iot.core.message;

import java.util.Objects;
import java.util.Optional;

/** 应用层处理结果，和MQTT确认、设备执行阶段分别表达。 */
public record ReplyResult(String code, Optional<String> message) {
    public ReplyResult {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("结果码格式无效");
        Objects.requireNonNull(message, "message");
        message.ifPresent(value -> {
            if (value.isBlank() || value.codePointCount(0, value.length()) > 512) throw new IllegalArgumentException("结果说明长度无效");
        });
    }
    public boolean isSuccess() { return "SUCCESS".equals(code); }
    public static ReplyResult success() { return new ReplyResult("SUCCESS", Optional.empty()); }
}
