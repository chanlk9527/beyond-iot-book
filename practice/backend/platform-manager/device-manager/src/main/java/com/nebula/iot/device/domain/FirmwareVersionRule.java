package com.nebula.iot.device.domain;

import java.util.OptionalInt;

/** Opaque vendor versions deliberately have no ordering. */
public enum FirmwareVersionRule {
    TUYA, OPAQUE;

    public void validate(String version) {
        if (version == null || version.isBlank() || version.length() > 100 || !version.equals(version.strip())
                || version.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("固件版本须为1～100字符，不能包含首尾空白或控制字符");
        if (this == TUYA && !version.matches("(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)"))
            throw new IllegalArgumentException("标准版本格式为x.y.z，每段0～99，不使用前导零");
    }

    public OptionalInt compare(String left, String right) {
        validate(left); validate(right);
        if (left.equals(right)) return OptionalInt.of(0);
        if (this == OPAQUE) return OptionalInt.empty();
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (result != 0) return OptionalInt.of(result);
        }
        return OptionalInt.of(0);
    }
}
