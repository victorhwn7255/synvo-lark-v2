package synvo.integration.azurebilling;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import synvo.billing.BillingException;
import synvo.configuration.BillingProperties;

import static org.junit.jupiter.api.Assertions.*;

class CertificateIdentityTests {
    @Test void certificatePairIsValidatedWithoutTokenAcquisitionOrFallback() {
        Path first = SyntheticBillingCertificate.create(); Path second = SyntheticBillingCertificate.create();
        try {
            assertDoesNotThrow(() -> new CertificateIdentity(properties(first.resolve("key.pem"), first.resolve("cert.pem"))));
            var mismatch = assertThrows(BillingException.class, () -> new CertificateIdentity(properties(first.resolve("key.pem"), second.resolve("cert.pem"))));
            assertEquals("CONFIGURATION", mismatch.getMessage()); assertNull(mismatch.getCause());
            assertThrows(BillingException.class, () -> new CertificateIdentity(properties(first.resolve("missing.pem"), first.resolve("cert.pem"))));
            assertThrows(BillingException.class, () -> new CertificateIdentity(properties(first.resolve("key.pem"), first.resolve("cert.pem")),
                    java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofDays(3))));
        } finally { SyntheticBillingCertificate.remove(first); SyntheticBillingCertificate.remove(second); }
    }
    private static BillingProperties properties(Path key, Path certificate) {
        return new BillingProperties(true, "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
                "test-account", "test-profile", key.toString(), certificate.toString());
    }
}
