package com.limelight.account;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.limelight.account.SpaceConnectApiClient.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, instrumentedPackages = "com.limelight.account",
        shadows = {SessionAccountSafetyTest.Store.class, VmMonitorServiceTest.Api.class})
public class VmMonitorServiceTest {
    private static final String FIRST = "507f1f77bcf86cd799439011";
    private static final String SECOND = "507f1f77bcf86cd799439022";
    private Context context;
    private ServiceController<VmMonitorService> service;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        AccountManager.logout(context);
        SessionAccountSafetyTest.saveAccountFixture(context, "first");
        shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(true);
        Api.statuses.clear();
        Api.reads.clear();
        Api.failures.clear();
        Api.managementFailure = null;
        Api.managementReads = 0;
        Api.management = new ManagementResponse();
        Api.management.serverNow = Instant.now().toString();
        Api.management.operations = new VmOperation[0];
        Api.management.volumes = new DataVolume[0];
        Api.management.machines = new MachineListItem[] {machine(FIRST), machine(SECOND)};
        Api.statuses.put(FIRST, status(FIRST, "starting"));
        Api.statuses.put(SECOND, status(SECOND, "starting"));
    }

    @After public void cleanup() throws Exception {
        if (service != null) service.destroy();
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Intent observation(String machineId) {
        long generation = ReflectionHelpers.callStaticMethod(AccountManager.class, "currentAccountGeneration");
        return new Intent(context, VmMonitorService.class).putExtra("machineId", machineId)
                .putExtra("boot", true).putExtra("accountGeneration", generation);
    }

    private void settle() throws Exception {
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void twoMachinesNotifyOnlyWhenEachIsReadyAndNeverOpenTheStream() throws Exception {
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        service.get().onStartCommand(observation(SECOND), 0, 2);
        settle();
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + SECOND));

        Api.statuses.put(SECOND, status(SECOND, "ready"));
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
        settle();
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertTrue(VmMonitorService.hasSessionNotice(context, true, "session-" + SECOND));
        Notification notice = shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification("ready:session-" + SECOND, 1);
        assertNotNull(notice);
        assertEquals(MachineManagementActivity.class.getName(),
                shadowOf(notice.contentIntent).getSavedIntent().getComponent().getClassName());
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());

        Api.statuses.put(FIRST, status(FIRST, "ready"));
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
        settle();
        assertTrue(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        int notifications = shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().size();
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
        settle();
        assertEquals(notifications,
                shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().size());
    }

    @Test public void anotherMachinesStatusCannotAnnounceThisMachineReady() throws Exception {
        Api.statuses.put(FIRST, status(SECOND, "ready"));
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        settle();
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + SECOND));
    }

    private void pollAgain() throws Exception {
        shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
        settle();
    }

    private void assertBootReadStops(Exception failure, int attempts) throws Exception {
        Api.failures.put(FIRST, failure);
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        settle();
        for (int i = 0; i < 4; i++) pollAgain();
        assertEquals(attempts, Api.reads.size());
        assertNotNull(shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification("boot_failed:" + FIRST, 1));
        Api.failures.clear();
        Api.statuses.put(FIRST, status(FIRST, "ready"));
        pollAgain();
        assertEquals(attempts, Api.reads.size());
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test public void aTlsFailureEndsBootObservationInsteadOfRetrying() throws Exception {
        assertBootReadStops(new javax.net.ssl.SSLHandshakeException("Untrusted fixture certificate"), 1);
    }

    @Test public void accessDeniedEndsBootObservationInsteadOfRetrying() throws Exception {
        assertBootReadStops(new ApiException(403, "ACCESS_DENIED", "Denied", null), 1);
    }

    @Test public void transportFailuresEndBootObservationAfterThreeReads() throws Exception {
        assertBootReadStops(new java.io.IOException("Fixture transport failure"), 3);
    }

    @Test public void successfulReadsResetTheConsecutiveFailureBudget() throws Exception {
        service = Robolectric.buildService(VmMonitorService.class).create();
        Api.failures.put(FIRST, new java.io.IOException("First outage"));
        service.get().onStartCommand(observation(FIRST), 0, 1);
        settle();
        pollAgain();
        Api.failures.clear();
        pollAgain();
        Api.failures.put(FIRST, new java.io.IOException("Second outage"));
        pollAgain();
        pollAgain();
        Api.failures.clear();
        Api.statuses.put(FIRST, status(FIRST, "ready"));
        pollAgain();
        assertTrue(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertEquals(6, Api.reads.size());
    }

    @Test public void aNewExplicitObservationCanRetryAfterATerminalFailure() throws Exception {
        Api.failures.put(FIRST, new ApiException(403, "ACCESS_DENIED", "Denied", null));
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        settle();
        assertNotNull(shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification("boot_failed:" + FIRST, 1));
        Api.failures.clear();
        Api.statuses.put(FIRST, status(FIRST, "ready"));
        service.get().onStartCommand(observation(FIRST), 0, 2);
        settle();
        assertTrue(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertEquals(2, Api.reads.size());
    }

    @Test public void oneFailedMachineDoesNotEndAnotherMachinesObservation() throws Exception {
        Api.failures.put(FIRST, new ApiException(403, "ACCESS_DENIED", "Denied", null));
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        service.get().onStartCommand(observation(SECOND), 0, 2);
        settle();
        Api.statuses.put(SECOND, status(SECOND, "ready"));
        pollAgain();
        assertTrue(VmMonitorService.hasSessionNotice(context, true, "session-" + SECOND));
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertEquals(1, java.util.Collections.frequency(Api.reads, FIRST));
    }

    private void assertManagementReadStops(Exception failure, int attempts) throws Exception {
        Api.managementFailure = failure;
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        settle();
        for (int i = 0; i < 4; i++) pollAgain();
        assertEquals(attempts, Api.managementReads);
        assertTrue(shadowOf(service.get()).isStoppedBySelf());
        assertNotNull(shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification("monitor_failed", 1));
        Api.managementFailure = null;
        Api.statuses.put(FIRST, status(FIRST, "ready"));
        pollAgain();
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertTrue(Api.reads.isEmpty());
    }

    @Test public void accessDeniedAlsoStopsManagementPolling() throws Exception {
        assertManagementReadStops(new ApiException(403, "ACCESS_DENIED", "Denied", null), 1);
    }

    @Test public void managementTransportFailuresAreBounded() throws Exception {
        assertManagementReadStops(new java.io.IOException("Fixture transport failure"), 3);
    }

    @Test public void anOlderFailedStartCannotCancelTheNewBootObservation() throws Exception {
        VmOperation old = new VmOperation();
        old.id = "old-start";
        old.machineId = FIRST;
        old.kind = "power_on";
        old.state = "failed";
        old.createdAt = Instant.now().minusSeconds(3600).toString();
        Api.management.operations = new VmOperation[] {old};
        Api.statuses.put(FIRST, status(FIRST, "ready"));
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        settle();
        assertTrue(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
    }

    @Test public void logoutAndLoginWithTheSameEmailDiscardAQueuedResponse() throws Exception {
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(observation(FIRST), 0, 1);
        SessionAccountSafetyTest.awaitAccountWorker();
        AccountManager.logout(context);
        SessionAccountSafetyTest.saveAccountFixture(context, "first");
        settle();
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
        assertTrue(Api.reads.isEmpty());
    }

    @Test public void anObservationFromAPreviousLoginCannotStartRequests() throws Exception {
        Intent old = observation(FIRST);
        AccountManager.logout(context);
        SessionAccountSafetyTest.saveAccountFixture(context, "first");
        service = Robolectric.buildService(VmMonitorService.class).create();
        service.get().onStartCommand(old, 0, 1);
        settle();
        assertEquals(0, Api.managementReads);
        assertTrue(Api.reads.isEmpty());
        assertFalse(VmMonitorService.hasSessionNotice(context, true, "session-" + FIRST));
    }

    private static MachineListItem machine(String id) {
        MachineListItem item = new MachineListItem();
        item.id = id;
        item.name = "PC " + id;
        item.state = "busy";
        item.phases = new MachinePhases();
        item.session = status(id, "starting").session;
        return item;
    }

    private static StatusResponse status(String id, String state) {
        StatusResponse response = new StatusResponse();
        response.state = state;
        response.serverNow = Instant.now().toString();
        response.session = new SessionStatus();
        response.session.id = "session-" + id;
        response.session.endAt = Instant.now().plusSeconds(3600).toString();
        response.session.remainingMs = 3_600_000;
        response.session.machine = new MachineStatus();
        response.session.machine.id = id;
        response.session.machine.name = "PC " + id;
        response.session.machine.state = "busy";
        return response;
    }

    @Implements(value = SpaceConnectApiClient.class, isInAndroidSdk = false)
    public static class Api extends SessionAccountSafetyTest.Api {
        static final Map<String, StatusResponse> statuses = new HashMap<>();
        static final Map<String, Exception> failures = new HashMap<>();
        static final List<String> reads = new ArrayList<>();
        static ManagementResponse management;
        static Exception managementFailure;
        static int managementReads;

        @Implementation protected ManagementResponse getManagement(String token) throws Exception {
            assertEquals("first-access", token);
            managementReads++;
            if (managementFailure != null) throw managementFailure;
            return management;
        }

        @Override @Implementation protected StatusResponse getStatus(String token, String machineId) throws Exception {
            assertEquals("first-access", token);
            reads.add(machineId);
            assertTrue("Only explicit observed machines may be polled", statuses.containsKey(machineId));
            if (failures.containsKey(machineId)) throw failures.get(machineId);
            return statuses.get(machineId);
        }
    }
}
