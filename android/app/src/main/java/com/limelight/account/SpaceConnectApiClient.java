package com.limelight.account;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class SpaceConnectApiClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public SpaceConnectApiClient(String baseUrl) {
        this(baseUrl, new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    SpaceConnectApiClient(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.httpClient = httpClient;
        this.gson = new Gson();
    }

    public AuthResponse login(String email, String password, DeviceInfo device, String recaptchaToken)
            throws IOException, ApiException {
        LoginRequest input = new LoginRequest();
        input.email = email;
        input.password = password;
        input.deviceId = device.deviceId;
        input.name = device.name;
        input.platform = device.platform;
        input.appVersion = device.appVersion;
        input.recaptchaToken = recaptchaToken;
        return post("auth/login", input, null, AuthResponse.class);
    }

    public AuthResponse verifyTwoFactor(String tempToken, String code) throws IOException, ApiException {
        TwoFactorRequest input = new TwoFactorRequest();
        input.tempToken = tempToken;
        input.code = code;
        return post("auth/2fa", input, null, AuthResponse.class);
    }

    public StatusResponse getStatus(String accessToken) throws IOException, ApiException {
        return get("status", accessToken, StatusResponse.class);
    }

    public StatusResponse joinQueue(
            String accessToken,
            double requestedHours,
            String provider) throws IOException, ApiException {
        QueueRequest input = new QueueRequest();
        input.requestedHours = requestedHours;
        input.provider = provider;
        return post("queue", input, accessToken, StatusResponse.class);
    }

    public ConnectionResponse getConnection(String accessToken) throws IOException, ApiException {
        return get("connection", accessToken, ConnectionResponse.class);
    }

    public AuthResponse refresh(String refreshToken, String deviceId) throws IOException, ApiException {
        RefreshRequest input = new RefreshRequest();
        input.refreshToken = refreshToken;
        input.deviceId = deviceId;
        return post("auth/refresh", input, null, AuthResponse.class);
    }

    public StatusResponse leaveQueue(String accessToken) throws IOException, ApiException {
        return delete("queue", accessToken, StatusResponse.class);
    }

    public PairResponse pair(String accessToken, String pin) throws IOException, ApiException {
        PairRequest input = new PairRequest();
        input.pin = pin;
        return post("pair", input, accessToken, PairResponse.class);
    }

    public EndSessionResponse endSession(String accessToken) throws IOException, ApiException {
        return post("session/end", new EmptyRequest(), accessToken, EndSessionResponse.class);
    }

    private <T> T post(String path, Object input, String accessToken, Class<T> responseType)
            throws IOException, ApiException {
        Request.Builder request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(JSON, gson.toJson(input)))
                .header("Accept", "application/json")
                .header("User-Agent", "SpaceConnect-Android");
        if (accessToken != null && !accessToken.isEmpty()) {
            request.header("Authorization", "Bearer " + accessToken);
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            String json = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw parseApiError(response.code(), json);
            }
            try {
                return gson.fromJson(json, responseType);
            } catch (JsonSyntaxException e) {
                throw new IOException("Resposta inválida da SpaceCloud", e);
            }
        }
    }

    private <T> T get(String path, String accessToken, Class<T> responseType)
            throws IOException, ApiException {
        Request.Builder request = new Request.Builder()
                .url(baseUrl + path)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "SpaceConnect-Android");
        if (accessToken != null && !accessToken.isEmpty()) {
            request.header("Authorization", "Bearer " + accessToken);
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            String json = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw parseApiError(response.code(), json);
            }
            try {
                return gson.fromJson(json, responseType);
            } catch (JsonSyntaxException e) {
                throw new IOException("Resposta inválida da SpaceCloud", e);
            }
        }
    }

    private <T> T delete(String path, String accessToken, Class<T> responseType)
            throws IOException, ApiException {
        Request.Builder request = new Request.Builder()
                .url(baseUrl + path)
                .delete()
                .header("Accept", "application/json")
                .header("User-Agent", "SpaceConnect-Android");
        if (accessToken != null && !accessToken.isEmpty()) {
            request.header("Authorization", "Bearer " + accessToken);
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            String json = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw parseApiError(response.code(), json);
            }
            try {
                return gson.fromJson(json, responseType);
            } catch (JsonSyntaxException e) {
                throw new IOException("Resposta inválida da SpaceCloud", e);
            }
        }
    }

    private ApiException parseApiError(int status, String json) {
        try {
            ErrorEnvelope envelope = gson.fromJson(json, ErrorEnvelope.class);
            if (envelope != null && envelope.error != null) {
                return new ApiException(
                        status,
                        envelope.error.code,
                        envelope.error.message,
                        envelope.error.tempToken);
            }
        } catch (JsonSyntaxException ignored) {
        }
        return new ApiException(status, "REQUEST_FAILED", "Falha ao acessar a SpaceCloud", null);
    }

    private static final class LoginRequest {
        String email;
        String password;
        String deviceId;
        String name;
        String platform;
        String appVersion;
        String recaptchaToken;
    }

    private static final class TwoFactorRequest {
        String tempToken;
        String code;
    }

    private static final class QueueRequest {
        double requestedHours;
        String provider;
    }

    private static final class RefreshRequest {
        String refreshToken;
        String deviceId;
    }

    private static final class PairRequest {
        String pin;
    }

    private static final class EmptyRequest {
    }

    private static final class ErrorEnvelope {
        ApiError error;
    }

    private static final class ApiError {
        String code;
        String message;
        String tempToken;
    }

    public static final class DeviceInfo {
        public final String deviceId;
        public final String name;
        public final String platform;
        public final String appVersion;

        public DeviceInfo(String deviceId, String name, String platform, String appVersion) {
            this.deviceId = deviceId;
            this.name = name;
            this.platform = platform;
            this.appVersion = appVersion;
        }
    }

    public static final class AuthResponse {
        public String accessToken;
        public String refreshToken;
        public int accessTokenExpiresIn;
        public String refreshTokenExpiresAt;
        public User user;
    }

    public static final class User {
        public String id;
        public String email;
        public String name;
        public String role;
        public boolean twoFactorEnabled;
    }

    public static final class StatusResponse {
        public String state;
        public QueueStatus queue;
        public SessionStatus session;
        public String serverNow;
    }

    public static final class QueueStatus {
        public int position;
        public int total;
        public int priority;
        public String planSlug;
        public double requestedHours;
        public String joinedAt;
    }

    public static final class SessionStatus {
        public String id;
        public MachineStatus machine;
        public String startAt;
        public String endAt;
        public long remainingMs;
    }

    public static final class MachineStatus {
        public String id;
        public String name;
        public String state;
        public String creationPhase;
        public String shutdownPhase;
    }

    public static final class ConnectionResponse {
        public String sessionId;
        public String machineId;
        public String machineName;
        public String host;
        public String ipv6;
        public int port;
        public String expiresAt;
    }

    public static final class PairResponse {
        public boolean paired;
        public boolean viaAgent;
        public boolean viaQemu;
        public String message;
    }

    public static final class EndSessionResponse {
        public boolean accepted;
        public boolean alreadyEnding;
    }

    public static final class ApiException extends Exception {
        public final int status;
        public final String code;
        public final String tempToken;

        ApiException(int status, String code, String message, String tempToken) {
            super(message != null && !message.isEmpty() ? message : "Falha ao acessar a SpaceCloud");
            this.status = status;
            this.code = code != null ? code : "REQUEST_FAILED";
            this.tempToken = tempToken;
        }
    }
}
