package synvo.integration.azurebilling;

import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.identity.ClientCertificateCredential;
import com.azure.identity.ClientCertificateCredentialBuilder;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import synvo.billing.BillingException;
import synvo.configuration.BillingProperties;

final class CertificateIdentity {
    private final ClientCertificateCredential credential;

    CertificateIdentity(BillingProperties properties) {
        this(properties, Clock.systemUTC());
    }

    CertificateIdentity(BillingProperties properties, Clock clock) {
        byte[] keyBytes = null;
        try {
            Path key = Path.of(properties.privateKeyPath()); Path cert = Path.of(properties.publicCertPath());
            checkFile(key); checkFile(cert);
            keyBytes = Files.readAllBytes(key);
            byte[] certificateBytes = Files.readAllBytes(cert);
            var certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificateBytes));
            certificate.checkValidity(java.util.Date.from(clock.instant()));
            if (!"RSA".equals(certificate.getPublicKey().getAlgorithm())) throw invalid();
            String pem = new String(keyBytes, StandardCharsets.US_ASCII);
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) throw invalid();
            byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            try {
                var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                var signature = Signature.getInstance("SHA256withRSA");
                byte[] challenge = "synvo-certificate-pair-check".getBytes(StandardCharsets.US_ASCII);
                signature.initSign(privateKey); signature.update(challenge); byte[] proof = signature.sign();
                signature.initVerify(certificate.getPublicKey()); signature.update(challenge);
                if (!signature.verify(proof)) throw invalid();
            } finally { Arrays.fill(der, (byte) 0); }
            byte[] combined = new byte[keyBytes.length + certificateBytes.length + 1];
            System.arraycopy(keyBytes, 0, combined, 0, keyBytes.length); combined[keyBytes.length] = '\n';
            System.arraycopy(certificateBytes, 0, combined, keyBytes.length + 1, certificateBytes.length);
            credential = new ClientCertificateCredentialBuilder().tenantId(properties.tenantId()).clientId(properties.clientId())
                    .authorityHost("https://login.microsoftonline.com/").disableInstanceDiscovery()
                    .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE))
                    .pemCertificate(new ByteArrayInputStream(combined)).build();
        } catch (Exception exception) { throw invalid(); }
        finally { if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0); }
    }

    String token() {
        try {
            var token = credential.getToken(new TokenRequestContext().addScopes("https://management.azure.com/.default")).block(Duration.ofSeconds(60));
            if (token == null) throw invalid();
            return token.getToken();
        } catch (RuntimeException exception) { throw new BillingException(BillingException.Reason.AUTHENTICATION); }
    }

    private static void checkFile(Path file) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768) throw invalid();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) throw invalid();
    }
    private static BillingException invalid() { return new BillingException(BillingException.Reason.CONFIGURATION); }
}
