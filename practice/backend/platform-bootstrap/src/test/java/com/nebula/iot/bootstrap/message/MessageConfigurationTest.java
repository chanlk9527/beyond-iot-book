package com.nebula.iot.bootstrap.message;

import com.nebula.iot.message.EventBus;
import com.nebula.iot.message.LocalEventBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import static org.assertj.core.api.Assertions.assertThat;

class MessageConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(MessageConfiguration.class);

    @Test void localIsTheDefaultAndNeedsNoBroker() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(EventBus.class);
            assertThat(ctx.getBean(EventBus.class)).isInstanceOf(LocalEventBus.class);
        });
    }

    @Test void invalidModeFailsInsteadOfFallingBack() {
        context.withPropertyValues("nebula.eventbus.mode=typo").run(ctx ->
                assertThat(ctx).hasFailed());
    }

    @Test void kafkaRequiresAnExplicitNodeIdentity() {
        context.withPropertyValues("nebula.eventbus.mode=kafka").run(ctx ->
                assertThat(ctx).hasFailed());
    }
}
