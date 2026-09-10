package synvo.configuration;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import synvo.billing.BillingInsightsFacade;
import synvo.billing.BillingStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BillingConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BillingConfiguration.class)
            .withBean(BillingStore.class, () -> mock(BillingStore.class))
            .withBean(synvo.billing.DailySpendingStore.class, () -> mock(synvo.billing.DailySpendingStore.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(LarkProperties.class, () -> new LarkProperties(false, null, null, "websocket", "owner", null, null,
                    Duration.ofMinutes(5), Duration.ofDays(30)));

    @Test void disabledContextDoesNotNeedIdentityOrCertificateFiles() {
        runner.withPropertyValues("synvo.billing.enabled=false", "synvo.billing.private-key-path=/does-not-exist")
                .run(context -> { assertNull(context.getStartupFailure()); assertFalse(context.getBean(BillingInsightsFacade.class).available("owner")); });
    }

    @Test void invalidEnabledConfigurationFailsBeforeAnyProviderAccess() {
        runner.withPropertyValues("synvo.billing.enabled=true").run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("synvo.billing.enabled=true", "synvo.billing.tenant-id=not-a-uuid",
                "synvo.billing.profile-id=../outside").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
