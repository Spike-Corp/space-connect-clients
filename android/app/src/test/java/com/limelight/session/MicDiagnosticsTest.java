package com.limelight.session;

import android.content.Context;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.Looper;
import com.limelight.account.AccountManager;
import com.limelight.account.SessionAccountSafetyTest;
import com.limelight.account.SessionAccountSafetyTest.Api;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.BuildConfig;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, instrumentedPackages = "com.limelight.account",
        shadows = {SessionAccountSafetyTest.Store.class, SessionAccountSafetyTest.Api.class})
public class MicDiagnosticsTest {
    private Context context;
    private Thread.UncaughtExceptionHandler original;
    private AtomicInteger delegated;
    private boolean initialized;

    @Before public void setup() {
        org.junit.Assume.assumeTrue(BuildConfig.MIC_DIAGNOSTICS);
        initialized = true;
        context = RuntimeEnvironment.getApplication();
        original = Thread.getDefaultUncaughtExceptionHandler();
        delegated = new AtomicInteger();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> delegated.incrementAndGet());
        Api.bugReport = null;
    }

    @After public void cleanup() {
        if (!initialized) return;
        Thread.setDefaultUncaughtExceptionHandler(original);
        ReflectionHelpers.setStaticField(MicDiagnostics.class, "active", null);
    }

    @Test public void theLastMicrophonePhaseSurvivesProcessStateLossWithoutSendingAnything() {
        MicDiagnostics.install(context);
        MicDiagnostics.record(MicDiagnostics.Phase.RECORDER_STARTING);
        ReflectionHelpers.setStaticField(MicDiagnostics.class, "active", null);
        String report = MicDiagnostics.report(context);
        assertTrue(report, report.contains("RECORDER_STARTING"));
        assertNull(Api.bugReport);
    }

    @Test public void anUnhandledFailureKeepsItsStackButNotItsMessageAndDelegatesToAndroid() {
        MicDiagnostics.install(context);
        RuntimeException error = new RuntimeException("Authorization: Bearer private-token; recorded-voice");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.limelight.binding.audio.MicForwarder", "runCaptureLoop", "MicForwarder.java", 177)
        });
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), error);
        String report = MicDiagnostics.report(context);
        assertEquals(1, delegated.get());
        assertTrue(report, report.contains("java.lang.RuntimeException"));
        assertTrue(report, report.contains("MicForwarder.runCaptureLoop:177"));
        assertFalse(report.contains("private-token"));
        assertFalse(report.contains("recorded-voice"));
    }

    @Test public void storageFailureCannotPreventTheOriginalCrashHandlerFromRunning() throws Exception {
        File directory = new File(context.getNoBackupFilesDir(), "mic-diagnostic");
        assertTrue(directory.createNewFile());
        MicDiagnostics.install(context);
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(
                Thread.currentThread(), new IllegalStateException("fixture"));
        assertEquals(1, delegated.get());
        assertTrue(MicDiagnostics.report(context).contains("DIAGNOSTIC_STORAGE_ERROR"));
    }

    @Test @Config(sdk = 35)
    public void aRecentSystemKillIsNotReplacedByAnOlderNativeCrash() {
        org.robolectric.shadows.ShadowActivityManager manager =
                shadowOf(context.getSystemService(ActivityManager.class));
        manager.addApplicationExitInfo(context.getPackageName(), 10, ApplicationExitInfo.REASON_CRASH_NATIVE, 6);
        manager.addApplicationExitInfo(context.getPackageName(), 11, ApplicationExitInfo.REASON_LOW_MEMORY, 9);
        String report = MicDiagnostics.report(context);
        assertTrue(report, report.contains("reason=3 status=9"));
        assertFalse(report, report.contains("reason=5"));
    }

    @Test public void aUserSubmittedReportIncludesThePersistedCrashWithinTheExistingApiLimit() throws Exception {
        MicDiagnostics.install(context);
        RuntimeException error = new RuntimeException("secret-not-for-report");
        StackTraceElement[] stack = new StackTraceElement[60];
        for (int i = 0; i < stack.length; i++)
            stack[i] = new StackTraceElement("com.limelight.binding.audio.MicForwarder" + "LongSymbol".repeat(30),
                    "repeatedFrameForBoundedCrashDiagnostic", "MicForwarder.java", i + 10);
        error.setStackTrace(stack);
        for (int i = 0; i < 3; i++) {
            error = new RuntimeException("secret-not-for-report", error);
            error.setStackTrace(stack);
        }
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), error);
        SessionAccountSafetyTest.saveAccountFixture(context, "first");
        String description = "O microfone fechou o aplicativo. " + "d".repeat(950);
        AtomicInteger sent = new AtomicInteger();
        AccountManager.reportBug(context, description, "", new AccountManager.ResultCallback<SpaceConnectApiClient.SimpleResponse>() {
            @Override public void onSuccess(SpaceConnectApiClient.SimpleResponse response) { sent.incrementAndGet(); }
            @Override public void onError(String message) { fail(message); }
        });
        SessionAccountSafetyTest.awaitAccountWorker();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, sent.get());
        assertNotNull(Api.bugReport);
        assertEquals(description, Api.bugReport.description);
        assertNotNull(Api.bugReport.context);
        assertTrue(Api.bugReport.context.size() <= 20);
        assertEquals(Boolean.TRUE, Api.bugReport.context.get("mic_diag_truncated"));
        for (Object value : Api.bugReport.context.values()) assertTrue(value.toString().length() <= 200);
        assertTrue(Api.bugReport.context.toString().contains("java.lang.RuntimeException"));
        assertFalse(Api.bugReport.context.toString().contains("secret-not-for-report"));
    }
}
