package com.limelight.account;

import android.content.Context;
import com.limelight.utils.RequestScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, instrumentedPackages = "com.limelight.account",
        shadows = {SessionAccountSafetyTest.Store.class, SessionAccountSafetyTest.Api.class})
public class SessionAccountSafetyTest {
    private Context context;
    private static final String MACHINE = "507f1f77bcf86cd799439011";

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Api.tokens.clear();
        Api.refreshCount = Api.disconnectCount = 0;
        Api.duringRefresh = () -> {};
        Store.afterSave = () -> {};
        new SecureSessionStore().save(context, auth("first"), "device-fixture");
    }

    @Test public void oldMachineRequestsNeverBorrowTheAccountLoggedInAfterLogout() throws Exception {
        try (AccountManager.SessionRequests requests = AccountManager.sessionRequests(
                context, MACHINE, false, new RequestScope())) {
            AccountManager.logout(context);
            new SecureSessionStore().save(context, auth("second"), "device-fixture");
            SpaceConnectApiClient.ApiException error = assertThrows(SpaceConnectApiClient.ApiException.class, requests::status);
            assertEquals("ACCOUNT_CHANGED", error.code);
            assertTrue(Api.tokens.isEmpty());
            assertEquals("second@example.invalid", AccountManager.getLoggedInEmail(context));
        }
    }

    @Test public void aRefreshReceivedAfterLogoutCannotRestoreTheOldLogin() throws Exception {
        Store.session.accessExpiresAt = 0;
        Api.duringRefresh = () -> AccountManager.logout(context);
        try (AccountManager.SessionRequests requests = AccountManager.sessionRequests(
                context, MACHINE, false, new RequestScope())) {
            assertThrows(SpaceConnectApiClient.ApiException.class, requests::status);
            assertFalse(AccountManager.isLoggedIn(context));
            assertTrue(Api.tokens.isEmpty());
        }
    }

    @Test public void partialDisconnectIsNotReplayedByAutomaticTokenRefresh() throws Exception {
        try (AccountManager.SessionRequests requests = AccountManager.sessionRequests(
                context, MACHINE, false, new RequestScope())) {
            SpaceConnectApiClient.ApiException error = assertThrows(SpaceConnectApiClient.ApiException.class,
                    () -> requests.disconnectStreamGuest("507f1f77bcf86cd799439055", "507f1f77bcf86cd799439022"));
            assertEquals(Integer.valueOf(1), error.requested);
            assertEquals(1, Api.disconnectCount);
            assertEquals(0, Api.refreshCount);
        }
    }

    @Test public void aNewOperationScopeCannotAdoptAnotherLogin() throws Exception {
        try (AccountManager.SessionRequests requests = AccountManager.sessionRequests(
                context, MACHINE, false, new RequestScope())) {
            AccountManager.logout(context);
            new SecureSessionStore().save(context, auth("second"), "device-fixture");
            try (AccountManager.StreamRequests operation = requests.withScope(new RequestScope())) {
                SpaceConnectApiClient.ApiException error = assertThrows(SpaceConnectApiClient.ApiException.class,
                        () -> operation.streamGuests("507f1f77bcf86cd799439055"));
                assertEquals("ACCOUNT_CHANGED", error.code);
                assertTrue(Api.tokens.isEmpty());
            }
        }
    }

    @Test public void closingAReadDoesNotCloseTheNextReadInTheSameStreamingSession() throws Exception {
        try (AccountManager.SessionRequests session = AccountManager.sessionRequests(context, MACHINE, false, new RequestScope());
             AccountManager.StreamRequests read = session.withScope(new RequestScope())) {
            read.close();
            assertThrows(java.io.InterruptedIOException.class,
                    () -> read.streamGuests("507f1f77bcf86cd799439055"));
            try (AccountManager.StreamRequests next = session.withScope(new RequestScope())) {
                assertEquals(0, next.streamGuests("507f1f77bcf86cd799439055").guests.length);
                assertEquals(1, Api.tokens.size());
            }
        }
    }

    @Test public void friendsRegisterTheirIdentityWithoutReceivingOwnerControls() throws Exception {
        try (AccountManager.SessionRequests requests = AccountManager.sessionRequests(context, MACHINE, true, new RequestScope())) {
            StreamClientIdentity identity = new StreamClientIdentity(new com.limelight.binding.crypto.AndroidCryptoProvider(context));
            assertFalse(requests.registerStream(identity).canManageGuests);
            assertThrows(IllegalStateException.class,
                    () -> requests.streamGuests("507f1f77bcf86cd799439055"));
            assertThrows(IllegalStateException.class,
                    () -> requests.disconnectStreamGuest("507f1f77bcf86cd799439055", "507f1f77bcf86cd799439022"));
            assertEquals(0, Api.disconnectCount);
        }
    }

    @Test public void aScopeCannotReadTheNextAccountsTokenHalfwayThroughSavingItsLogin() throws Exception {
        CountDownLatch saved = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (AccountManager.SessionRequests requests = AccountManager.sessionRequests(context, MACHINE, false, new RequestScope())) {
            Store.afterSave = () -> {
                saved.countDown();
                try { assertTrue(release.await(4, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            };
            AccountManager.verifyTwoFactor(context, "fixture", "123456", new AccountManager.LoginCallback() {
                @Override public void onSuccess() {}
                @Override public void onTwoFactorRequired(String token) { fail("Already completing two-factor authentication"); }
                @Override public void onError(String message) { fail(message); }
            });
            assertTrue(saved.await(2, TimeUnit.SECONDS));
            Future<String> read = worker.submit(() -> {
                started.countDown();
                try { requests.status(); return "sent"; }
                catch (SpaceConnectApiClient.ApiException error) { return error.code; }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> read.get(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("ACCOUNT_CHANGED", read.get(2, TimeUnit.SECONDS));
            assertTrue(Api.tokens.isEmpty());
            assertEquals("second@example.invalid", AccountManager.getLoggedInEmail(context));
        } finally {
            release.countDown();
            Store.afterSave = () -> {};
            worker.shutdownNow();
        }
    }

    @Test public void streamControlsKeepTheOriginalAccountAfterStartupRequestsClose() throws Exception {
        com.limelight.session.SessionOrchestrator.Backend backend = backend();
        backend.cancel();
        try (AccountManager.StreamRequests stream = backend.streamRequests(new RequestScope())) {
            assertEquals(0, stream.streamGuests("507f1f77bcf86cd799439055").guests.length);
        }
        AccountManager.logout(context);
        new SecureSessionStore().save(context, auth("second"), "device-fixture");
        Api.tokens.clear();
        try (AccountManager.StreamRequests late = backend.streamRequests(new RequestScope())) {
            assertThrows(SpaceConnectApiClient.ApiException.class,
                    () -> late.streamGuests("507f1f77bcf86cd799439055"));
            assertTrue(Api.tokens.isEmpty());
        }
    }

    @Test public void delayedQueueCleanupUsesItsOriginalTokenInsteadOfTheNewAccountsToken() throws Exception {
        com.limelight.session.SessionOrchestrator.Backend backend = backend();
        backend.status();
        backend.cancel();
        AccountManager.logout(context);
        new SecureSessionStore().save(context, auth("second"), "device-fixture");
        Api.tokens.clear();
        backend.cleanupQueue(false);
        assertEquals(java.util.Collections.singletonList("first-access"), Api.tokens);
        assertEquals("second@example.invalid", AccountManager.getLoggedInEmail(context));
    }

    private com.limelight.session.SessionOrchestrator.Backend backend() throws Exception {
        return org.robolectric.util.ReflectionHelpers.callConstructor(
                Class.forName("com.limelight.session.SessionBackend")
                        .asSubclass(com.limelight.session.SessionOrchestrator.Backend.class),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Context.class, context),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class, MACHINE),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(RequestScope.class, new RequestScope()));
    }

    private static SpaceConnectApiClient.AuthResponse auth(String name) {
        SpaceConnectApiClient.AuthResponse auth = new SpaceConnectApiClient.AuthResponse();
        auth.accessToken = name + "-access";
        auth.refreshToken = name + "-refresh";
        auth.accessTokenExpiresIn = 900;
        auth.user = new SpaceConnectApiClient.User();
        auth.user.id = name;
        auth.user.email = name + "@example.invalid";
        auth.user.name = name;
        return auth;
    }

    @Implements(value = SecureSessionStore.class, isInAndroidSdk = false)
    public static class Store {
        static SecureSessionStore.Session session;
        static Runnable afterSave = () -> {};
        @Implementation protected boolean isSupported() { return true; }
        @Implementation protected SecureSessionStore.Session load(Context context) { return session; }
        @Implementation protected void clear(Context context) { session = null; }
        @Implementation protected void save(Context context, SpaceConnectApiClient.AuthResponse auth, String deviceId) {
            SecureSessionStore.Session previous = session;
            session = new SecureSessionStore.Session();
            session.accessToken = auth.accessToken;
            session.refreshToken = auth.refreshToken;
            session.email = auth.user == null ? previous.email : auth.user.email;
            session.name = auth.user == null ? previous.name : auth.user.name;
            session.deviceId = deviceId;
            session.accessExpiresAt = System.currentTimeMillis() + auth.accessTokenExpiresIn * 1000L;
            afterSave.run();
        }
    }

    @Implements(value = SpaceConnectApiClient.class, isInAndroidSdk = false)
    public static class Api {
        static final List<String> tokens = new ArrayList<>();
        static Runnable duringRefresh;
        static int refreshCount, disconnectCount;
        @Implementation protected SpaceConnectApiClient.AuthResponse verifyTwoFactor(String token, String code) {
            return auth("second");
        }
        @Implementation protected SpaceConnectApiClient.StatusResponse getStatus(String token, String machineId) {
            tokens.add(token);
            SpaceConnectApiClient.StatusResponse status = new SpaceConnectApiClient.StatusResponse();
            status.state = "ready";
            return status;
        }
        @Implementation protected SpaceConnectApiClient.StatusResponse leaveQueue(String token, String machineId) {
            assertEquals(MACHINE, machineId);
            return getStatus(token, machineId);
        }
        @Implementation protected SpaceConnectApiClient.AuthResponse refresh(String refreshToken, String deviceId) {
            refreshCount++;
            duringRefresh.run();
            SpaceConnectApiClient.AuthResponse auth = auth("first");
            auth.accessToken = "first-access-refreshed";
            return auth;
        }
        @Implementation protected SpaceConnectApiClient.StreamChallengeResponse streamChallenge(String token, String machineId, String pem) {
            SpaceConnectApiClient.StreamChallengeResponse result = new SpaceConnectApiClient.StreamChallengeResponse();
            result.challenge = "opaque-challenge";
            return result;
        }
        @Implementation protected SpaceConnectApiClient.StreamIdentityResponse registerStream(
                String token, String machineId, String pem, String challenge, String signature) {
            SpaceConnectApiClient.StreamIdentityResponse result = new SpaceConnectApiClient.StreamIdentityResponse();
            result.bindingId = "507f1f77bcf86cd799439055";
            result.canManageGuests = false;
            return result;
        }
        @Implementation protected SpaceConnectApiClient.StreamGuestsResponse streamGuests(String token, String machineId, String bindingId) {
            tokens.add(token);
            SpaceConnectApiClient.StreamGuestsResponse result = new SpaceConnectApiClient.StreamGuestsResponse();
            result.guests = new SpaceConnectApiClient.StreamGuest[0];
            result.unidentifiedCount = 0;
            return result;
        }
        @Implementation protected SpaceConnectApiClient.StreamDisconnectResponse disconnectStreamGuest(
                String token, String machineId, String bindingId, String guestUserId) throws SpaceConnectApiClient.ApiException {
            disconnectCount++;
            throw new SpaceConnectApiClient.ApiException(401, "ACCESS_REVOKED", "Access revoked", null, 1, 0, 1, 0);
        }
    }
}
