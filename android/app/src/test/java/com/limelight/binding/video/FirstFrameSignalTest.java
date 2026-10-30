package com.limelight.binding.video;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FirstFrameSignalTest {
    @Test public void onlyFirstRenderedFrameNotifiesAndCannotRearm() {
        FirstFrameSignal signal = new FirstFrameSignal();
        AtomicInteger renders = new AtomicInteger();
        signal.setListener(renders::incrementAndGet);
        assertEquals(0, renders.get());
        signal.onFrameRendered();
        signal.onFrameRendered();
        signal.setListener(renders::incrementAndGet);
        signal.onFrameRendered();
        assertEquals(1, renders.get());
    }

    @Test public void destructionDiscardsQueuedRenderedFrameAndListener() {
        FirstFrameSignal signal = new FirstFrameSignal();
        AtomicInteger renders = new AtomicInteger();
        signal.setListener(renders::incrementAndGet);
        signal.cancel();
        signal.cancel();
        signal.onFrameRendered();
        signal.setListener(renders::incrementAndGet);
        signal.onFrameRendered();
        assertEquals(0, renders.get());
    }
}
