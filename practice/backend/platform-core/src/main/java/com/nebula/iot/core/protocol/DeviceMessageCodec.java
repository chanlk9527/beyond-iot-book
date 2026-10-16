package com.nebula.iot.core.protocol;

import com.nebula.iot.core.command.DeviceCommand;
import com.nebula.iot.core.command.DeviceDownlink;
import java.util.List;

/** CPU-local mapping contract. Blocking I/O must run outside codec calls. */
public interface DeviceMessageCodec {
    /** Return a non-null list, empty for packets with no business messages. */
    List<DecodedMessage> decode(ProtocolPacket packet, DecodeContext context);

    /** Encode for the selected transport. Addressing details are returned as packet attributes. */
    ProtocolPacket encode(DeviceCommand command, Transport transport);

    /** 通用下行扩展点；旧协议需要显式实现才能支持新增业务。 */
    default ProtocolPacket encode(DeviceDownlink message, Transport transport) {
        throw new UnsupportedOperationException("协议尚未实现通用下行");
    }
}
