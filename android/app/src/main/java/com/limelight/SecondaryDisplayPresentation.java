/**
 * Ported from Artemis: simple android.app.Presentation subclass used to show the stream
 * on a secondary/external display (see Game.showSecondScreen()). The existing StreamView
 * surface is moved into this presentation's window rather than creating a new render target.
 */

package com.limelight;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.ui.StreamView;

public class SecondaryDisplayPresentation extends Presentation {

    private FrameLayout view;

    public SecondaryDisplayPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = (FrameLayout) View.inflate(getContext(), R.layout.activity_game_display, null);
        setContentView(view);
    }

    public void addView(StreamView streamView) {
        view.addView(streamView);
    }

    @Override
    protected void onStop() {
        super.onStop();
        view.removeAllViews();
    }
}
