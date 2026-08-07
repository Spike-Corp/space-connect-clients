package com.limelight.account;

import com.limelight.BuildConfig;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AccountManager {
    private static final String API_BASE_URL = "https://spacecloud.gg/api/launcher/v1/";
    private static final String DEVICE_PREFS = "space_connect_device";
    private static final String DEVICE_ID_KEY = "device_id";

    private static final SpaceConnectApiClient API = new SpaceConnectApiClient(API_BASE_URL);
    private static final SecureSessionStore SESSION_STORE = new SecureSessionStore();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AccountManager() {
    }

    public interface LoginCallback {
        void onSuccess();
        void onTwoFactorRequired(String tempToken);
        void onError(String message);
    }

    public interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private interface AuthenticatedOperation<T> {
        T run(String accessToken) throws Exception;
    }

    public static boolean isSupported() {
        return SESSION_STORE.isSupported();
    }

    public static boolean isLoggedIn(Context context) {
        return SESSION_STORE.load(context.getApplicationContext()) != null;
    }

    public static String getLoggedInEmail(Context context) {
        SecureSessionStore.Session session = SESSION_STORE.load(context.getApplicationContext());
        return session != null ? session.email : null;
    }

    public static void login(
            Context context,
            String email,
            String password,
            LoginCallback callback) {
        Context appContext = context.getApplicationContext();
        // Try logging in directly first, without a reCAPTCHA token — most logins
        // don't need one. The backend only asks for step-up verification
        // (RECAPTCHA_REQUIRED, handled below) once this e-mail has shown recent
        // suspicious activity, so the WebView challenge is skipped in the common case.
        attemptLogin(appContext, email, password, null, callback);
    }

    private static void attemptLogin(
            Context appContext,
            String email,
            String password,
            String recaptchaToken,
            LoginCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                SpaceConnectApiClient.DeviceInfo device = deviceInfo(appContext);
                SpaceConnectApiClient.AuthResponse auth =
                        API.login(email, password, device, recaptchaToken);
                SESSION_STORE.save(appContext, auth, device.deviceId);
                post(callback::onSuccess);
            } catch (SpaceConnectApiClient.ApiException e) {
                if ("TWO_FACTOR_REQUIRED".equals(e.code) && e.tempToken != null) {
                    post(() -> callback.onTwoFactorRequired(e.tempToken));
                } else if ("RECAPTCHA_REQUIRED".equals(e.code)) {
                    RecaptchaClient.fetchToken(appContext, "login", token -> {
                        if (token == null || token.isEmpty()) {
                            post(() -> callback.onError(e.getMessage()));
                            return;
                        }
                        attemptLogin(appContext, email, password, token, callback);
                    });
                } else {
                    post(() -> callback.onError(e.getMessage()));
                }
            } catch (Exception e) {
                post(() -> callback.onError(userMessage(e)));
            }
        });
    }

    public static void verifyTwoFactor(
            Context context,
            String tempToken,
            String code,
            LoginCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                SpaceConnectApiClient.AuthResponse auth = API.verifyTwoFactor(tempToken, code);
                SESSION_STORE.save(appContext, auth, deviceId(appContext));
                post(callback::onSuccess);
            } catch (Exception e) {
                post(() -> callback.onError(userMessage(e)));
            }
        });
    }

    public static void getStatus(
            Context context,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, API::getStatus, callback);
    }

    public static void joinQueue(
            Context context,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, token -> API.joinQueue(token, 24, "proxmox"), callback);
    }

    public static void leaveQueue(
            Context context,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, API::leaveQueue, callback);
    }

    public static void getMachines(
            Context context,
            ResultCallback<SpaceConnectApiClient.MachinesResponse> callback) {
        executeAuthenticated(context, API::getMachines, callback);
    }

    // Provisiona a VM dedicada do usuário (self-service), igual ao botão "Criar
    // VM" do site — necessário pra usuários sem VM ainda conseguirem jogar
    // pelo app, em vez de ficarem presos num loop de fila sem VM nenhuma.
    public static void createMachine(
            Context context,
            String password,
            ResultCallback<SpaceConnectApiClient.CreateMachineResponse> callback) {
        executeAuthenticated(context, token -> API.createMachine(token, password), callback);
    }

    public static void getConnection(
            Context context,
            ResultCallback<SpaceConnectApiClient.ConnectionResponse> callback) {
        executeAuthenticated(context, API::getConnection, callback);
    }

    public static void endSession(
            Context context,
            ResultCallback<SpaceConnectApiClient.EndSessionResponse> callback) {
        executeAuthenticated(context, API::endSession, callback);
    }

    public static void submitPairPin(Context context, String pin) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    SecureSessionStore.Session session = validSession(appContext);
                    API.pair(session.accessToken, pin);
                    return;
                } catch (Exception ignored) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    public static void logout(Context context) {
        SESSION_STORE.clear(context.getApplicationContext());
    }

    private static <T> void executeAuthenticated(
            Context context,
            AuthenticatedOperation<T> operation,
            ResultCallback<T> callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                SecureSessionStore.Session session = validSession(appContext);
                T result;
                try {
                    result = operation.run(session.accessToken);
                } catch (SpaceConnectApiClient.ApiException e) {
                    if (e.status != 401) throw e;
                    session = refresh(appContext, session);
                    result = operation.run(session.accessToken);
                }
                T finalResult = result;
                post(() -> callback.onSuccess(finalResult));
            } catch (Exception e) {
                post(() -> callback.onError(userMessage(e)));
            }
        });
    }

    private static SecureSessionStore.Session validSession(Context context) throws Exception {
        SecureSessionStore.Session session = SESSION_STORE.load(context);
        if (session == null) throw new IllegalStateException("Faça login novamente");
        if (session.accessExpiresAt <= System.currentTimeMillis() + 60_000L) {
            session = refresh(context, session);
        }
        return session;
    }

    private static SecureSessionStore.Session refresh(
            Context context,
            SecureSessionStore.Session session) throws Exception {
        try {
            SpaceConnectApiClient.AuthResponse auth =
                    API.refresh(session.refreshToken, session.deviceId);
            SESSION_STORE.save(context, auth, session.deviceId);
            SecureSessionStore.Session refreshed = SESSION_STORE.load(context);
            if (refreshed == null) throw new IllegalStateException("Sessão inválida");
            return refreshed;
        } catch (Exception e) {
            SESSION_STORE.clear(context);
            throw e;
        }
    }

    private static SpaceConnectApiClient.DeviceInfo deviceInfo(Context context) {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "Android";
        String name = manufacturer.isEmpty() || model.toLowerCase().startsWith(manufacturer.toLowerCase())
                ? model
                : manufacturer + " " + model;
        return new SpaceConnectApiClient.DeviceInfo(
                deviceId(context),
                name,
                "android",
                BuildConfig.VERSION_NAME);
    }

    private static String deviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(DEVICE_ID_KEY, null);
        if (existing != null && !existing.isEmpty()) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(DEVICE_ID_KEY, created).apply();
        return created;
    }

    private static String userMessage(Exception error) {
        String message = error.getMessage();
        return message != null && !message.trim().isEmpty()
                ? message
                : "Não foi possível acessar a SpaceCloud";
    }

    private static void post(Runnable runnable) {
        MAIN.post(runnable);
    }
}
