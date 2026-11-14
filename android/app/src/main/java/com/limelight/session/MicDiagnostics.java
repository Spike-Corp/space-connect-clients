package com.limelight.session;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MicDiagnostics {
    public enum Phase {
        ACTIVITY_CREATE, ACTIVITY_STOP, ACTIVITY_DESTROY, MIC_BUTTON,
        PERMISSION_REQUESTED, PERMISSION_RESULT, HOST_PREPARING, HOST_READY,
        CAPTURE_REQUESTED, RECORDER_CREATING, RECORDER_READY, RECORDER_STARTING,
        CAPTURING, FIRST_AUDIO_SENT, CAPTURE_FAILED, STOP_REQUESTED, STOPPED, STREAM_STOP
    }

    private static volatile MicDiagnostics active;
    private final File directory;
    private final ArrayDeque<String> phases = new ArrayDeque<>();

    private MicDiagnostics(Context context) {
        directory = directory(context);
        try {
            for (String line : readFile(directory, "phases").split("\n"))
                if (!line.isEmpty()) phases.addLast(line);
        } catch (IOException | SecurityException error) {
            storageError(error);
        }
    }

    public static synchronized void install(Context context) {
        if (!BuildConfig.MIC_DIAGNOSTICS || active != null) return;
        MicDiagnostics diagnostics = new MicDiagnostics(context);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        active = diagnostics;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                diagnostics.crash(error);
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public static void record(Phase phase) {
        MicDiagnostics diagnostics = active;
        if (diagnostics != null) diagnostics.phase(phase);
    }

    private synchronized void phase(Phase phase) {
        phases.addLast(System.currentTimeMillis() + " " + phase.name());
        while (phases.size() > 24) phases.removeFirst();
        write("phases", android.text.TextUtils.join("\n", phases));
    }

    private void crash(Throwable error) {
        StringBuilder text = new StringBuilder("UNCAUGHT at=" + System.currentTimeMillis()
                + " build=" + BuildConfig.VERSION_NAME + "\n");
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = error; cause != null && causes.size() < 4 && !causes.contains(cause); cause = cause.getCause())
            causes.add(cause);
        for (int i = causes.size() - 1; i >= 0; i--) {
            Throwable cause = causes.get(i);
            text.append(cause.getClass().getName()).append('\n');
            StackTraceElement[] stack = cause.getStackTrace();
            for (int frame = 0; frame < Math.min(6, stack.length); frame++)
                text.append(stack[frame].getClassName()).append('.').append(stack[frame].getMethodName())
                        .append(':').append(stack[frame].getLineNumber()).append('\n');
        }
        // Exception messages can contain tokens, URLs or application data.
        write("crash", text.toString());
    }

    private synchronized void write(String name, String text) {
        AtomicFile file = new AtomicFile(new File(directory, name));
        FileOutputStream output = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Diagnostic directory unavailable");
            output = file.startWrite();
            output.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException | SecurityException error) {
            if (output != null) file.failWrite(output);
            storageError(error);
        }
    }

    public static String report(Context context) {
        if (!BuildConfig.MIC_DIAGNOSTICS) return "";
        StringBuilder text = new StringBuilder("MIC_DIAGNOSTIC build=" + BuildConfig.VERSION_NAME + "\n");
        File directory = directory(context);
        try {
            String[] phases = readFile(directory, "phases").split("\n");
            for (int i = Math.max(0, phases.length - 5); i < phases.length; i++)
                if (!phases[i].isEmpty()) text.append(phases[i]).append('\n');
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) text.append(ExitHistory.read(context));
            text.append(readFile(directory, "crash"));
        } catch (IOException | SecurityException error) {
            storageError(error);
            text.append("DIAGNOSTIC_STORAGE_ERROR\n");
        }
        return text.toString();
    }

    public static Map<String, Object> reportContext(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = report(context).split("\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            if (result.size() == 19) { result.put("mic_diag_truncated", true); break; }
            result.put(String.format(Locale.ROOT, "mic_diag_%02d", result.size() + 1),
                    line.length() > 200 ? line.substring(0, 197) + "..." : line);
        }
        return result;
    }

    private static File directory(Context context) {
        return new File(context.getNoBackupFilesDir(), "mic-diagnostic");
    }

    private static String readFile(File directory, String name) throws IOException {
        if (directory.exists() && !directory.isDirectory()) throw new IOException("Invalid diagnostic directory");
        File file = new File(directory, name);
        if (!file.exists() && !new File(directory, name + ".bak").exists()) return "";
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new String(readLimited(input, 32768), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Diagnostic size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void storageError(Exception error) {
        LimeLog.warning("Microphone diagnostic unavailable: " + error.getClass().getSimpleName());
    }

    private static final class ExitHistory {
        static String read(Context context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "PROCESS_EXIT_UNAVAILABLE\n";
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "PROCESS_EXIT_UNAVAILABLE\n";
            try {
                for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1)) {
                    int reason = exit.getReason();
                    String header = "PREVIOUS_EXIT at=" + exit.getTimestamp() + " reason=" + reason
                            + " status=" + exit.getStatus() + " (previous build not supplied by Android)\n";
                    if (reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return header;
                    try (InputStream trace = exit.getTraceInputStream()) {
                        return header + (trace == null ? "NATIVE_TRACE_UNAVAILABLE\n"
                                : NativeCrashTrace.read(readLimited(trace, 8 * 1024 * 1024)));
                    } catch (IOException error) {
                        storageError(error);
                        return header + "NATIVE_TRACE_UNAVAILABLE " + error.getClass().getSimpleName() + "\n";
                    }
                }
                return "";
            } catch (SecurityException error) {
                storageError(error);
                return "PROCESS_EXIT_UNAVAILABLE SecurityException\n";
            }
        }
    }
}
