package com.limelight.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.limelight.utils.RequestScope;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SpaceConnectApiClientTest {
    private MockWebServer server;
    private SpaceConnectApiClient client;

    @Test
    public void scopedPairPinsAndQueueCancellationTargetTheSameExplicitMachine() throws Exception {
        String machine = "507f1f77bcf86cd799439011";
        server.enqueue(new MockResponse().setBody("{\"paired\":false,\"viaAgent\":true}"));
        server.enqueue(new MockResponse().setBody("{\"state\":\"idle\"}"));
        RequestScope scope = new RequestScope();
        SpaceConnectApiClient scoped = client.withScope(scope);
        SpaceConnectApiClient.PairResponse ack = scoped.pair("test-access", "1234", machine);
        assertTrue(ack.viaAgent);
        assertFalse(ack.paired);
        RecordedRequest pair = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("/api/launcher/v1/pair", pair.getPath());
        com.google.gson.JsonObject body = new com.google.gson.Gson().fromJson(pair.getBody().readUtf8(), com.google.gson.JsonObject.class);
        assertEquals(machine, body.get("machineId").getAsString());
        scoped.leaveQueue("test-access", machine);
        RecordedRequest leave = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("DELETE", leave.getMethod());
        assertEquals("/api/launcher/v1/queue?machineId=" + machine, leave.getPath());
        scope.close();
    }

    @Test
    public void cancellingBodyReadDoesNotCancelAnotherScopesCallOrGlobalClient() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"state\":\"queued\"}").setBodyDelay(2, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("{\"state\":\"ready\"}"));
        RequestScope cancelled = new RequestScope();
        RequestScope unrelated = new RequestScope();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> failed = worker.submit(() -> {
                try {
                    client.withScope(cancelled).getStatus("test");
                    return false;
                } catch (java.io.IOException expected) {
                    return true;
                }
            });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            cancelled.close();
            assertTrue(failed.get(1, TimeUnit.SECONDS));
            assertEquals("ready", client.withScope(unrelated).getStatus("test").state);
            assertFalse(unrelated.isCancelled());
            server.enqueue(new MockResponse().setBody("{\"state\":\"idle\"}"));
            assertEquals("idle", client.getStatus("test").state);
        } finally {
            cancelled.close();
            unrelated.close();
            worker.shutdownNow();
        }
    }

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new SpaceConnectApiClient(server.url("/api/launcher/v1/").toString());
    }

    @Test
    public void cancelledPinAcknowledgementCannotAffectImmediateRetryForAnotherMachine() throws Exception {
        String oldMachine = "507f1f77bcf86cd799439011";
        String newMachine = "507f1f77bcf86cd799439022";
        server.enqueue(new MockResponse().setBody("{\"paired\":false,\"viaAgent\":true}")
                .setBodyDelay(1, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("{\"paired\":false,\"viaAgent\":true}"));
        RequestScope oldScope = new RequestScope(), newScope = new RequestScope();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> oldPin = worker.submit(() -> {
                try {
                    client.withScope(oldScope).pair("fixture", "1234", oldMachine);
                    return false;
                } catch (java.io.IOException cancelled) {
                    return true;
                }
            });
            RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first);
            oldScope.close();
            SpaceConnectApiClient.PairResponse retry = client.withScope(newScope).pair("fixture", "5678", newMachine);
            assertTrue(oldPin.get(1, TimeUnit.SECONDS));
            assertTrue(retry.viaAgent);
            assertFalse(retry.paired);
            assertFalse(newScope.isCancelled());
            RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(second);
            assertTrue(first.getBody().readUtf8().contains("\"machineId\":\"" + oldMachine + "\""));
            assertTrue(second.getBody().readUtf8().contains("\"machineId\":\"" + newMachine + "\""));
            assertEquals(2, server.getRequestCount());
        } finally {
            oldScope.close();
            newScope.close();
            worker.shutdownNow();
        }
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void loginSendsDeviceIdentityAndParsesTokens() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"accessToken\":\"access-1\","
                        + "\"refreshToken\":\"refresh-1\","
                        + "\"accessTokenExpiresIn\":900,"
                        + "\"refreshTokenExpiresAt\":\"2026-08-14T00:00:00.000Z\","
                        + "\"user\":{\"id\":\"user-1\",\"email\":\"client@spacecloud.gg\",\"name\":\"Cliente\"}"
                        + "}"));

        SpaceConnectApiClient.AuthResponse response = client.login(
                "client@spacecloud.gg",
                "secret",
                new SpaceConnectApiClient.DeviceInfo(
                        "device-1234",
                        "Pixel",
                        "android",
                        "0.1.0"),
                "recaptcha-tok");

        assertEquals("access-1", response.accessToken);
        assertEquals("refresh-1", response.refreshToken);
        assertEquals("client@spacecloud.gg", response.user.email);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/auth/login", request.getPath());
        assertEquals("POST", request.getMethod());
        String body = request.getBody().readUtf8();
        assertEquals(true, body.contains("\"deviceId\":\"device-1234\""));
        assertEquals(true, body.contains("\"platform\":\"android\""));
        assertEquals(true, body.contains("\"recaptchaToken\":\"recaptcha-tok\""));
    }

    @Test
    public void verifyTwoFactorExchangesChallengeForTokens() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"accessToken\":\"access-2\","
                        + "\"refreshToken\":\"refresh-2\","
                        + "\"accessTokenExpiresIn\":900,"
                        + "\"refreshTokenExpiresAt\":\"2026-08-14T00:00:00.000Z\","
                        + "\"user\":{\"id\":\"user-1\",\"email\":\"client@spacecloud.gg\"}"
                        + "}"));

        SpaceConnectApiClient.AuthResponse response =
                client.verifyTwoFactor("temporary-token", "123456");

        assertEquals("access-2", response.accessToken);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/auth/2fa", request.getPath());
        String body = request.getBody().readUtf8();
        assertEquals(true, body.contains("\"tempToken\":\"temporary-token\""));
        assertEquals(true, body.contains("\"code\":\"123456\""));
    }

    @Test
    public void getStatusUsesBearerTokenAndParsesQueuePosition() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"state\":\"queued\","
                        + "\"queue\":{\"position\":2,\"total\":7,\"priority\":5,\"planSlug\":\"builder-pro\"},"
                        + "\"session\":null,"
                        + "\"serverNow\":\"2026-07-14T00:00:00.000Z\""
                        + "}"));

        SpaceConnectApiClient.StatusResponse response = client.getStatus("access-1");

        assertEquals("queued", response.state);
        assertEquals(2, response.queue.position);
        assertEquals(7, response.queue.total);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/status", request.getPath());
        assertEquals("Bearer access-1", request.getHeader("Authorization"));
    }

    @Test
    public void statusUsesAdminBootTextsAndPreservesEmptyTips() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"state\":\"queued\",\"queue\":{\"position\":2,\"total\":3},\"bootTexts\":{"
                + "\"queueTitle\":\"Fila {position}/{total}\",\"phases\":{\"starting\":\"Texto editado\"},\"tips\":[]}}"));
        SpaceConnectApiClient.StatusResponse status = client.getStatus("access-1");
        assertEquals("Fila 2/3", status.bootTexts.queueTitle(2, 3, "fallback"));
        assertEquals("Texto editado", status.bootTexts.phases.get("starting"));
        assertEquals(0, status.bootTexts.tips.length);
        assertEquals("fallback", SpaceConnectApiClient.BootTexts.text(" ", "fallback"));
        assertEquals("fallback", SpaceConnectApiClient.BootTexts.text(null, "fallback"));
    }

    @Test
    public void joinQueueUsesTheSharedLauncherEndpoint() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"state\":\"queued\",\"queue\":{\"position\":1,\"total\":3}}"));

        SpaceConnectApiClient.StatusResponse response =
                client.joinQueue("access-1", 3, "proxmox");

        assertEquals("queued", response.state);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/queue", request.getPath());
        assertEquals("Bearer access-1", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertEquals(true, body.contains("\"requestedHours\":3.0"));
        assertEquals(true, body.contains("\"provider\":\"proxmox\""));
    }

    @Test
    public void joinQueueIncludesTheSelectedMachineId() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"state\":\"queued\",\"queue\":{\"position\":1,\"total\":1}}"));

        client.joinQueue("access-1", 24, "proxmox", "507f1f77bcf86cd799439011");

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/queue", request.getPath());
        assertEquals(true, request.getBody().readUtf8()
                .contains("\"machineId\":\"507f1f77bcf86cd799439011\""));
    }

    @Test
    public void getConnectionParsesMoonlightHostAndPort() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"sessionId\":\"session-1\","
                        + "\"machineId\":\"machine-1\","
                        + "\"machineName\":\"Space PC\","
                        + "\"host\":\"203.0.113.10\","
                        + "\"port\":48000,"
                        + "\"expiresAt\":\"2026-07-14T03:00:00.000Z\""
                        + "}"));

        SpaceConnectApiClient.ConnectionResponse response =
                client.getConnection("access-1");

        assertEquals("203.0.113.10", response.host);
        assertEquals(48000, response.port);
    }

    @Test
    public void getConnectionTargetsTheSelectedMachine() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"host\":\"203.0.113.10\",\"port\":48000}"));

        client.getConnection("access-1", "507f1f77bcf86cd799439011");

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/connection?machineId=507f1f77bcf86cd799439011", request.getPath());
    }

    @Test
    public void uploadTargetsTheSelectedMachine() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"filename\":\"save.zip\"}"));

        client.uploadFile(
                "access-1",
                "507f1f77bcf86cd799439011",
                "save.zip",
                new ByteArrayInputStream(new byte[] { 1, 2, 3 }),
                3);

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/session/upload", request.getPath());
        String body = request.getBody().readUtf8();
        assertEquals(true, body.contains("name=\"machineId\""));
        assertEquals(true, body.contains("507f1f77bcf86cd799439011"));
        assertEquals(true, body.contains("filename=\"save.zip\""));
    }

    @Test
    public void refreshRotatesTheOpaqueTokenForTheSameDevice() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"accessToken\":\"access-2\","
                        + "\"refreshToken\":\"refresh-2\","
                        + "\"accessTokenExpiresIn\":900,"
                        + "\"refreshTokenExpiresAt\":\"2026-08-14T00:00:00.000Z\""
                        + "}"));

        SpaceConnectApiClient.AuthResponse response =
                client.refresh("refresh-1", "device-1234");

        assertEquals("access-2", response.accessToken);
        assertEquals("refresh-2", response.refreshToken);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/auth/refresh", request.getPath());
        String body = request.getBody().readUtf8();
        assertEquals(true, body.contains("\"refreshToken\":\"refresh-1\""));
        assertEquals(true, body.contains("\"deviceId\":\"device-1234\""));
    }

    @Test
    public void leaveQueueUsesAuthenticatedDelete() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"state\":\"idle\",\"queue\":null,\"session\":null}"));

        SpaceConnectApiClient.StatusResponse response = client.leaveQueue("access-1");

        assertEquals("idle", response.state);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/launcher/v1/queue", request.getPath());
        assertEquals("Bearer access-1", request.getHeader("Authorization"));
    }

    @Test
    public void getMachinesParsesFuturePresaleEntitlement() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"locked\":true,"
                        + "\"machines\":[],"
                        + "\"upcomingEntitlements\":[{"
                        + "\"id\":\"future-balance\","
                        + "\"planSlug\":\"ibm-l4-mini\","
                        + "\"planName\":\"NVIDIA L4 Mini\","
                        + "\"status\":\"active\","
                        + "\"active\":false,"
                        + "\"availableAt\":\"2026-10-28T03:00:00.000Z\""
                        + "}]"
                        + "}"));

        SpaceConnectApiClient.MachinesResponse response = client.getMachines("access-1");

        assertEquals(true, response.locked);
        assertNotNull(response.upcomingEntitlements);
        assertEquals(1, response.upcomingEntitlements.length);
        assertEquals("future-balance", response.upcomingEntitlements[0].id);
        assertEquals("ibm-l4-mini", response.upcomingEntitlements[0].planSlug);
        assertEquals("2026-10-28T03:00:00.000Z", response.upcomingEntitlements[0].availableAt);
    }

    @Test
    public void pairSubmitsTheMoonlightPinThroughTheTrustedApi() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"paired\":true,\"viaAgent\":true,\"viaQemu\":false,\"message\":\"Pareado\"}"));

        SpaceConnectApiClient.PairResponse response = client.pair("access-1", "1234");

        assertEquals(true, response.paired);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/pair", request.getPath());
        assertEquals(true, request.getBody().readUtf8().contains("\"pin\":\"1234\""));
    }

    @Test
    public void searchFriendEncodesUsernameAndParsesAvatarPreview() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"profile\":{"
                        + "\"userId\":\"user-2\","
                        + "\"username\":\"amigo_teste\","
                        + "\"name\":\"Amigo Teste\","
                        + "\"avatarUrl\":\"https://cdn.example/avatar.png?token=signed\","
                        + "\"staffRole\":\"master\","
                        + "\"acceptsFriendRequests\":false"
                        + "},"
                        + "\"relationship\":\"incoming\""
                        + "}"));

        SpaceConnectApiClient.FriendSearchResponse response =
                client.searchFriend("access-1", "@amigo_teste");

        assertEquals("incoming", response.relationship);
        assertEquals("amigo_teste", response.profile.username);
        assertEquals("https://cdn.example/avatar.png?token=signed", response.profile.avatarUrl);
        assertEquals("master", response.profile.staffRole);
        assertEquals(Boolean.FALSE, response.profile.acceptsFriendRequests);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/launcher/v1/friends/search?u=%40amigo_teste", request.getPath());
    }

    @Test
    public void staffCanDisableIncomingFriendRequests() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"acceptFriendRequests\":false}"));

        SpaceConnectApiClient.FriendSettingsResponse response =
                client.setFriendRequestSettings("access-1", false);

        assertEquals(false, response.acceptFriendRequests);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("PATCH", request.getMethod());
        assertEquals("/api/launcher/v1/friends/settings", request.getPath());
        assertEquals(true, request.getBody().readUtf8().contains("\"acceptFriendRequests\":false"));
    }

    @Test
    public void endSessionUsesTheAuthenticatedLauncherEndpoint() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accepted\":true,\"alreadyEnding\":false}"));

        SpaceConnectApiClient.EndSessionResponse response = client.endSession("access-1");

        assertEquals(true, response.accepted);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/api/launcher/v1/session/end", request.getPath());
        assertEquals("Bearer access-1", request.getHeader("Authorization"));
    }
}
