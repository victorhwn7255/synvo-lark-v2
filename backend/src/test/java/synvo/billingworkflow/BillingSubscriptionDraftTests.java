package synvo.billingworkflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Two explicit local qualification steps. Never initiates a model call or accesses credentials. */
class BillingSubscriptionDraftTests {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000043");
    @Test @EnabledIfSystemProperty(named = "billing.synthetic.export", matches = "true")
    void exportOnlySyntheticFilesForExistingRunnerQualification() throws Exception {
        var fixture = new BillingAnalysisPackageTests(); var snapshot = fixture.snapshot();
        var root = Files.createDirectories(Path.of("target/billing-subscription-fixture")).toRealPath();
        var packages = new BillingAnalysisPackage(fixture.source(), root, Clock.fixed(snapshot.createdAt().plusSeconds(60), ZoneOffset.UTC), new ObjectMapper());
        packages.prepare("owner", snapshot.id(), ID, ID);
        var work = new BillingWorkflowStore.Work(ID,"owner","revision","synthetic",BillingWorkflowStore.Kind.GENERATION,
                snapshot.range(),null,snapshot.id(),null,null,null,BillingWorkflowStore.State.ANALYZING,null,null,snapshot.createdAt(),snapshot.expiresAt());
        Files.writeString(root.resolve("prompt.txt"), BillingWorkflowFacade.prompt(work, ID, snapshot.id()));
    }
    @Test @EnabledIfSystemProperty(named = "billing.synthetic.validate", matches = "true")
    void realDraftPassesProductionValidatorAndPdfRenderer() throws Exception {
        var fixture = new BillingAnalysisPackageTests(); var snapshot = fixture.snapshot(); var mapper = new ObjectMapper();
        var root = Path.of("target/billing-subscription-fixture").toRealPath();
        var facts = new BillingAnalysisPackage(fixture.source(), root, Clock.fixed(snapshot.createdAt().plusSeconds(60), ZoneOffset.UTC), mapper)
                .prepare("owner", snapshot.id(), ID, UUID.randomUUID()).facts();
        var raw = Files.readString(root.resolve("draft.json"));
        var draft = new BillingReportAnalysis(mapper).validate(raw, ID, snapshot, facts);
        assertFalse(draft.costDrivers().isEmpty());
        assertFalse(draft.optimizationPriorities().isEmpty());
        Files.write(root.resolve("validated-report.pdf"), new BillingReportPdf().render(ID, snapshot, facts, draft));
    }
}
