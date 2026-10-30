package com.limelight.session;

import android.content.Context;
import android.os.SystemClock;

import com.limelight.account.AccountManager;
import com.limelight.account.SpaceConnectApiClient.ConnectionResponse;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.utils.RequestScope;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

final class ManagedSessionHost implements SessionOrchestrator.Host {
    private final Context context;
    private final String machineId;
    private final String accountName;
    private final boolean friend;
    private final ComputerManagerBinder manager;
    private final AccountManager.SessionRequests requests;
    private final RequestScope scope;
    private final ExecutorService pinWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Session-pair-pin"));
    private volatile Future<?> pinRequest;

    ManagedSessionHost(Context context, String machineId, String accountName, boolean friend,
            ComputerManagerBinder manager, AccountManager.SessionRequests requests, RequestScope scope) {
        this.context = context.getApplicationContext();
        this.machineId = machineId;
        this.accountName = accountName;
        this.friend = friend;
        this.manager = manager;
        this.requests = requests;
        this.scope = scope;
    }

    @Override
    public SessionOrchestrator.PreparedSession prepare(ConnectionResponse connection, Runnable onPairing) throws Exception {
        try {
            scope.checkCancelled();
            String host = connection.host;
            if (host == null || host.trim().isEmpty()) host = connection.ipv6;
            if (host == null || host.trim().isEmpty() || connection.port <= 0 || connection.port > 65535) {
                throw new IOException("Invalid streaming address");
            }
            ComputerDetails.AddressTuple address = new ComputerDetails.AddressTuple(host.trim(), connection.port);
            ComputerDetails saved = manager.getSessionComputer(machineId, address);
            NvHTTP http = http(address, saved);
            String serverInfo = http.getServerInfo(true);
            ComputerDetails computer = http.getComputerDetails(serverInfo);
            if (saved == null || !computer.uuid.equals(saved.uuid)) {
                // A recreated VM can have a new UUID; an old local/manual record may also
                // already hold its certificate. Never reuse another UUID's certificate.
                saved = manager.getComputer(computer.uuid);
                http = http(address, saved);
                serverInfo = http.getServerInfo(true);
                computer = http.getComputerDetails(serverInfo);
            }
            scope.checkCancelled();
            boolean newlyPaired = http.getPairState(serverInfo) != PairingManager.PairState.PAIRED;
            if (newlyPaired) {
                onPairing.run();
                String pin = PairingManager.generatePinString();
                AtomicReference<Exception> pinFailure = new AtomicReference<>();
                pinRequest = pinWorker.submit(() -> {
                    try {
                        requests.submitPairPin(pin);
                    } catch (Exception e) {
                        pinFailure.set(e);
                        // Wake the native handshake if PIN delivery failed or was cancelled.
                        scope.close();
                    }
                });
                PairingManager pairing = http.getPairingManager();
                PairingManager.PairState result;
                try {
                    result = pairing.pair(serverInfo, pin);
                } catch (Exception e) {
                    if (pinFailure.get() != null) throw pinFailure.get();
                    throw e;
                }
                scope.checkCancelled();
                if (result != PairingManager.PairState.PAIRED) {
                    throw new SessionOrchestrator.SessionException(SessionOrchestrator.Failure.PAIRING);
                }
                pinRequest.get();
                if (pinFailure.get() != null) throw pinFailure.get();
                computer.serverCert = pairing.getPairedCert();
            } else if (saved != null) {
                computer.serverCert = saved.serverCert;
            }
            scope.checkCancelled();
            computer.activeAddress = computer.manualAddress = address;
            computer.state = ComputerDetails.State.ONLINE;
            computer.pairState = PairingManager.PairState.PAIRED;
            computer.scMachineId = machineId;
            computer.scOrigin = friend ? "friend" : "launcher";
            if (accountName != null && !accountName.trim().isEmpty()) {
                computer.name = accountName.trim();
                computer.hasCustomName = true;
            } else if (saved != null && saved.hasCustomName) {
                computer.name = saved.name;
                computer.hasCustomName = true;
            }

            NvApp desktop;
            long authorizationDeadline = SystemClock.elapsedRealtime() + (newlyPaired ? 20_000 : 0);
            while (true) {
                scope.checkCancelled();
                try {
                    desktop = DesktopSelector.select(http.getAppList());
                    break;
                } catch (HostHttpResponseException e) {
                    if (e.getErrorCode() != 403 || SystemClock.elapsedRealtime() >= authorizationDeadline) throw e;
                    // The agent grants launch rights to the newly paired UUID asynchronously.
                    Thread.sleep(500);
                }
            }
            if (computer.runningGameId != 0 && computer.runningGameId != desktop.getAppId()) {
                throw new SessionOrchestrator.SessionException(SessionOrchestrator.Failure.HOST_BUSY);
            }
            scope.checkCancelled();
            manager.saveSessionComputer(computer, scope);
            return new SessionOrchestrator.PreparedSession(computer, desktop, manager.getUniqueId(), connection, newlyPaired);
        } finally {
            pinWorker.shutdownNow();
        }
    }

    private NvHTTP http(ComputerDetails.AddressTuple address, ComputerDetails saved) throws IOException {
        scope.checkCancelled();
        return new NvHTTP(address, 0, manager.getUniqueId(), saved == null ? null : saved.serverCert,
                PlatformBinding.getCryptoProvider(context), scope);
    }

    @Override
    public void cancel() {
        scope.close();
        Future<?> pending = pinRequest;
        if (pending != null) pending.cancel(true);
        pinWorker.shutdownNow();
    }
}
