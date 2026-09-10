package synvo.configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import synvo.billing.BillingException;

@ConfigurationProperties("synvo.billing")
public record BillingProperties(boolean enabled, String tenantId, String clientId, String accountId,
        String profileId, String privateKeyPath, String publicCertPath) {
    public void validate() {
        if (!enabled) return;
        String uuid = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
        try {
            if (tenantId == null || !tenantId.matches(uuid) || clientId == null || !clientId.matches(uuid)
                    || accountId == null || !accountId.matches(uuid + ":" + uuid + "_[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || profileId == null || !profileId.matches("[A-Za-z0-9-]{4,64}")
                    || privateKeyPath == null || !Path.of(privateKeyPath).isAbsolute()
                    || publicCertPath == null || !Path.of(publicCertPath).isAbsolute()) {
                throw new BillingException(BillingException.Reason.CONFIGURATION);
            }
        } catch (IllegalArgumentException exception) { throw new BillingException(BillingException.Reason.CONFIGURATION); }
    }

    public String revision() {
        if (!enabled) return "disabled";
        validate();
        try {
            String identity = (tenantId + "/" + accountId + "/" + profileId + "/USD/actual/v1").toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    @Override public String toString() { return "BillingProperties[enabled=" + enabled + ", configuration=redacted]"; }
}
