package com.limelight.utils;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 34})
@LooperMode(LooperMode.Mode.PAUSED)
public class SpinnerDialogTest {
    private ActivityController<TestActivity> controller;
    private TestActivity activity;

    public static class TestActivity extends Activity {
        @Override
        protected void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(TestActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        SpinnerDialog.closeDialogs(activity);
        idle();
        controller.close();
    }

    @Test
    public void connectionCompletionClosesTheLastStageWithoutFinishingTheStream() {
        SpinnerDialog spinner = showSpinner(true);
        ProgressDialog dialog = latestDialog();
        assertTrue(dialog.isShowing());

        spinner.setMessage("Iniciando conectando os controles");
        idle();
        assertEquals("Iniciando conectando os controles", shadowOf(dialog).getMessage().toString());

        // Game.connectionStarted() dismisses this dialog before capturing input.
        spinner.dismiss();
        idle();

        assertFalse("Connected stream must not be covered by its loading dialog", dialog.isShowing());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void backgroundCompletionClosesTheDialogOnTheUiThread() throws Exception {
        SpinnerDialog spinner = showSpinner(true);
        ProgressDialog dialog = latestDialog();

        onWorker(spinner::dismiss);
        idle();

        assertFalse(dialog.isShowing());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void repeatedDismissAndLateStageCannotReopenTheDialog() {
        SpinnerDialog spinner = showSpinner(true);
        ProgressDialog dialog = latestDialog();

        spinner.dismiss();
        spinner.dismiss();
        spinner.setMessage("Mensagem atrasada");
        spinner.run();
        idle();

        assertSame(dialog, ShadowDialog.getLatestDialog());
        assertFalse(dialog.isShowing());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void dismissBeforeQueuedCreationDoesNotShowAWindow() throws Exception {
        Dialog previous = ShadowDialog.getLatestDialog();
        onWorker(() -> {
            SpinnerDialog spinner = SpinnerDialog.displayDialog(activity, "Conexão", "Preparando", true);
            spinner.dismiss();
        });
        idle();

        assertSame(previous, ShadowDialog.getLatestDialog());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void closingAnActivityAlsoCancelsItsQueuedCreation() throws Exception {
        Dialog previous = ShadowDialog.getLatestDialog();
        onWorker(() -> SpinnerDialog.displayDialog(activity, "Conexão", "Preparando", true));

        SpinnerDialog.closeDialogs(activity);
        idle();

        assertSame(previous, ShadowDialog.getLatestDialog());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void latestStageIsKeptEvenBeforeQueuedCreation() throws Exception {
        AtomicReference<SpinnerDialog> spinner = new AtomicReference<>();
        onWorker(() -> spinner.set(SpinnerDialog.displayDialog(activity, "Conexão", "Preparando", true)));

        spinner.get().setMessage("Autenticando com segurança");
        idle();

        assertEquals("Autenticando com segurança", shadowOf(latestDialog()).getMessage().toString());
        assertTrue(latestDialog().isShowing());
    }

    @Test
    public void repeatedCreationDoesNotHideTheLoadingDialog() {
        SpinnerDialog spinner = showSpinner(false);
        ProgressDialog dialog = latestDialog();

        spinner.run();
        idle();

        assertSame(dialog, ShadowDialog.getLatestDialog());
        assertTrue(dialog.isShowing());
    }

    @Test
    public void finishingActivityStillDismissesItsVisibleDialog() {
        SpinnerDialog spinner = showSpinner(true);
        ProgressDialog dialog = latestDialog();

        activity.finish();
        spinner.dismiss();
        idle();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void destroyedActivityDoesNotShowAQueuedDialog() throws Exception {
        Dialog previous = ShadowDialog.getLatestDialog();
        onWorker(() -> SpinnerDialog.displayDialog(activity, "Conexão", "Preparando", true));

        controller.pause().stop().destroy();
        idle();

        assertSame(previous, ShadowDialog.getLatestDialog());
    }

    @Test
    public void closingOneActivityDoesNotDismissAnotherActivitysDialog() {
        ProgressDialog first = showAndGetDialog();
        try (ActivityController<TestActivity> other = Robolectric.buildActivity(TestActivity.class).setup()) {
            SpinnerDialog secondSpinner = SpinnerDialog.displayDialog(other.get(), "Outra", "Preparando", false);
            idle();
            ProgressDialog second = latestDialog();

            SpinnerDialog.closeDialogs(activity);
            idle();

            assertFalse(first.isShowing());
            assertTrue(second.isShowing());
            assertFalse(activity.isFinishing());
            secondSpinner.dismiss();
            idle();
        }
    }

    @Test
    public void userCancellationStillFinishesTheConnectionActivity() {
        showSpinner(true);
        ProgressDialog dialog = latestDialog();

        dialog.cancel();
        idle();

        assertTrue(activity.isFinishing());
        assertFalse(dialog.isShowing());
    }

    private SpinnerDialog showSpinner(boolean finishOnCancel) {
        SpinnerDialog spinner = SpinnerDialog.displayDialog(
                activity, "Estabelecendo Conexão", "Conectando com segurança", finishOnCancel);
        idle();
        return spinner;
    }

    private ProgressDialog showAndGetDialog() {
        showSpinner(false);
        return latestDialog();
    }

    private static ProgressDialog latestDialog() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        return (ProgressDialog) dialog;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void onWorker(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        thread.start();
        thread.join(5000);
        assertFalse("Worker did not finish", thread.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
