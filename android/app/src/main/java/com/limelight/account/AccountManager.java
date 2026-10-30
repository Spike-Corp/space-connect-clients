package com.limelight.account;

import com.limelight.BuildConfig;
import com.limelight.utils.RequestScope;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

public final class AccountManager {
    private static final String API_BASE_URL = "https://spacecloud.gg/api/launcher/v1/";
    private static final String DEVICE_PREFS = "space_connect_device";
    private static final String DEVICE_ID_KEY = "device_id";

    private static final SpaceConnectApiClient API = new SpaceConnectApiClient(API_BASE_URL);
    private static final SecureSessionStore SESSION_STORE = new SecureSessionStore();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ReentrantLock SESSION_LOCK = new ReentrantLock();

    // Liga host -> (machineId, apelido de conta) pra sessão atual do processo —
    // preenchido a cada connect/connectFriend bem-sucedido, consultado pelo
    // PcView pra saber em qual VM da conta aplicar o "Renomear" e o auto-apply
    // do apelido (accountName). Mesmo esquema do desktop (LauncherApi::
    // m_MachineIdByAddress): só em memória, dura a sessão do processo — se o
    // app reabrir sem reconectar, o rename simplesmente fica só local, igual lá.
    private static final Map<String, String> machineIdByHost = new HashMap<>();
    private static final Map<String, String> accountNameByHost = new HashMap<>();

    private AccountManager() {
    }

    // Guarda o vínculo host -> (machineId, accountName) da VM que acabou de ser
    // conectada (própria ou de amigo). hostPort pode vir com porta (ex.:
    // "1.2.3.4:47989") — só o host é usado como chave, pra casar mesmo se a
    // porta do /connection divergir do endereço salvo no ComputerDetails.
    public static void rememberMachineLink(String hostPort, String machineId, String accountName) {
        String key = hostKey(hostPort);
        if (key.isEmpty()) return;
        if (machineId != null && !machineId.trim().isEmpty()) {
            machineIdByHost.put(key, machineId.trim());
        }
        if (accountName != null && !accountName.trim().isEmpty()) {
            accountNameByHost.put(key, accountName.trim());
        }
        else {
            // null/vazio = sem apelido custom (nunca renomeado) — remove
            // qualquer valor velho pra não reaplicar um nome desatualizado.
            accountNameByHost.remove(key);
        }
    }

    public static String getMachineIdForHost(String hostPort) {
        return machineIdByHost.get(hostKey(hostPort));
    }

    public static String getAccountNameForHost(String hostPort) {
        return accountNameByHost.get(hostKey(hostPort));
    }

    private static String hostKey(String hostPort) {
        if (hostPort == null) return "";
        String h = hostPort.trim();
        if (h.isEmpty()) return "";
        if (h.startsWith("[")) {
            // IPv6 com colchetes: "[::1]:47989" -> "::1"
            int end = h.indexOf(']');
            return (end >= 0 ? h.substring(1, end) : h).toLowerCase(Locale.ROOT);
        }
        int colon = h.indexOf(':');
        return (colon >= 0 ? h.substring(0, colon) : h).toLowerCase(Locale.ROOT);
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

    public static String getLoggedInName(Context context) {
        SecureSessionStore.Session session = SESSION_STORE.load(context.getApplicationContext());
        return session != null ? session.name : null;
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

    // O poll de status precisa mandar o mesmo machineId usado pra entrar na fila,
    // senão o backend antigo não achava a entrada e o app voltava pra "Abrir Máquina"
    // como se o usuário tivesse saído da fila.
    public static void getStatus(
            Context context,
            String machineId,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, token -> API.getStatus(token, machineId), callback);
    }

    public static Future<?> getStatus(Context context, String machineId, RequestScope scope,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        Context appContext = context.getApplicationContext();
        SpaceConnectApiClient api = API.withScope(scope);
        return EXECUTOR.submit(() -> {
            try {
                scope.checkCancelled();
                SecureSessionStore.Session session = validSession(appContext, api, scope);
                SpaceConnectApiClient.StatusResponse result;
                try {
                    result = api.getStatus(session.accessToken, machineId);
                } catch (SpaceConnectApiClient.ApiException e) {
                    if (e.status != 401) throw e;
                    session = refresh(appContext, session, api, scope);
                    result = api.getStatus(session.accessToken, machineId);
                }
                SpaceConnectApiClient.StatusResponse response = result;
                post(() -> { if (!scope.isCancelled()) callback.onSuccess(response); });
            } catch (Exception e) {
                post(() -> { if (!scope.isCancelled()) callback.onError(userMessage(e)); });
            }
        });
    }

    public static void joinQueue(
            Context context,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, token -> API.joinQueue(token, 24, "proxmox"), callback);
    }

    public static void joinQueue(
            Context context,
            String machineId,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, token -> API.joinQueue(token, 24, "proxmox", machineId), callback);
    }

    public static void leaveQueue(
            Context context,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, API::leaveQueue, callback);
    }

    public static void leaveQueue(Context context, String machineId,
            ResultCallback<SpaceConnectApiClient.StatusResponse> callback) {
        executeAuthenticated(context, token -> API.leaveQueue(token, machineId), callback);
    }

    /**
     * Synchronous, attempt-owned requests. Call on the session worker, never the UI thread.
     * The selected VM and ownership cannot change while pairing or cancelling.
     */
    public static final class SessionRequests implements AutoCloseable {
        private final Context context;
        private final String machineId;
        private final boolean friend;
        private final RequestScope scope;
        private final SpaceConnectApiClient api;

        private SessionRequests(Context context, String machineId, boolean friend, RequestScope scope) {
            if (machineId == null || !machineId.matches("^[a-fA-F0-9]{24}$")) {
                throw new IllegalArgumentException("Invalid machine ID");
            }
            this.context = context.getApplicationContext();
            this.machineId = machineId;
            this.friend = friend;
            this.scope = scope;
            this.api = API.withScope(scope);
        }

        private <T> T execute(AuthenticatedOperation<T> operation) throws Exception {
            scope.checkCancelled();
            SecureSessionStore.Session session = validSession(context, api, scope);
            T result;
            try {
                result = operation.run(session.accessToken);
            } catch (SpaceConnectApiClient.ApiException e) {
                if (e.status != 401) throw e;
                scope.checkCancelled();
                session = refresh(context, session, api, scope);
                result = operation.run(session.accessToken);
            }
            scope.checkCancelled();
            return result;
        }

        public SpaceConnectApiClient.StatusResponse status() throws Exception {
            return execute(token -> api.getStatus(token, machineId));
        }

        public SpaceConnectApiClient.StatusResponse joinQueue() throws Exception {
            if (friend) throw new IllegalStateException("Cannot power on a friend's machine");
            return execute(token -> api.joinQueue(token, 24, "proxmox", machineId));
        }

        public SpaceConnectApiClient.StatusResponse leaveQueue() throws Exception {
            if (friend) throw new IllegalStateException("Cannot leave a friend's queue");
            return execute(token -> api.leaveQueue(token, machineId));
        }

        public SpaceConnectApiClient.ConnectionResponse connection() throws Exception {
            return execute(token -> friend ? api.getFriendConnection(token, machineId)
                    : api.getConnection(token, machineId));
        }

        public SpaceConnectApiClient.PairResponse submitPairPin(String pin) throws Exception {
            for (int attempt = 0; ; attempt++) {
                scope.checkCancelled();
                try {
                    SpaceConnectApiClient.PairResponse result = execute(token -> friend
                            ? api.pairFriend(token, machineId, pin) : api.pair(token, pin, machineId));
                    // This is an acknowledgement only. PairingManager must finish the handshake.
                    if (result != null && (result.paired || result.viaAgent || result.viaQemu)) return result;
                    throw new java.io.IOException("Pair request not accepted");
                } catch (Exception e) {
                    scope.checkCancelled();
                    if (attempt >= 7 || (e instanceof SpaceConnectApiClient.ApiException
                            && (((SpaceConnectApiClient.ApiException) e).status == 401
                                || ((SpaceConnectApiClient.ApiException) e).status == 403))) throw e;
                    Thread.sleep(500);
                }
            }
        }

        @Override public void close() { scope.close(); }
    }

    public static SessionRequests sessionRequests(Context context, String machineId,
            boolean friend, RequestScope scope) {
        return new SessionRequests(context, machineId, friend, scope);
    }

    public static void getMachines(
            Context context,
            ResultCallback<SpaceConnectApiClient.MachinesResponse> callback) {
        executeAuthenticated(context, API::getMachines, callback);
    }

    // Só chama o backend se a VM for do usuário (dono renomeando); pra VM de
    // amigo o próprio backend rejeita (404) e o rename fica só local no app —
    // por isso é fire-and-forget, sem exibir erro pro usuário.
    public static void renameMachine(
            Context context,
            String machineId,
            String name,
            ResultCallback<SpaceConnectApiClient.RenameMachineResponse> callback) {
        executeAuthenticated(context, token -> API.renameMachine(token, machineId, name), callback);
    }

    // Troca a senha do Windows (scg) da PRÓPRIA VM, sem formatar. Aplica ao vivo
    // se a máquina estiver ligada, senão no próximo boot. Igual ao site.
    public static void setMachinePassword(
            Context context,
            String machineId,
            String password,
            ResultCallback<SpaceConnectApiClient.SetMachinePasswordResponse> callback) {
        executeAuthenticated(context, token -> API.setMachinePassword(token, machineId, password), callback);
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

    // Cria a VM de um plano específico (balanceId) — usado pelo "Criar nova VM"
    // quando o usuário já tem máquinas e escolhe de qual plano criar a próxima.
    public static void createMachine(
            Context context,
            String password,
            String balanceId,
            ResultCallback<SpaceConnectApiClient.CreateMachineResponse> callback) {
        executeAuthenticated(context, token -> API.createMachine(token, password, balanceId), callback);
    }

    public static void getConnection(
            Context context,
            ResultCallback<SpaceConnectApiClient.ConnectionResponse> callback) {
        executeAuthenticated(context, API::getConnection, callback);
    }

    public static void getConnection(
            Context context,
            String machineId,
            ResultCallback<SpaceConnectApiClient.ConnectionResponse> callback) {
        executeAuthenticated(context, token -> API.getConnection(token, machineId), callback);
    }

    // Garante que o MicBridge está rodando na VM (chamado ao ligar o mic).
    // Fire-and-forget: callback pode ser null.
    public static void ensureMicBridge(
            Context context,
            ResultCallback<SpaceConnectApiClient.MicEnsureResponse> callback) {
        executeAuthenticated(context, API::ensureMicBridge, callback);
    }

    // Biblioteca de downloads (aba Downloads do app).
    public static void getDownloads(
            Context context,
            ResultCallback<SpaceConnectApiClient.DownloadsResponse> callback) {
        executeAuthenticated(context, API::getDownloads, callback);
    }

    // A VM baixa o item direto da URL pra pasta Downloads dela (não pro
    // aparelho local). Polling no status depois.
    public static void startVmDownload(
            Context context,
            String itemId,
            ResultCallback<SpaceConnectApiClient.VmDownloadStartResponse> callback) {
        executeAuthenticated(context, token -> API.startVmDownload(token, itemId), callback);
    }

    public static void getVmDownloadStatus(
            Context context,
            String itemId,
            ResultCallback<SpaceConnectApiClient.VmDownloadStatusResponse> callback) {
        executeAuthenticated(context, token -> API.getVmDownloadStatus(token, itemId), callback);
    }

    // Licença NVIDIA vGPU (RTX Virtual Workstation) da T4 — o cliente aplica
    // depois de atualizar o driver GRID. O token é empurrado direto pra VM.
    public static void getGpuLicenseStatus(
            Context context,
            ResultCallback<SpaceConnectApiClient.GpuLicenseStatusResponse> callback) {
        executeAuthenticated(context, API::getGpuLicenseStatus, callback);
    }

    public static void applyGpuLicense(
            Context context,
            ResultCallback<SpaceConnectApiClient.GpuLicenseApplyResponse> callback) {
        executeAuthenticated(context, API::applyGpuLicense, callback);
    }

    // Exclui a VM de VERDADE (wipe total na nuvem). Irreversível.
    public static void deleteMachine(
            Context context,
            String machineId,
            ResultCallback<SpaceConnectApiClient.DeleteMachineResponse> callback) {
        executeAuthenticated(context, token -> API.deleteMachine(token, machineId), callback);
    }

    // Transfere o disco de uma VM pra outra (a de origem é excluída, a de
    // destino ganha o espaço inteiro). Só faz sentido com 2+ VMs Proxmox.
    public static void transferDisk(
            Context context,
            String sourceMachineId,
            String targetMachineId,
            ResultCallback<SpaceConnectApiClient.TransferDiskResponse> callback) {
        executeAuthenticated(context, token -> API.transferDisk(token, sourceMachineId, targetMachineId), callback);
    }

    public static void endSession(
            Context context,
            ResultCallback<SpaceConnectApiClient.EndSessionResponse> callback) {
        executeAuthenticated(context, API::endSession, callback);
    }

    public static void endSession(
            Context context,
            String machineId,
            ResultCallback<SpaceConnectApiClient.EndSessionResponse> callback) {
        executeAuthenticated(context, token -> API.endSession(token, machineId), callback);
    }

    // Envia um arquivo (stream do SAF) pra pasta Downloads da VM do usuario.
    // Requer sessao/maquina ligada — o backend responde com erro claro se nao ha.
    public static void uploadFileToVm(
            Context context,
            String machineId,
            String fileName,
            java.io.InputStream input,
            long length,
            ResultCallback<SpaceConnectApiClient.UploadResponse> callback) {
        executeAuthenticated(context, token -> API.uploadFile(token, machineId, fileName, input, length), callback);
    }

    // Relato de bug pra página "Bugs app" do admin. Funciona COM e SEM sessão:
    // logado, amarra à conta; na tela de login (onde o próprio login pode ser o
    // bug), envia anônimo com o e-mail digitado.
    public static void reportBug(
            Context context,
            String description,
            String emailHint,
            ResultCallback<SpaceConnectApiClient.SimpleResponse> callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                String token = null;
                String email = emailHint;
                SecureSessionStore.Session session = SESSION_STORE.load(appContext);
                if (session != null) {
                    try {
                        token = validSession(appContext).accessToken;
                    } catch (Exception ignored) {
                        // Sessão expirada/inválida não impede o relato.
                        token = null;
                    }
                    if (email == null || email.trim().isEmpty()) {
                        email = session.email;
                    }
                }

                SpaceConnectApiClient.DeviceInfo device = deviceInfo(appContext);
                SpaceConnectApiClient.BugReportRequest input =
                        new SpaceConnectApiClient.BugReportRequest();
                input.description = description;
                input.email = email;
                input.app = "android";
                input.appVersion = BuildConfig.VERSION_NAME;
                input.osVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
                input.deviceModel = device.name;
                input.deviceId = device.deviceId;

                SpaceConnectApiClient.SimpleResponse result = API.reportBug(token, input);
                post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                post(() -> callback.onError(userMessage(e)));
            }
        });
    }

    public static void submitPairPin(Context context, String pin) {
        Context appContext = context.getApplicationContext();
        // Pareamento na VM de um AMIGO usa a rota friend-aware (o backend valida
        // a permissão de acesso). O flag é setado pelo fluxo de conexão de amigo
        // (FriendsActivity) e limpo no pareamento bem-sucedido / conexão própria.
        SharedPreferences launcherPrefs = appContext.getSharedPreferences("space_connect_launcher", Context.MODE_PRIVATE);
        String friendMachine = launcherPrefs.getString("pending_friend_machine", null);
        EXECUTOR.execute(() -> {
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    SecureSessionStore.Session session = validSession(appContext);
                    if (friendMachine != null) {
                        SpaceConnectApiClient.PairResponse r =
                                API.pairFriend(session.accessToken, friendMachine, pin);
                        if (r != null && r.paired) {
                            launcherPrefs.edit().remove("pending_friend_machine").apply();
                            return;
                        }
                        if (r != null && r.viaAgent) {
                            return;
                        }
                    } else {
                        API.pair(session.accessToken, pin);
                        return;
                    }
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

    // ── Emuladores ──────────────────────────────────────────────────────────

    public static void getEmulators(Context context, ResultCallback<SpaceConnectApiClient.EmulatorsResponse> callback) {
        executeAuthenticated(context, API::getEmulators, callback);
    }

    public static void getEmulatorStatus(Context context, ResultCallback<SpaceConnectApiClient.EmulatorStatusResponse> callback) {
        executeAuthenticated(context, API::getEmulatorStatus, callback);
    }

    public static void installEmulator(Context context, String emulatorId, ResultCallback<SpaceConnectApiClient.EmulatorActionResponse> callback) {
        executeAuthenticated(context, token -> API.installEmulator(token, emulatorId), callback);
    }

    public static void getEmulatorInstallStatus(Context context, String emulatorId, ResultCallback<SpaceConnectApiClient.EmulatorInstallStatusResponse> callback) {
        executeAuthenticated(context, token -> API.getEmulatorInstallStatus(token, emulatorId), callback);
    }

    public static void launchEmulator(Context context, String emulatorId, ResultCallback<SpaceConnectApiClient.EmulatorActionResponse> callback) {
        executeAuthenticated(context, token -> API.launchEmulator(token, emulatorId), callback);
    }

    // ── Amigos (beta) ───────────────────────────────────────────────────────

    public static void getFriends(Context context, ResultCallback<SpaceConnectApiClient.FriendsResponse> callback) {
        executeAuthenticated(context, API::getFriends, callback);
    }

    public static void getFriendMachines(Context context, ResultCallback<SpaceConnectApiClient.FriendMachinesResponse> callback) {
        executeAuthenticated(context, API::getFriendMachines, callback);
    }

    public static void searchFriend(Context context, String username, ResultCallback<SpaceConnectApiClient.FriendSearchResponse> callback) {
        executeAuthenticated(context, token -> API.searchFriend(token, username), callback);
    }

    public static void setFriendRequestSettings(Context context, boolean enabled, ResultCallback<SpaceConnectApiClient.FriendSettingsResponse> callback) {
        executeAuthenticated(context, token -> API.setFriendRequestSettings(token, enabled), callback);
    }

    public static void addFriend(Context context, String username, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.addFriend(token, username), callback);
    }

    public static void acceptFriendRequest(Context context, String requestId, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.acceptFriendRequest(token, requestId), callback);
    }

    public static void declineFriendRequest(Context context, String requestId, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.declineFriendRequest(token, requestId), callback);
    }

    public static void removeFriend(Context context, String friendId, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.removeFriend(token, friendId), callback);
    }

    public static void setFriendPermissions(Context context, String friendId, boolean showMachine, boolean allowConnect, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.setFriendPermissions(token, friendId, showMachine, allowConnect), callback);
    }

    public static void setFriendMachinePermission(Context context, String friendId, String machineId, boolean showMachine, boolean allowConnect, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.setFriendPermissions(token, friendId, machineId, showMachine, allowConnect), callback);
    }

    public static void checkUsername(Context context, String username, ResultCallback<SpaceConnectApiClient.UsernameAvailabilityResponse> callback) {
        executeAuthenticated(context, token -> API.checkUsername(token, username), callback);
    }

    public static void setUsername(Context context, String username, ResultCallback<SpaceConnectApiClient.FriendActionResponse> callback) {
        executeAuthenticated(context, token -> API.setUsername(token, username), callback);
    }

    public static void getFriendConnection(Context context, String machineId, ResultCallback<SpaceConnectApiClient.ConnectionResponse> callback) {
        executeAuthenticated(context, token -> API.getFriendConnection(token, machineId), callback);
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
        return validSession(context, API, null);
    }

    private static SecureSessionStore.Session validSession(Context context,
            SpaceConnectApiClient api, RequestScope scope) throws Exception {
        SESSION_LOCK.lockInterruptibly();
        try {
            if (scope != null) scope.checkCancelled();
            SecureSessionStore.Session session = SESSION_STORE.load(context);
            if (session == null) throw new IllegalStateException("Faça login novamente");
            if (session.accessExpiresAt <= System.currentTimeMillis() + 60_000L) {
                session = refresh(context, session, api, scope);
            }
            return session;
        } finally {
            SESSION_LOCK.unlock();
        }
    }

    private static SecureSessionStore.Session refresh(
            Context context,
            SecureSessionStore.Session session) throws Exception {
        return refresh(context, session, API, null);
    }

    private static SecureSessionStore.Session refresh(Context context, SecureSessionStore.Session session,
            SpaceConnectApiClient api, RequestScope scope) throws Exception {
        SESSION_LOCK.lockInterruptibly();
        try {
            if (scope != null) scope.checkCancelled();
            SecureSessionStore.Session current = SESSION_STORE.load(context);
            if (current == null) throw new IllegalStateException("Faça login novamente");
            if (!current.accessToken.equals(session.accessToken)) return current;
            try {
                SpaceConnectApiClient.AuthResponse auth = api.refresh(session.refreshToken, session.deviceId);
                // A cancelled refresh may have rotated the token on the server already.
                // Persist a received replacement before observing cancellation.
                SESSION_STORE.save(context, auth, session.deviceId);
                SecureSessionStore.Session refreshed = SESSION_STORE.load(context);
                if (refreshed == null) throw new IllegalStateException("Sessão inválida");
                return refreshed;
            } catch (Exception e) {
                // Cancelling a session's I/O must not log out other screens.
                if (e instanceof SpaceConnectApiClient.ApiException
                        && ((SpaceConnectApiClient.ApiException) e).status == 401) {
                    SESSION_STORE.clear(context);
                }
                throw e;
            }
        } finally {
            SESSION_LOCK.unlock();
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
