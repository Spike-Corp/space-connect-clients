package com.limelight.account;

import com.limelight.nvstream.http.LimelightCryptoProvider;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 24)
public class StreamClientIdentityTest {
    @Test public void proofUsesExistingPairingKeyAndExactDomainPrefix() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=Ephemeral test client");
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(subject, BigInteger.ONE,
                        new Date(0), new Date(System.currentTimeMillis() + 600_000), subject, pair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pem = new JcaPEMWriter(writer)) { pem.writeObject(certificate); }
        byte[] publicPem = writer.toString().getBytes(StandardCharsets.UTF_8);
        LimelightCryptoProvider provider = new LimelightCryptoProvider() {
            @Override public X509Certificate getClientCertificate() { return certificate; }
            @Override public PrivateKey getClientPrivateKey() { return pair.getPrivate(); }
            @Override public byte[] getPemEncodedClientCertificate() { return publicPem; }
            @Override public String encodeBase64String(byte[] data) {
                return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            }
        };
        StreamClientIdentity identity = new StreamClientIdentity(provider);
        byte[] proof = android.util.Base64.decode(identity.sign("opaque-challenge"), android.util.Base64.NO_WRAP);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate);
        verifier.update("SpaceConnect stream identity v1\nopaque-challenge".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(proof));
        verifier.update("opaque-challenge".getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(proof));
        assertEquals(writer.toString(), identity.certificatePem());
    }
}
