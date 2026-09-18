package com.limelight.utils;

import java.util.ArrayList;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.view.WindowManager;

import com.limelight.LimeLog;

public class SpinnerDialog implements Runnable, OnCancelListener {
    private final String title;
    private String message;
    private final Activity activity;
    private ProgressDialog progress;
    private final boolean finish;
    // Completion may arrive from the connection thread before the UI creates the window.
    private volatile boolean dismissed;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.progress = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish)
    {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        synchronized (rundownDialogs) {
            rundownDialogs.add(spinner);
        }
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        ArrayList<SpinnerDialog> dialogsToClose = new ArrayList<>();
        synchronized (rundownDialogs) {
            for (SpinnerDialog dialog : rundownDialogs) {
                if (dialog.activity == activity) {
                    dialogsToClose.add(dialog);
                }
            }
        }
        for (SpinnerDialog dialog : dialogsToClose) {
            dialog.dismiss();
        }
    }

    public void dismiss()
    {
        dismissed = true;
        activity.runOnUiThread(this::dismissOnUiThread);
    }

    private void dismissOnUiThread()
    {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }
        ProgressDialog dialog = progress;
        progress = null;
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (IllegalArgumentException ignored) {
                // Android may already have detached the Activity's window.
            }
        }
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (dismissed) {
                    return;
                }
                SpinnerDialog.this.message = message;
                if (progress != null) {
                    progress.setMessage(message);
                }
            }
        });
    }

    @Override
    public void run() {
        if (activity.isFinishing() || activity.isDestroyed() || dismissed) {
            dismiss();
            return;
        }

        if (progress != null) {
            return;
        }

        progress = new ProgressDialog(activity);
        progress.setTitle(title);
        progress.setMessage(message);
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progress.setOnCancelListener(this);
        progress.setCancelable(finish);
        progress.setCanceledOnTouchOutside(false);

        try {
            progress.show();
        } catch (WindowManager.BadTokenException | IllegalStateException e) {
            LimeLog.warning("Unable to show progress dialog: " + e.getClass().getSimpleName());
            dismiss();
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        dismiss();
        if (finish) {
            activity.finish();
        }
    }
}
