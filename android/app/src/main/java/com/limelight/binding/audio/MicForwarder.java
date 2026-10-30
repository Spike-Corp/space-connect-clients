package com.limelight.binding.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

// Captures the device microphone and forwards it as raw PCM over UDP to the "Space Connect Mic
// Bridge" companion program running on the host PC (see /SpaceConnectMicBridge in the repo root).
// This is a separate side-channel from the Moonlight/GameStream protocol itself, which has no
// client-to-host audio channel - the companion program plays this audio into a virtual audio
// cable (e.g. VB-Audio Virtual Cable) so the host OS sees it as a real microphone.
public class MicForwarder {
    public static final int DEFAULT_PORT = 48100;

    public enum State { STOPPED, STARTING, RUNNING, STOPPING }

    public interface Listener {
        void onStateChanged(State state, boolean failed);
    }

    private static final class CaptureAttempt {
        final long generation;
        volatile boolean cancelled;
        volatile DatagramSocket socket;
        Thread thread;

        CaptureAttempt(long generation) {
            this.generation = generation;
        }
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final byte PROTOCOL_VERSION = 1;
    private static final byte[] MAGIC_HEADER = { 'S', 'C', 'M', 'B' };
    private static final int HEADER_SIZE = 12; // magic(4) + version(1) + channels(1) + reserved(2) + sampleRate(4)

    private final Context context;
    private final String hostAddress;
    private final int port;
    // Empty/null means "automatic" - let AudioRecord use the system default recording device.
    // Only actually used on API 23+ (see MicDeviceCompat); ignored entirely on older devices.
    private final String deviceId;

    private final Handler callbacks = new Handler(Looper.getMainLooper());
    private CaptureAttempt capture;
    private volatile State state = State.STOPPED;
    private volatile long generation;
    private volatile Listener listener;
    private volatile float currentLevel;

    public MicForwarder(Context context, String hostAddress, int port, String deviceId) {
        this.context = context.getApplicationContext();
        this.hostAddress = hostAddress;
        this.port = port;
        this.deviceId = deviceId;
    }

    public synchronized void start() {
        if (capture != null) return;
        CaptureAttempt attempt = new CaptureAttempt(++generation);
        capture = attempt;
        currentLevel = 0f;
        state = State.STARTING;
        notifyState(attempt, State.STARTING, false);
        attempt.thread = new Thread(() -> runCaptureLoop(attempt), "MicForwarder");
        attempt.thread.start();
    }

    public void stop() {
        CaptureAttempt attempt;
        synchronized (this) {
            attempt = capture;
            if (attempt == null || attempt.cancelled) return;
            attempt.cancelled = true;
            state = State.STOPPING;
            currentLevel = 0f;
            notifyState(attempt, State.STOPPING, false);
        }
        if (attempt.socket != null) attempt.socket.close();
        attempt.thread.interrupt();
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public State getState() {
        return state;
    }

    public void setListener(Listener value) {
        callbacks.removeCallbacksAndMessages(null);
        listener = value;
    }

    private void notifyState(CaptureAttempt attempt, State expected, boolean failed) {
        callbacks.post(() -> {
            Listener current = listener;
            if (current != null && generation == attempt.generation && state == expected) {
                current.onStateChanged(expected, failed);
            }
        });
    }

    // Normalized microphone input level, roughly 0.0 (silence) to 1.0 (loud) - updated on every
    // captured buffer. Used by the in-stream overlay to show a live "is my mic picking up sound"
    // indicator. Returns 0 if forwarding isn't currently running.
    public float getCurrentLevel() {
        return isRunning() ? currentLevel : 0f;
    }

    // Caller (Game.java) is responsible for checking/requesting the RECORD_AUDIO runtime
    // permission before calling start() - this class assumes it has already been granted.
    @SuppressLint("MissingPermission")
    private void runCaptureLoop(CaptureAttempt attempt) {
        AudioRecord audioRecord = null;
        boolean failed = false;
        try {
            if (attempt.cancelled) return;
            InetAddress address = InetAddress.getByName(hostAddress);
            if (attempt.cancelled) return;
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferSize <= 0) throw new IllegalStateException("AudioRecord buffer size unavailable");
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord failed to initialize");
            }

            // Picking the wrong input device (e.g. a webcam mic instead of a headset mic) is a
            // common cause of "mic forwarding does nothing" - honor the user's explicit choice
            // when one was made and the device is still connected. Falls back to whatever
            // AudioRecord's default device selection would have done otherwise (i.e. the same
            // behavior as before this feature existed) if no id was saved, or the saved device
            // is no longer connected, or we're on a pre-Marshmallow device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                MicDeviceCompat.applyPreferredDevice(context, audioRecord, deviceId);
            }

            attempt.socket = new DatagramSocket();
            if (attempt.cancelled) return;

            byte[] audioBuffer = new byte[minBufferSize];
            byte[] packetBuffer = new byte[HEADER_SIZE + minBufferSize];

            System.arraycopy(MAGIC_HEADER, 0, packetBuffer, 0, 4);
            packetBuffer[4] = PROTOCOL_VERSION;
            packetBuffer[5] = (byte) CHANNELS;
            packetBuffer[6] = 0;
            packetBuffer[7] = 0;
            writeIntLE(packetBuffer, 8, SAMPLE_RATE);

            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not start recording");
            }
            synchronized (this) {
                if (attempt.cancelled) return;
                state = State.RUNNING;
                notifyState(attempt, State.RUNNING, false);
            }

            while (!attempt.cancelled) {
                int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length, AudioRecord.READ_NON_BLOCKING);
                if (bytesRead < 0) throw new IOException("AudioRecord read failed: " + bytesRead);
                if (bytesRead == 0) {
                    Thread.sleep(10);
                    continue;
                }

                currentLevel = computeLevel(audioBuffer, bytesRead);

                System.arraycopy(audioBuffer, 0, packetBuffer, HEADER_SIZE, bytesRead);
                DatagramPacket packet = new DatagramPacket(packetBuffer, HEADER_SIZE + bytesRead, address, port);
                attempt.socket.send(packet);
            }
        }
        catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
            failed = !attempt.cancelled;
            if (failed) LimeLog.warning("MicForwarder: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed = !attempt.cancelled;
            if (failed) LimeLog.warning("MicForwarder: capture interrupted");
        }
        finally {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                }
                catch (IllegalStateException e) {
                    LimeLog.warning("MicForwarder: recorder was already stopped");
                }
                audioRecord.release();
            }
            if (attempt.socket != null) attempt.socket.close();
            synchronized (this) {
                if (capture == attempt) {
                    capture = null;
                    state = State.STOPPED;
                    currentLevel = 0f;
                    notifyState(attempt, State.STOPPED, failed && !attempt.cancelled);
                }
            }
        }
    }

    private static void writeIntLE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // Simple RMS-based level meter over a PCM16LE buffer, normalized to ~0.0-1.0. Speech RMS is
    // typically well below full-scale, so a gain multiplier is applied to give a visually useful
    // range on the overlay's bar indicator.
    private static float computeLevel(byte[] buffer, int length) {
        int sampleCount = length / 2;
        if (sampleCount == 0) {
            return 0f;
        }

        long sumSquares = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }

        double rms = Math.sqrt((double) sumSquares / sampleCount);
        float normalized = (float) (rms / 32768.0) * 6f;
        return Math.max(0f, Math.min(1f, normalized));
    }
}
