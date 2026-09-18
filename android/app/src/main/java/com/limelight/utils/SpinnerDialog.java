package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;

public class SpinnerDialog implements Runnable,OnCancelListener {
    private final String title;
    private final String message;
    private final Activity activity;
    private ProgressDialog progress;
    private final boolean finish;
    // Marca que dismiss() foi chamado mesmo antes do diálogo ser criado — sem isso,
    // um dismiss() corrido antes do run() de criação ACABAVA CRIANDO e mostrando o
    // diálogo (efeito inverso), e um setMessage() com progress==null dava NPE.
    private boolean dismissed = false;

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
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity)
    {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    // progress pode ser null se o run() de criação ainda não executou
                    // na UI thread (closeDialogs chamado antes) — NPE derrubava o app.
                    if (dialog.progress != null && dialog.progress.isShowing()) {
                        try { dialog.progress.dismiss(); } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        }
    }

    public void dismiss()
    {
        dismissed = true;
        // Running again with progress != null will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progress != null && !dismissed) {
                    progress.setMessage(message);
                }
            }
        });
    }

    @Override
    public void run() {

        // If we're dying, don't bother doing anything. isDestroyed (API 17+) cobre o
        // caso em que a activity já foi destruída mas isFinishing ainda é false —
        // show() nesse estado lança BadTokenException e derruba o app (visto em
        // Android 13/14, OneUI 6.x, ao encerrar a tela de stream no meio do diálogo).
        if (activity.isFinishing() || activity.isDestroyed() || dismissed) {
            return;
        }

        if (progress == null)
        {
            progress = new ProgressDialog(activity);

            progress.setTitle(title);
            progress.setMessage(message);
            progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progress.setOnCancelListener(this);

            // If we want to finish the activity when this is killed, make it cancellable
            if (finish)
            {
                progress.setCancelable(true);
                progress.setCanceledOnTouchOutside(false);
            }
            else
            {
                progress.setCancelable(false);
            }

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                try {
                    progress.show();
                } catch (RuntimeException e) {
                    // BadTokenException/IllegalStateException se a activity morreu entre o
                    // check acima e o show() — remove e segue sem crashar o stream.
                    rundownDialogs.remove(this);
                    progress = null;
                    return;
                }
            }
        }
        else
        {
            synchronized (rundownDialogs) {
                if (rundownDialogs.remove(this) && progress.isShowing()) {
                    try { progress.dismiss(); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish();
    }
}
