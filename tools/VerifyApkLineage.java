import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.HexFormat;

/** Checks the actual signed APK and its upgrade lineage; never prints certificate metadata. */
class VerifyApkLineage {
    public static void main(String[] args) {
        try {
            verify(args);
            System.out.println("APK release signature and upgrade lineage verified");
        } catch (Exception error) {
            System.err.println("APK release signature or upgrade lineage verification failed");
            System.exit(1);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid release signing contract");
    }

    private static void verify(String[] args) throws Exception {
        require(args.length == 3 || args.length == 4);
        var result = new ApkVerifier.Builder(new File(args[0]))
            .setMinCheckedPlatformVersion(34).build().verify();
        require(result.isVerified() && !result.containsErrors());
        require(result.isVerifiedUsingV3Scheme() || result.isVerifiedUsingV31Scheme());
        require(result.getSignerCertificates().size() == 1);
        X509Certificate current = result.getSignerCertificates().get(0);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(current.getEncoded()));
        require(digest.equals(args[1]));
        current.checkValidity();
        try (var publicCertificate = Files.newInputStream(Path.of(args[2]).resolveSibling("certificate.pem"))) {
            require(current.equals(CertificateFactory.getInstance("X.509").generateCertificate(publicCertificate)));
        }

        var expected = SigningCertificateLineage.readFromBytes(
            Base64.getMimeDecoder().decode(Files.readString(Path.of(args[2]))));
        var lineage = result.getSigningCertificateLineage();
        require(lineage != null && lineage.containsSameHistory(expected));
        require(lineage.isCertificateLatestInLineage(current));
        for (var certificate : lineage.getCertificatesInLineage()) {
            if (certificate.equals(current)) continue;
            var capabilities = lineage.getSignerCapabilities(certificate);
            require(capabilities.hasInstalledData() && !capabilities.hasRollback());
            require(!capabilities.hasSharedUid() && !capabilities.hasPermission() && !capabilities.hasAuth());
        }
        if (args.length == 4) {
            var previous = new ApkVerifier.Builder(new File(args[3])).build().verify();
            require(previous.isVerified() && previous.getSignerCertificates().size() == 1);
            var previousCertificate = previous.getSignerCertificates().get(0);
            require(lineage.isCertificateInLineage(previousCertificate));
            require(lineage.getSignerCapabilities(previousCertificate).hasInstalledData());
        }
    }
}
