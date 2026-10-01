package com.limelight.binding.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import com.limelight.LimeLog;

// Medidor de nível do microfone estilo Discord: captura 100% local (NADA é
// enviado pra rede) só pra mostrar a barrinha de sensibilidade nas
// configurações. O usuário confirma na hora que o mic certo está captando,
// sem precisar entrar numa sessão pra descobrir que estava mudo/errado.
public class MicLevelTester {

    public interface LevelListener {
        void onLevel(float level); // 0.0 (silêncio) a 1.0 (alto)
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private final String deviceId; // null/vazio = dispositivo padrão
    private final LevelListener listener;

    private volatile boolean running;
    private Thread thread;

    public MicLevelTester(Context context, String deviceId, LevelListener listener) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runCaptureLoop, "MicLevelTester");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    public boolean isRunning() { return running; }

    // O chamador é responsável pela permissão RECORD_AUDIO (a tela de settings
    // já pede quando liga o forwarding).
    @SuppressLint("MissingPermission")
    private void runCaptureLoop() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            LimeLog.warning("MicLevelTester: unable to determine AudioRecord buffer size");
            running = false;
            if (listener != null) listener.onError("Microfone indisponível neste aparelho");
            return;
        }

        AudioRecord audioRecord = null;
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (listener != null) listener.onError("Não consegui abrir o microfone");
                return;
            }

            // Respeita o dispositivo escolhido nas configurações (mesmo do forwarding)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                MicDeviceCompat.applyPreferredDevice(context, audioRecord, deviceId);
            }

            byte[] audioBuffer = new byte[minBufferSize];
            audioRecord.startRecording();

            while (running) {
                int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (bytesRead <= 0) continue;
                if (listener != null) listener.onLevel(computeLevel(audioBuffer, bytesRead));
            }
        } catch (Exception e) {
            LimeLog.warning("MicLevelTester: capture failed: " + e.getMessage());
            if (listener != null) listener.onError("Falha ao capturar: " + e.getMessage());
        } finally {
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                audioRecord.release();
            }
            running = false;
        }
    }

    // Mesma curva RMS do MicForwarder — o nível mostrado aqui bate com o que a
    // VM vai receber.
    private static float computeLevel(byte[] buffer, int length) {
        int sampleCount = length / 2;
        if (sampleCount <= 0) return 0f;
        double sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            int lo = buffer[i * 2] & 0xFF;
            int hi = buffer[i * 2 + 1];
            short sample = (short) ((hi << 8) | lo);
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }
        float rms = (float) Math.sqrt(sumSquares / sampleCount);
        return Math.min(1.0f, Math.max(0.0f, rms * 6.0f));
    }
}
