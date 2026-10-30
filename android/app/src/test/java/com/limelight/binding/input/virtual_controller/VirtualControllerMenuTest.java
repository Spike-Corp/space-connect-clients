package com.limelight.binding.input.virtual_controller;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.limelight.R;
import com.limelight.session.SessionLoadingLifecycleTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w640dp-h360dp-land-mdpi",
        shadows = SessionLoadingLifecycleTest.NativeBridge.class)
public class VirtualControllerMenuTest {
    private ActivityController<Activity> activity;
    private FrameLayout root;
    private VirtualController controller;
    private SharedPreferences saved;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup();
        activity.get().setTheme(R.style.StreamTheme);
        root = new FrameLayout(activity.get());
        activity.get().setContentView(root);
        controller = new VirtualController(null, root, activity.get());
        saved = activity.get().getSharedPreferences("OSC", 0);
    }

    @After public void cleanup() {
        controller.dispose();
        activity.pause().stop().destroy();
    }

    private Rect bounds(VirtualControllerElement element) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) element.getLayoutParams();
        return new Rect(p.leftMargin, p.topMargin, p.leftMargin + p.width, p.topMargin + p.height);
    }

    private VirtualControllerElement element(int id) {
        return controller.getElements().stream().filter(value -> value.elementId == id).findFirst().orElseThrow();
    }

    private Button configure() {
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof Button) return (Button) root.getChildAt(i);
        }
        throw new AssertionError("Configuration button missing");
    }

    @Test public void defaultFitsReservedStripAndHiddenRefreshDoesNotResurrectControls() {
        List<Boolean> states = new ArrayList<>();
        controller.setStateListener(() -> states.add(controller.isVirtualGamepadVisible()));
        controller.refreshLayout();
        controller.show();
        assertTrue(controller.isVirtualGamepadVisible());
        for (VirtualControllerElement element : controller.getElements()) {
            assertTrue(controller.getUsableBounds().contains(bounds(element)));
        }
        controller.hide();
        controller.refreshLayout();
        assertFalse(controller.isVirtualGamepadVisible());
        assertEquals(Boolean.FALSE, states.get(states.size() - 1));
        for (VirtualControllerElement element : controller.getElements()) {
            assertEquals(View.INVISIBLE, element.getVisibility());
        }
    }

    @Test public void customRightStickMovesNeighborWithoutOverwritingSavedProfile() {
        String stick = "{\"LEFT\":490,\"TOP\":210,\"WIDTH\":130,\"HEIGHT\":130}";
        String start = "{\"LEFT\":415,\"TOP\":320,\"WIDTH\":60,\"HEIGHT\":35}";
        saved.edit().putString("13", stick).putString("11", start).commit();
        controller.refreshLayout();
        Rect rightStick = bounds(element(VirtualControllerElement.EID_RS));
        Rect startButton = bounds(element(VirtualControllerElement.EID_START));
        assertTrue(controller.getUsableBounds().contains(rightStick));
        assertTrue(controller.getUsableBounds().contains(startButton));
        assertFalse(Rect.intersects(rightStick, startButton));
        assertEquals(stick, saved.getString("13", null));
        assertEquals(start, saved.getString("11", null));
    }

    @Test public void editorClampsMoveAndResizeToSafeAreaAndOnlyThenSaves() {
        controller.refreshLayout();
        controller.show();
        controller.setSafeArea(new Rect(20, 10, 610, 350), 48);
        configure().performClick();
        assertEquals(VirtualController.ControllerMode.MoveButtons, controller.getControllerMode());
        VirtualControllerElement button = element(VirtualControllerElement.EID_B);
        button.moveElement(0, 0, 5000, 5000);
        assertTrue(controller.getUsableBounds().contains(bounds(button)));
        configure().performClick();
        button.startSize_x = 50;
        button.startSize_y = 50;
        button.resizeElement(0, 0, 5000, 5000);
        assertTrue(controller.getUsableBounds().contains(bounds(button)));
        configure().performClick();
        assertEquals(VirtualController.ControllerMode.Active, controller.getControllerMode());
        assertNotNull(saved.getString("7", null));
    }

    @Test public void impossibleLegacyProfileRequiresEditorWithoutDeletingIt() {
        String oversized = "{\"LEFT\":0,\"TOP\":0,\"WIDTH\":1000,\"HEIGHT\":1000}";
        saved.edit().putString("13", oversized).commit();
        controller.refreshLayout();
        controller.show();
        assertEquals(VirtualController.ControllerMode.MoveButtons, controller.getControllerMode());
        configure().performClick();
        configure().performClick();
        assertEquals(VirtualController.ControllerMode.ResizeButtons, controller.getControllerMode());
        assertEquals(oversized, saved.getString("13", null));
        VirtualControllerElement stick = element(VirtualControllerElement.EID_RS);
        stick.startSize_x = 130;
        stick.startSize_y = 130;
        stick.resizeElement(0, 0, 0, 0);
        configure().performClick();
        assertEquals(VirtualController.ControllerMode.Active, controller.getControllerMode());
    }
}
