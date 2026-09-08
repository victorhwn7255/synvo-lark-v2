package synvo.integration.azurebilling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** Disposable test-only identity; never points at a developer's real certificate. */
public final class SyntheticBillingCertificate {
    private SyntheticBillingCertificate() { }
    public static Path create() {
        try {
            Path directory = Files.createTempDirectory("synvo-synthetic-billing-");
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            var process = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
                    "-keyout", directory.resolve("key.pem").toString(), "-out", directory.resolve("cert.pem").toString(), "-subj", "/CN=synthetic-billing-test")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Synthetic certificate generation timed out"); }
            if (process.exitValue() != 0) throw new IllegalStateException("Synthetic certificate generation failed");
            Files.setPosixFilePermissions(directory.resolve("key.pem"), PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(directory.resolve("cert.pem"), PosixFilePermissions.fromString("rw-------"));
            return directory;
        } catch (Exception exception) { throw new IllegalStateException("Synthetic certificate setup failed"); }
    }
    public static void remove(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("key.pem")); Files.deleteIfExists(directory.resolve("cert.pem")); Files.deleteIfExists(directory);
        } catch (java.io.IOException exception) { throw new IllegalStateException("Synthetic certificate cleanup failed"); }
    }
}
