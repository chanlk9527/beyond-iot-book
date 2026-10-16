package com.nebula.iot.core.device;

import com.nebula.iot.core.model.DeviceModel;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Runtime lookup contract; registration CRUD and database storage belong to management modules. */
public interface DeviceRegistry {
    CompletionStage<Optional<DeviceOperator>> findDevice(DeviceKey key);

    /** Look up exactly the requested product/model version; do not silently use the latest model. */
    CompletionStage<Optional<DeviceModel>> findModel(ProductKey product, String modelVersion);
}
