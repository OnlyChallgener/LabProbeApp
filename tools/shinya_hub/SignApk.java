import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Password is read from environment; never command-line arguments or output. */
public final class SignApk {
    public static void main(String[] args) throws Exception {
        char[] password = System.getenv("SHINYA_HUB_KEY_PASSWORD").toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[0])) { keys.load(in, password); }
        List<X509Certificate> certificates = new ArrayList<>();
        for (java.security.cert.Certificate cert : keys.getCertificateChain("shinya-hub"))
            certificates.add((X509Certificate) cert);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("shinya-hub",
            (PrivateKey) keys.getKey("shinya-hub", password), certificates).build();
        Arrays.fill(password, '\0');
        new ApkSigner.Builder(Arrays.asList(signer))
            .setInputApk(new File(args[1])).setOutputApk(new File(args[2]))
            .setMinSdkVersion(21).setV1SigningEnabled(true).setV2SigningEnabled(true)
            .setV3SigningEnabled(true).build().sign();
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[2])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException(result.getErrors().toString());
        System.out.println("APK signature verified: v1=" + result.isVerifiedUsingV1Scheme()
            + " v2=" + result.isVerifiedUsingV2Scheme() + " v3=" + result.isVerifiedUsingV3Scheme());
    }
}
