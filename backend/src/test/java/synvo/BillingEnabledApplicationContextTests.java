package synvo;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import synvo.billing.BillingInsightsFacade;
import synvo.billing.BillingSource;
import synvo.billing.BillingData;
import synvo.integration.azurebilling.AzureBillingSource;
import synvo.integration.azurebilling.SyntheticBillingCertificate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BillingEnabledApplicationContextTests {
    private static final Path CERTIFICATE = SyntheticBillingCertificate.create();
    @MockitoSpyBean private BillingSource source;
    @Autowired private BillingInsightsFacade facade;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("synvo.billing.enabled", () -> "true");
        registry.add("synvo.billing.tenant-id", () -> "00000000-0000-4000-8000-000000000001");
        registry.add("synvo.billing.client-id", () -> "00000000-0000-4000-8000-000000000002");
        registry.add("synvo.billing.account-id", () -> "00000000-0000-4000-8000-000000000001:00000000-0000-4000-8000-000000000002_2019-05-31");
        registry.add("synvo.billing.profile-id", () -> "test-profile");
        registry.add("synvo.billing.private-key-path", () -> CERTIFICATE.resolve("key.pem").toString());
        registry.add("synvo.billing.public-cert-path", () -> CERTIFICATE.resolve("cert.pem").toString());
        registry.add("synvo.lark.pilot-open-id", () -> "billing-test-owner");
    }

    @Test void enabledProductionWiringUsesCertificateAdapterWithoutNetworkOnStartup() {
        assertInstanceOf(AzureBillingSource.class, source);
        assertTrue(facade.available("billing-test-owner"));
    }

    @Test void configuredProductionFacadePersistsSyntheticEvidenceThroughItsRealWorker() throws Exception {
        var fixture = new BillingSource.Session() {
            @Override public BillingData.Partition month(java.time.YearMonth month, java.util.function.Consumer<BillingData.CostRow> sink) {
                String dataset = "month:" + month;
                sink.accept(new BillingData.CostRow(dataset, 0, 1, month.atDay(1), "USD", java.math.BigDecimal.ONE,
                        BillingData.Bucket.PROFILE_ADJUSTMENT, "", "Unallocated", "RoundingAdjustment", "", java.util.Map.of()));
                return new BillingData.Partition(dataset, java.time.Instant.now(), "synthetic", java.util.Map.of(),
                        java.util.List.of(new BillingData.Part(0, "b".repeat(64), 100, 1)), java.util.Set.of(), java.util.Set.of(), true, true);
            }
            @Override public java.util.List<BillingData.Invoice> invoices(java.util.Set<String> ids, java.time.LocalDate first, java.time.LocalDate last) {
                return java.util.List.of();
            }
            @Override public BillingData.Partition invoice(String id, java.util.function.Consumer<BillingData.CostRow> sink) {
                throw new AssertionError("No invoice requested by this fixture");
            }
        };
        org.mockito.Mockito.doReturn(fixture).when(source).open();
        var month = java.time.YearMonth.now(java.time.ZoneOffset.UTC).minusMonths(1);
        var snapshot = facade.request("billing-test-owner", BillingData.SOURCE_ID,
                new BillingData.Range(month, month, false), java.util.UUID.randomUUID().toString());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (snapshot.state() == BillingData.State.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(20); snapshot = facade.inspect("billing-test-owner", snapshot.id());
        }
        assertEquals(BillingData.State.READY_WITH_LIMITATIONS, snapshot.state());
        assertEquals(1, facade.evidence("billing-test-owner", snapshot.id(), 0, 100).size());
        org.mockito.Mockito.verify(source).open();
    }
    @AfterAll static void cleanup() { SyntheticBillingCertificate.remove(CERTIFICATE); }
}
