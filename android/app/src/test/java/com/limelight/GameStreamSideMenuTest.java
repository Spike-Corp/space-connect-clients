package com.limelight;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.limelight.binding.audio.MicForwarder;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.session.SessionLoadingLifecycleTest;
import com.limelight.ui.StreamSideMenu;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR-w640dp-h360dp-land-mdpi",
        shadows = SessionLoadingLifecycleTest.NativeBridge.class)
public class GameStreamSideMenuTest {
    private final List<Game> games = new ArrayList<>();

    private Game createUi(Bundle saved) {
        // Build only the real Activity's UI/input bridge, without launching a remote session.
        Game game = Robolectric.buildActivity(Game.class).get();
        games.add(game);
        game.setTheme(R.style.StreamTheme);
        game.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        game.setContentView(R.layout.activity_game);
        ReflectionHelpers.setField(game, "connected", true);
        ReflectionHelpers.setField(game, "hostAddress", "127.0.0.1");
        ReflectionHelpers.setField(game, "prefConfig", PreferenceConfiguration.readPreferences(game));
        ReflectionHelpers.setField(game, "gameMenu", new GameMenu(game));
        ReflectionHelpers.callInstanceMethod(game, "initializeStreamSideMenu", ClassParameter.from(Bundle.class, saved));
        View decor = game.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 640, 360);
        return game;
    }

    private StreamSideMenu menu(Game game) {
        return ReflectionHelpers.getField(game, "streamSideMenu");
    }

    @After public void cleanup() throws Exception {
        for (Game game : games) {
            ReflectionHelpers.callInstanceMethod(game, "stopConnection");
            menu(game).dispose();
            MicForwarder mic = ReflectionHelpers.getField(game, "micForwarder");
            if (mic != null) awaitState(mic, MicForwarder.State.STOPPED);
        }
        ShadowAudioRecord.clearSource();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void actualGameDispatchKeepsRevealGestureOnBackground() {
        Game game = createUi(null);
        AtomicInteger events = new AtomicInteger();
        game.findViewById(R.id.backgroundTouchView).setOnTouchListener((v, event) -> {
            events.incrementAndGet();
            return true;
        });
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + action * 20L, action, 616, 48, 0);
            game.dispatchTouchEvent(event);
            event.recycle();
        }
        assertEquals(2, events.get());
        assertFalse(menu(game).isOpen());
    }

    @Test public void savedOpenStateRestoresAndBackClosesMenuWithoutFinishingGame() {
        Game game = createUi(null);
        menu(game).setOpen(true, false);
        Bundle saved = new Bundle();
        ReflectionHelpers.callInstanceMethod(game, "onSaveInstanceState", ClassParameter.from(Bundle.class, saved));
        Game restored = createUi(saved);
        assertTrue(menu(restored).isOpen());
        assertTrue(restored.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        assertTrue(restored.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
        assertFalse(menu(restored).isOpen());
        assertFalse(restored.isFinishing());
    }

    @Test public void deniedPermissionReenablesMicrophoneWithoutStartingCapture() {
        Game game = createUi(null);
        shadowOf(game.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        Button micButton = menu(game).findViewById(R.id.streamSideMicrophone);
        assertFalse(micButton.isEnabled());
        game.onRequestPermissionsResult(4200, new String[] {Manifest.permission.RECORD_AUDIO},
                new int[] {PackageManager.PERMISSION_DENIED});
        assertTrue(micButton.isEnabled());
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
        assertEquals(game.getString(R.string.game_mic_forward_denied), ShadowToast.getTextOfLatestToast());
    }

    @Test public void latePermissionGrantCannotRestartAnEndedSession() {
        Game game = createUi(null);
        shadowOf(game.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        game.onRequestPermissionsResult(4200, new String[] {Manifest.permission.RECORD_AUDIO},
                new int[] {PackageManager.PERMISSION_GRANTED});
        assertNull(ReflectionHelpers.getField(game, "micForwarder"));
    }

    @Test public void menuAndStatisticsObserveSameCaptureFailure() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            @Override public int readInByteArray(byte[] target, int offset, int size, boolean blocking) {
                return fail.get() ? AudioRecord.ERROR_DEAD_OBJECT : 0;
            }
        });
        Game game = createUi(null);
        shadowOf(game.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(game, "toggleMicForwarding");
        MicForwarder mic = ReflectionHelpers.getField(game, "micForwarder");
        awaitState(mic, MicForwarder.State.RUNNING);
        shadowOf(Looper.getMainLooper()).idle();
        Button button = menu(game).findViewById(R.id.streamSideMicrophone);
        TextView stats = game.findViewById(R.id.perfOverlayExtra);
        assertEquals(game.getString(R.string.stream_side_mic_on), button.getContentDescription());
        assertFalse(stats.getText().toString().contains(game.getString(R.string.perf_overlay_mic_inactive)));
        fail.set(true);
        awaitState(mic, MicForwarder.State.STOPPED);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(game.getString(R.string.stream_side_mic_off), button.getContentDescription());
        assertTrue(stats.getText().toString().contains(game.getString(R.string.perf_overlay_mic_inactive)));
        assertEquals(game.getString(R.string.stream_side_mic_failed), ShadowToast.getTextOfLatestToast());
    }

    private static void awaitState(MicForwarder mic, MicForwarder.State state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (mic.getState() != state && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(state, mic.getState());
    }
}
