package com.limelight.account;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureSessionStore {
    private static final String PREFS = "space_connect_secure_session";
    private static final String VALUE_KEY = "session";
    private static final String KEY_ALIAS = "space_connect_launcher_session_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Gson gson = new Gson();

    boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    @TargetApi(Build.VERSION_CODES.M)
    void save(
            Context context,
            SpaceConnectApiClient.AuthResponse auth,
            String deviceId) throws Exception {
        if (!isSupported()) throw new IllegalStateException("Android 6.0 ou superior é obrigatório");

        Session session = new Session();
        Session previous = load(context);
        session.accessToken = auth.accessToken;
        session.refreshToken = auth.refreshToken;
        session.email = auth.user != null ? auth.user.email : previous != null ? previous.email : null;
        session.deviceId = deviceId;
        session.accessExpiresAt = System.currentTimeMillis()
                + Math.max(60, auth.accessTokenExpiresIn) * 1000L;

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(context.getPackageName().getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(gson.toJson(session).getBytes(StandardCharsets.UTF_8));

        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs(context).edit().putString(VALUE_KEY, value).apply();
    }

    @TargetApi(Build.VERSION_CODES.M)
    Session load(Context context) {
        if (!isSupported()) return null;
        String value = prefs(context).getString(VALUE_KEY, null);
        if (value == null || value.isEmpty()) return null;

        try {
            String[] parts = value.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid session payload");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getPackageName().getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(encrypted);
            Session session = gson.fromJson(new String(plain, StandardCharsets.UTF_8), Session.class);
            if (session == null
                    || session.accessToken == null
                    || session.refreshToken == null
                    || session.deviceId == null) {
                throw new IllegalArgumentException("Incomplete session");
            }
            return session;
        } catch (Exception e) {
            clear(context);
            return null;
        }
    }

    void clear(Context context) {
        prefs(context).edit().remove(VALUE_KEY).apply();
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGenerator.generateKey();
    }

    static final class Session {
        String accessToken;
        String refreshToken;
        String email;
        String deviceId;
        long accessExpiresAt;
    }
}
