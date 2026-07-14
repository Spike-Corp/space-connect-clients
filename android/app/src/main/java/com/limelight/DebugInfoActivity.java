package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.Sensor;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Ported (simplified) from Artemis (github.com/MobinYengejehi/Artemis, a moonlight-android
 * fork)'s DebugInfoActivity - lets users inspect Android/kernel version and connected gamepad
 * details (vendor/product ID, vibration support, sensor availability), and test device/gamepad
 * vibration. Purely a diagnostic screen - doesn't touch any streaming code paths.
 */
public class DebugInfoActivity extends Activity implements View.OnClickListener {

    private TextView tx_gamepad_info;
    private Vibrator vibrator;
    private Vibrator vibratorOnline;
    private Button bt_vibrator_value;
    private final List<InputDevice> ids = new ArrayList<>();
    private int simulatedAmplitude = 220;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_info);

        tx_gamepad_info = findViewById(R.id.tx_game_pad_info);
        TextView tx_content = findViewById(R.id.tx_content);
        bt_vibrator_value = findViewById(R.id.bt_vibrator_value);

        findViewById(R.id.bt_vibrator).setOnClickListener(this);
        findViewById(R.id.bt_update_gamepad).setOnClickListener(this);
        findViewById(R.id.bt_vibrator_gamepad).setOnClickListener(this);
        bt_vibrator_value.setOnClickListener(this);
        findViewById(R.id.bt_vibrator_cancel).setOnClickListener(this);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String kernelVersion = System.getProperty("os.version");
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.debug_info_android_version)).append(Build.VERSION.RELEASE);
        sb.append("\t").append(getString(R.string.debug_info_api_version)).append(Build.VERSION.SDK_INT);
        sb.append("\n").append(getString(R.string.debug_info_kernel_version)).append(kernelVersion);
        sb.append("\n").append(getString(R.string.debug_info_brand_model)).append(Build.MANUFACTURER).append("\t-\t").append(Build.MODEL);
        tx_content.setText(sb.toString());

        boolean hasVibrator = vibrator.hasVibrator();
        String content = hasVibrator ? getString(R.string.debug_info_has_vibration_motor) : getString(R.string.debug_info_no_vibration_motor);
        ((Button) findViewById(R.id.bt_vibrator)).setText(getString(R.string.debug_info_test_device_vibration) + " (" + content + ")");

        showSimulatedAmplitude();
        updateGamePad();
    }

    private void showSimulatedAmplitude() {
        bt_vibrator_value.setText(getString(R.string.debug_info_vibration_amplitude) + " (" + simulatedAmplitude + ")");
    }

    private void cancelRumble() {
        if (vibratorOnline != null) {
            vibratorOnline.cancel();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();

        if (id == R.id.bt_vibrator_cancel) {
            cancelRumble();
            return;
        }

        if (id == R.id.bt_vibrator) {
            String[] titles = new String[]{getString(R.string.debug_info_simple_vibration), getString(R.string.debug_info_continuous_hd_vibration)};
            new AlertDialog.Builder(this).setItems(titles, (dialog, which) -> {
                dialog.dismiss();
                if (which == 0) {
                    vibrator.vibrate(1000);
                }
                else {
                    rumble(vibrator);
                }
            }).setTitle(getString(R.string.debug_info_please_choose)).create().show();
            return;
        }

        if (id == R.id.bt_vibrator_gamepad) {
            if (ids.isEmpty()) {
                Toast.makeText(this, getString(R.string.debug_info_no_gamepad_detected), Toast.LENGTH_LONG).show();
                return;
            }
            String[] names = new String[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                names[i] = ids.get(i).getName();
            }
            new AlertDialog.Builder(this).setItems(names, (dialog, which) -> {
                dialog.dismiss();
                InputDevice dev = ids.get(which);
                if (!dev.getVibrator().hasVibrator()) {
                    Toast.makeText(this, getString(R.string.debug_info_no_vibrator), Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] titles = new String[]{getString(R.string.debug_info_simple_vibration), getString(R.string.debug_info_continuous_hd_vibration)};
                new AlertDialog.Builder(this).setItems(titles, (dialog2, which2) -> {
                    dialog2.dismiss();
                    if (which2 == 0) {
                        dev.getVibrator().vibrate(1000);
                    }
                    else {
                        cancelRumble();
                        vibratorOnline = dev.getVibrator();
                        rumble(vibratorOnline);
                    }
                }).setTitle(getString(R.string.debug_info_please_choose)).create().show();
            }).setTitle(getString(R.string.debug_info_please_choose)).create().show();
            return;
        }

        if (id == R.id.bt_update_gamepad) {
            updateGamePad();
            return;
        }

        if (id == R.id.bt_vibrator_value) {
            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(255);
            seekBar.setProgress(simulatedAmplitude);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    simulatedAmplitude = progress;
                    showSimulatedAmplitude();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.debug_info_set_amplitude))
                    .setView(seekBar)
                    .create().show();
        }
    }

    private void rumble(Vibrator vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{1000}, new int[]{simulatedAmplitude}, 0));
        }
        else {
            long pwmPeriod = 20;
            long onTime = (long) ((simulatedAmplitude / 255.0) * pwmPeriod);
            long offTime = pwmPeriod - onTime;
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build();
            vibrator.vibrate(new long[]{0, onTime, offTime}, 0, audioAttributes);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vibratorOnline != null) {
            vibratorOnline.cancel();
        }
    }

    private static InputDevice.MotionRange getMotionRangeForJoystickAxis(InputDevice dev, int axis) {
        InputDevice.MotionRange range = dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null) {
            range = dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD);
        }
        return range;
    }

    private void updateGamePad() {
        ids.clear();
        StringBuilder sb = new StringBuilder("\n");

        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(deviceId);
            if (dev == null) {
                continue;
            }

            int sources = dev.getSources();
            boolean looksLikeGamepad = ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                    ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
            if (!looksLikeGamepad) {
                continue;
            }
            if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) == null ||
                    getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) == null) {
                continue;
            }

            ids.add(dev);
            sb.append(getString(R.string.debug_info_name)).append(dev.getName()).append("\n");
            sb.append(getString(R.string.debug_info_sensors));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                String sensor = "";
                if (dev.getSensorManager().getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                    sensor += getString(R.string.debug_info_accelerometer);
                }
                if (dev.getSensorManager().getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                    sensor += getString(R.string.debug_info_gyroscope);
                }
                sb.append(sensor.isEmpty() ? getString(R.string.debug_info_no_relevant_driver) : sensor);
            }
            else {
                sb.append(getString(R.string.debug_info_no_api_below_android12));
            }
            sb.append("\n");
            sb.append(getString(R.string.debug_info_vid_pid))
                    .append(dev.getVendorId()).append("_").append(dev.getProductId())
                    .append("\t[").append(String.format("%04x", dev.getVendorId()))
                    .append("_").append(String.format("%04x", dev.getProductId())).append("]\n");
            sb.append(getString(R.string.debug_info_vibration))
                    .append(dev.getVibrator().hasVibrator() ? getString(R.string.debug_info_supported) : getString(R.string.debug_info_not_supported))
                    .append("\n");
            sb.append(getString(R.string.debug_info_details)).append("\n");
            sb.append(dev.toString()).append("\n");
        }

        tx_gamepad_info.setText(getString(R.string.debug_info_number_of_gamepads) + ids.size() + "\n" + sb);
    }
}
