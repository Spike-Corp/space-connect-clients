package com.limelight.binding.video;

/** One-shot, lifetime-scoped signal fed exclusively by MediaCodec's rendered-frame callback. */
public final class FirstFrameSignal {
    private Runnable listener;
    private boolean rendered;
    private boolean cancelled;

    public synchronized void setListener(Runnable listener) {
        if (!cancelled && !rendered) this.listener = listener;
    }

    public synchronized void onFrameRendered() {
        if (cancelled || rendered) return;
        rendered = true;
        Runnable callback = listener;
        listener = null;
        if (callback != null) callback.run();
    }

    public synchronized void cancel() {
        cancelled = true;
        listener = null;
    }
}
