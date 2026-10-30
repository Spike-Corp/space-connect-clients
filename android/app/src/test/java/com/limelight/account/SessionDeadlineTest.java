package com.limelight.account;

import android.app.AlarmManager;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SessionDeadlineTest {
    @Test public void observingInsideTheWarningWindowStillSchedulesVerification() {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        SessionDeadlineReceiver.scheduleRemaining(context, "vm1", "session1", "First", 2 * 60_000L);
        ShadowAlarmManager.ScheduledAlarm alarm = shadowOf(context.getSystemService(AlarmManager.class)).getNextScheduledAlarm();
        assertNotNull(alarm);
        assertTrue(alarm.triggerAtTime >= now);
        assertTrue(alarm.triggerAtTime <= now + 2000);
    }

    @Test public void anExtendedSessionReplacesItsDeadlineWithoutReplacingAnotherVm() {
        Context context = RuntimeEnvironment.getApplication();
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        SessionDeadlineReceiver.scheduleRemaining(context, "vm1", "session1", "First", 6 * 60_000L);
        SessionDeadlineReceiver.scheduleRemaining(context, "vm2", "session2", "Second", 10 * 60_000L);
        SessionDeadlineReceiver.scheduleRemaining(context, "vm1", "session1", "First", 20 * 60_000L);
        assertEquals(2, shadowOf(manager).getScheduledAlarms().size());
        long now = System.currentTimeMillis();
        assertTrue(shadowOf(manager).getScheduledAlarms().stream()
                .anyMatch(alarm -> Math.abs(alarm.triggerAtTime - now - 15 * 60_000L) < 1000));
    }

    @Test public void queuedMachinesCannotBeUsedAsStoppedTransferTargets() {
        SpaceConnectApiClient.MachineListItem machine = new SpaceConnectApiClient.MachineListItem();
        machine.state = "offline";
        assertTrue(MachineManagementActivity.stopped(machine));
        machine.queue = new SpaceConnectApiClient.QueueStatus();
        assertFalse(MachineManagementActivity.stopped(machine));
    }
}
