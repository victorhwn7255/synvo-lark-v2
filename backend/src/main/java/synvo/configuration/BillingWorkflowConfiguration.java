package synvo.configuration;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import synvo.agent.ConversationRunCoordinator;
import synvo.billing.BillingException;
import synvo.billing.BillingInsightsFacade;
import synvo.billingworkflow.BillingAnalysisPackage;
import synvo.billingworkflow.BillingReportAnalysis;
import synvo.billingworkflow.BillingReportPdf;
import synvo.billingworkflow.BillingWorkflowFacade;
import synvo.billingworkflow.BillingWorkflowStore;
import synvo.workspaceagent.WorkspaceAgentFacade;
import synvo.workspaceagent.WorkspaceRegistry;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingWorkflowConfiguration.Properties.class)
class BillingWorkflowConfiguration {
    @ConfigurationProperties("synvo.billing.workflow")
    record Properties(boolean enabled, String workspaceId, String packageRoot) { }

    @Bean(initMethod = "start", destroyMethod = "close")
    BillingWorkflowFacade billingWorkflowFacade(Properties properties, BillingProperties billingProperties,
            LarkProperties lark, WorkspaceRegistry registry, BillingInsightsFacade billing, BillingWorkflowStore store,
            WorkspaceAgentFacade tasks, ConversationRunCoordinator conversations, ObjectMapper mapper) {
        Path root = Path.of(properties.packageRoot() == null ? "/disabled-billing" : properties.packageRoot());
        if (properties.enabled()) {
            if (!billingProperties.enabled() || !root.isAbsolute() || !root.normalize().equals(root)
                    || properties.workspaceId() == null || !registry.require(properties.workspaceId()).workflowManaged()
                    || !registry.require(properties.workspaceId()).writeEnabled()) throw new BillingException(BillingException.Reason.CONFIGURATION);
            try {
                if (!java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !root.toRealPath().equals(root) || !java.nio.file.Files.isWritable(root)) throw new BillingException(BillingException.Reason.CONFIGURATION);
            } catch (java.io.IOException invalid) { throw new BillingException(BillingException.Reason.CONFIGURATION); }
        }
        Clock clock = Clock.systemUTC();
        return new BillingWorkflowFacade(store, billing, new BillingAnalysisPackage(billing, root, clock, mapper),
                new BillingReportAnalysis(mapper), new BillingReportPdf(), tasks, conversations, lark.pilotOpenId(),
                billingProperties.revision(), properties.workspaceId(), properties.enabled(), clock);
    }
    @Bean Cleanup billingWorkflowCleanup(BillingWorkflowFacade facade) { return new Cleanup(facade); }
    @Bean synvo.billingworkflow.DailySpendingView dailySpendingView(synvo.billing.DailySpendingFacade daily,
            @org.springframework.beans.factory.annotation.Value("${synvo.billing.daily-history-start:2025-09}") String historyStart) {
        return new synvo.billingworkflow.DailySpendingView(daily, java.time.YearMonth.parse(historyStart), Clock.systemUTC());
    }
    static final class Cleanup {
        private final BillingWorkflowFacade facade;
        Cleanup(BillingWorkflowFacade facade) { this.facade = facade; }
        @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
        public void cleanup() { facade.cleanup(); }
    }
}
