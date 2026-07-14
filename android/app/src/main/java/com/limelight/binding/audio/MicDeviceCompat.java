package com.limelight.binding.audio;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

// This class must be kept separate from MicForwarder/StreamSettings. Referencing AudioDeviceInfo/
// AudioRecord.setPreferredDevice() APIs (introduced in API 23) directly inside those classes -
// which must remain loadable on this app's much older minSdk 16 - risks the same class-
// verification problem documented in ImageDecoderCompat.java. Isolating all of it here means
// this class is only ever loaded/verified when actually invoked, which callers only do after
// checking Build.VERSION.SDK_INT >= Build.VERSION_CODES.M themselves. Public (unlike
// ImageDecoderCompat, which only ever needed same-package access) because both MicForwarder
// (com.limelight.binding.audio) AND StreamSettings (com.limelight.preferences) need to call it.
@TargetApi(Build.VERSION_CODES.M)
public class MicDeviceCompat {

    // Returns one AudioDeviceInfo per available audio INPUT (recording) device.
    public static List<AudioDeviceInfo> getInputDevices(Context context) {
        List<AudioDeviceInfo> result = new ArrayList<>();

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return result;
        }

        for (AudioDeviceInfo info : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            result.add(info);
        }

        return result;
    }

    // NOTE: AudioDeviceInfo IDs are only stable for the lifetime of the current device
    // connection/session, not guaranteed stable across reboots or reconnects - if a
    // previously-saved id no longer matches any currently-connected device (e.g. the
    // headset was unplugged and plugged back in), findDeviceById() below simply returns null
    // and the caller falls back to the default device, same as picking "Automatic" would.
    public static String getDeviceId(AudioDeviceInfo info) {
        return String.valueOf(info.getId());
    }

    public static String getDeviceLabel(AudioDeviceInfo info) {
        CharSequence productName = info.getProductName();
        if (productName != null && productName.length() > 0) {
            return productName.toString();
        }
        return "Microphone " + info.getId();
    }

    // Finds the AudioDeviceInfo matching a previously-saved device id (from getDeviceId), or
    // null if not found (covers both the "Automatic" selection and a stale/disconnected id).
    public static AudioDeviceInfo findDeviceById(Context context, String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return null;
        }

        for (AudioDeviceInfo info : getInputDevices(context)) {
            if (getDeviceId(info).equals(deviceId)) {
                return info;
            }
        }

        return null;
    }

    // Applies the given device (if non-null) as the preferred recording device. No-op if
    // device is null, which leaves AudioRecord using the system default device, same as
    // before this feature existed.
    public static void setPreferredDevice(AudioRecord audioRecord, AudioDeviceInfo device) {
        if (device != null) {
            audioRecord.setPreferredDevice(device);
        }
    }

    // Combines findDeviceById() + setPreferredDevice() into one call so callers (specifically
    // MicForwarder) never need to reference the AudioDeviceInfo type themselves at all - not
    // even as a local variable - keeping that type COMPLETELY isolated to this compat class.
    // This is deliberately more conservative than strictly necessary (see the class-level
    // comment) given this exact codebase has previously hit a real ART class-verification bug
    // from a related but not identical pattern (see ImageDecoderCompat.java).
    public static void applyPreferredDevice(Context context, AudioRecord audioRecord, String deviceId) {
        setPreferredDevice(audioRecord, findDeviceById(context, deviceId));
    }

    // Friendly display labels for every currently-connected input device, in the same order as
    // getDeviceIdsForList(). Exists so StreamSettings (a different, older-minSdk-sensitive
    // class) never needs to reference AudioDeviceInfo directly either - same isolation
    // reasoning as applyPreferredDevice() above.
    public static CharSequence[] getDeviceLabelsForList(Context context) {
        List<AudioDeviceInfo> devices = getInputDevices(context);
        CharSequence[] labels = new CharSequence[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            labels[i] = getDeviceLabel(devices.get(i));
        }
        return labels;
    }

    // Stable ids for every currently-connected input device, in the same order as
    // getDeviceLabelsForList() - these are the values actually persisted to preferences.
    public static CharSequence[] getDeviceIdsForList(Context context) {
        List<AudioDeviceInfo> devices = getInputDevices(context);
        CharSequence[] ids = new CharSequence[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            ids[i] = getDeviceId(devices.get(i));
        }
        return ids;
    }
}
