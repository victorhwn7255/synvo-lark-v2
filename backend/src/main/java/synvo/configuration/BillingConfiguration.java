package synvo.configuration;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import synvo.billing.BillingException;
import synvo.billing.BillingInsightsFacade;
import synvo.billing.BillingSource;
import synvo.billing.BillingStore;
import synvo.integration.azurebilling.AzureBillingSource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
@EnableScheduling
class BillingConfiguration {
    @Bean
    BillingSource billingSource(BillingProperties properties, ObjectMapper mapper) {
        properties.validate();
        if (!properties.enabled()) return () -> { throw new BillingException(BillingException.Reason.DISABLED); };
        return new AzureBillingSource(properties, mapper);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    BillingInsightsFacade billingInsightsFacade(BillingStore store, BillingSource source, BillingProperties properties, LarkProperties lark) {
        if (properties.enabled() && (lark.pilotOpenId() == null || lark.pilotOpenId().isBlank())) {
            throw new BillingException(BillingException.Reason.CONFIGURATION);
        }
        return new BillingInsightsFacade(store, source, lark.pilotOpenId(), properties.revision(), properties.enabled(), Clock.systemUTC());
    }

    @Bean
    BillingCleanup billingCleanup(BillingInsightsFacade facade) { return new BillingCleanup(facade); }

    @Bean(destroyMethod = "close")
    synvo.billing.DailySpendingFacade dailySpendingFacade(synvo.billing.DailySpendingStore store, BillingSource source,
            BillingProperties properties, LarkProperties lark, BillingInsightsFacade recovery,
            @org.springframework.beans.factory.annotation.Value("${synvo.billing.daily-history-start:2025-09}") String historyStart) {
        // Initialization of the existing facade acquires the process lease and recovers both ingestion paths first.
        return new synvo.billing.DailySpendingFacade(store, source, lark.pilotOpenId(), properties.revision(),
                properties.enabled(), java.time.YearMonth.parse(historyStart), Clock.systemUTC());
    }

    static final class BillingCleanup {
        private final BillingInsightsFacade facade;
        BillingCleanup(BillingInsightsFacade facade) { this.facade = facade; }
        @Scheduled(initialDelayString = "PT24H", fixedDelayString = "PT24H")
        public void cleanup() { facade.cleanup(); }
    }
}
