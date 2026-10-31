package com.limelight.account;

import com.limelight.nvstream.http.LimelightCryptoProvider;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;

public final class StreamClientIdentity {
    private final LimelightCryptoProvider provider;

    public StreamClientIdentity(LimelightCryptoProvider provider) { this.provider = provider; }

    public String certificatePem() {
        return new String(provider.getPemEncodedClientCertificate(), StandardCharsets.UTF_8);
    }

    public String sign(String challenge) throws GeneralSecurityException {
        if (challenge == null || challenge.isEmpty() || challenge.length() > 8192) {
            throw new GeneralSecurityException("Invalid stream identity challenge");
        }
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(provider.getClientPrivateKey());
        signature.update(("SpaceConnect stream identity v1\n" + challenge).getBytes(StandardCharsets.UTF_8));
        return provider.encodeBase64String(signature.sign());
    }
}
