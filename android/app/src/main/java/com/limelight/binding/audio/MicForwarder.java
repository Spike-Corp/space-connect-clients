package com.limelight.binding.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

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

    private Thread captureThread;
    private volatile boolean running;
    private volatile float currentLevel;

    public MicForwarder(Context context, String hostAddress, int port, String deviceId) {
        this.context = context.getApplicationContext();
        this.hostAddress = hostAddress;
        this.port = port;
        this.deviceId = deviceId;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runCaptureLoop();
            }
        }, "MicForwarder");
        captureThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (captureThread != null) {
            try {
                captureThread.join(500);
            }
            catch (InterruptedException ignored) {}
            captureThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    // Normalized microphone input level, roughly 0.0 (silence) to 1.0 (loud) - updated on every
    // captured buffer. Used by the in-stream overlay to show a live "is my mic picking up sound"
    // indicator. Returns 0 if forwarding isn't currently running.
    public float getCurrentLevel() {
        return running ? currentLevel : 0f;
    }

    // Caller (Game.java) is responsible for checking/requesting the RECORD_AUDIO runtime
    // permission before calling start() - this class assumes it has already been granted.
    @SuppressLint("MissingPermission")
    private void runCaptureLoop() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            LimeLog.warning("MicForwarder: unable to determine AudioRecord buffer size");
            running = false;
            return;
        }

        AudioRecord audioRecord = null;
        DatagramSocket socket = null;
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                LimeLog.warning("MicForwarder: AudioRecord failed to initialize");
                return;
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

            InetAddress address = InetAddress.getByName(hostAddress);
            socket = new DatagramSocket();

            byte[] audioBuffer = new byte[minBufferSize];
            byte[] packetBuffer = new byte[HEADER_SIZE + minBufferSize];

            System.arraycopy(MAGIC_HEADER, 0, packetBuffer, 0, 4);
            packetBuffer[4] = PROTOCOL_VERSION;
            packetBuffer[5] = (byte) CHANNELS;
            packetBuffer[6] = 0;
            packetBuffer[7] = 0;
            writeIntLE(packetBuffer, 8, SAMPLE_RATE);

            audioRecord.startRecording();

            while (running) {
                int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (bytesRead <= 0) {
                    continue;
                }

                currentLevel = computeLevel(audioBuffer, bytesRead);

                System.arraycopy(audioBuffer, 0, packetBuffer, HEADER_SIZE, bytesRead);
                DatagramPacket packet = new DatagramPacket(packetBuffer, HEADER_SIZE + bytesRead, address, port);
                socket.send(packet);
            }
        }
        catch (IOException e) {
            LimeLog.warning("MicForwarder: " + e.getMessage());
        }
        finally {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                }
                catch (IllegalStateException ignored) {}
                audioRecord.release();
            }
            if (socket != null) {
                socket.close();
            }
            running = false;
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
