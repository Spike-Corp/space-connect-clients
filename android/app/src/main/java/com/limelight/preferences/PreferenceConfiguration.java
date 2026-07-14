package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.Display;

import java.util.regex.Pattern;

import com.limelight.nvstream.jni.MoonBridge;

public class PreferenceConfiguration {
    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
    };

    // Ported from Artemis
    public enum ScaleMode {
        FIT,
        FILL,
        STRETCH
    }

    public enum AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    private static final String LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps";
    private static final String LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround";

    static final String RESOLUTION_PREF_STRING = "list_resolution";
    static final String FPS_PREF_STRING = "list_fps";
    static final String BITRATE_PREF_STRING = "seekbar_bitrate_kbps";
    private static final String BITRATE_PREF_OLD_STRING = "seekbar_bitrate";
    // Kept only to migrate existing users' choice into the new 3-way videoScaleMode below.
    private static final String LEGACY_STRETCH_PREF_STRING = "checkbox_stretch_video";
    // Ported from Artemis: replaces the old binary stretch checkbox with a 3-way mode.
    private static final String VIDEO_SCALE_MODE_PREF_STRING = "list_video_scale_mode";
    private static final String SOPS_PREF_STRING = "checkbox_enable_sops";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    private static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity";
    // Ported from Artemis: "free" analog stick variant preferences
    private static final String ENABLE_ANALOG_STICK_NEW_PREF_STRING = "checkbox_enable_analog_stick_new";
    private static final String ANALOG_STICK_NEW_OPACITY_PREF_STRING = "seekbar_osc_free_analog_stick_opacity";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    private static final String SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    private static final String USB_DRIVER_PREF_SRING = "checkbox_usb_driver";
    private static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    private static final String ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls";
    private static final String ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3";
    private static final String LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop";
    private static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_PIP_PREF_STRING = "checkbox_enable_pip";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String ENABLE_PERF_OVERLAY_LITE_STRING = "checkbox_enable_perf_overlay_lite";
    private static final String BIND_ALL_USB_STRING = "checkbox_usb_bind_all";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String ANALOG_SCROLLING_PREF_STRING = "analog_scrolling";
    private static final String MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons";
    static final String UNLOCK_FPS_STRING = "checkbox_unlock_fps";
    private static final String VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc";
    private static final String VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback";
    private static final String VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
    private static final String TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad";
    private static final String LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast";
    private static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    private static final String ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx";
    private static final String MIC_FORWARDING_DEVICE_PREF_STRING = "mic_forwarding_device";
    private static final String REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate";
    private static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";
    private static final String GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback";

    // Ported from Artemis (github.com/MobinYengejehi/Artemis, a moonlight-android fork)
    private static final String CHECKBOX_ENABLE_JOYCON_FIX_STRING = "checkbox_enable_joyconfix";
    private static final String CHECKBOX_ENABLE_DEVICE_RUMBLE_STRING = "checkbox_enable_device_rumble";
    private static final String CHECKBOX_FORCE_QWERTY_STRING = "checkbox_force_qwerty";
    private static final String CHECKBOX_BACK_AS_META_STRING = "checkbox_back_as_meta";
    private static final String CHECKBOX_AUTO_ORIENTATION_STRING = "checkbox_auto_orientation";
    private static final String CHECKBOX_ALIGN_DISPLAY_TOP_CENTER_STRING = "checkbox_enable_view_top_center";
    private static final String SEEKBAR_TRACKPAD_SENSITIVITY_X_STRING = "seekbar_trackpad_sensitivity_x";
    private static final String SEEKBAR_TRACKPAD_SENSITIVITY_Y_STRING = "seekbar_trackpad_sensitivity_y";
    private static final String CHECKBOX_TRACKPAD_SWAP_AXIS_STRING = "checkbox_trackpad_swap_axis";

    static final String DEFAULT_RESOLUTION = "1280x720";
    static final String DEFAULT_FPS = "60";
    private static final String DEFAULT_VIDEO_SCALE_MODE = "fit";
    private static final boolean DEFAULT_SOPS = true;
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    private static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 7;
    private static final int DEFAULT_OPACITY = 90;
    public static final String DEFAULT_LANGUAGE = "default";
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_USB_DRIVER = true;
    private static final String DEFAULT_VIDEO_FORMAT = "auto";

    private static final boolean ONSCREEN_CONTROLLER_DEFAULT = false;
    private static final boolean ONLY_L3_R3_DEFAULT = false;
    private static final boolean DEFAULT_ENABLE_HDR = false;
    private static final boolean DEFAULT_ENABLE_PIP = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY_LITE = false;
    private static final boolean DEFAULT_BIND_ALL_USB = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final String DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right";
    private static final boolean DEFAULT_MOUSE_NAV_BUTTONS = false;
    private static final boolean DEFAULT_UNLOCK_FPS = false;
    private static final boolean DEFAULT_VIBRATE_OSC = true;
    private static final boolean DEFAULT_VIBRATE_FALLBACK = false;
    private static final int DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    private static final boolean DEFAULT_TOUCHSCREEN_TRACKPAD = true;
    private static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    private static final boolean DEFAULT_LATENCY_TOAST = false;
    private static final String DEFAULT_FRAME_PACING = "latency";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    private static final boolean DEFAULT_ENABLE_AUDIO_FX = false;
    // Empty string means "automatic"/use the system-selected default recording device
    private static final String DEFAULT_MIC_FORWARDING_DEVICE = "";
    private static final boolean DEFAULT_REDUCE_REFRESH_RATE = false;
    private static final boolean DEFAULT_FULL_RANGE = false;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;
    private static final boolean DEFAULT_GAMEPAD_MOTION_FALLBACK = false;

    // Ported from Artemis
    private static final boolean DEFAULT_ENABLE_JOYCON_FIX = false;
    private static final boolean DEFAULT_ENABLE_DEVICE_RUMBLE = false;
    // Stock behavior (unconditional QWERTY remap in KeyboardTranslator) is preserved by
    // defaulting this to true - this preference is really an OPT-OUT for users with a
    // non-QWERTY physical keyboard layout they want passed through unmodified.
    private static final boolean DEFAULT_FORCE_QWERTY = true;
    private static final boolean DEFAULT_BACK_AS_META = false;
    private static final boolean DEFAULT_AUTO_ORIENTATION = false;
    private static final boolean DEFAULT_ALIGN_DISPLAY_TOP_CENTER = false;
    private static final int DEFAULT_TRACKPAD_SENSITIVITY_X = 100;
    private static final int DEFAULT_TRACKPAD_SENSITIVITY_Y = 100;
    private static final boolean DEFAULT_TRACKPAD_SWAP_AXIS = false;

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;

    public static final String RES_360P = "640x360";
    public static final String RES_480P = "854x480";
    public static final String RES_720P = "1280x720";
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";
    public static final String RES_NATIVE = "Native";

    public int width, height, fps;
    public int bitrate;
    public FormatOption videoFormat;
    public int deadzonePercentage;
    public int oscOpacity;
    public boolean enableSops, playHostAudio, disableWarnings;
    public ScaleMode videoScaleMode;
    public String language;
    public boolean smallIconMode, multiController, usbDriver, flipFaceButtons;
    public boolean onscreenController;
    public boolean onlyL3R3;
    // Ported from Artemis: "free" analog stick variant - recenters wherever the finger first
    // touches down instead of requiring a precise touch on the fixed-position nub.
    public boolean enableNewAnalogStick;
    public int enableNewAnalogStickOpacity;
    public boolean enableHdr;
    // Ported from Artemis: streams to a secondary/external display while keeping the on-screen
    // controls and performance overlay on the device's own screen.
    public boolean enableExDisplay;
    public boolean enablePip;
    public boolean enablePerfOverlay;
    public boolean enablePerfOverlayLite;
    public boolean enableLatencyToast;
    public boolean bindAllUsb;
    public boolean mouseEmulation;
    public AnalogStickForScrolling analogStickForScrolling;
    public boolean mouseNavButtons;
    public boolean unlockFps;
    public boolean vibrateOsc;
    public boolean vibrateFallbackToDevice;
    public int vibrateFallbackToDeviceStrength;
    public boolean touchscreenTrackpad;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public int framePacing;
    // Ported from Artemis: multiplier (0 = none, 2 or 4) for the experimental "Warp Drive"
    // frame pacing options - see Game.java's chosenFrameRate calculation.
    public int framePacingWarpFactor;
    public boolean absoluteMouseMode;
    public boolean enableAudioFx;
    public String micForwardingDevice;
    public boolean reduceRefreshRate;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;
    public boolean gamepadMotionSensorsFallbackToDevice;

    // Ported from Artemis
    public boolean enableJoyConFix;
    public boolean enableDeviceRumble;
    public boolean forceQwerty;
    public boolean backAsMeta;
    public boolean autoOrientation;
    public boolean alignDisplayTopCenter;

    // Ported from Artemis: sensitivity multipliers (as a percentage, 100 = 1.0x) and axis-swap
    // for TrackpadContext's "natural" trackpad mode. enableMultiTouchScreen is deliberately NOT
    // a persisted preference - it (like the rest of the mouse mode) is a runtime-only choice
    // made via the in-game menu's "Select mouse mode" option (see Game.applyMouseMode()).
    public int trackpadSensitivityX;
    public int trackpadSensitivityY;
    public boolean trackpadSwapAxis;
    public boolean enableMultiTouchScreen;

    public static boolean isNativeResolution(int width, int height) {
        // It's not a native resolution if it matches an existing resolution option
        if (width == 640 && height == 360) {
            return false;
        }
        else if (width == 854 && height == 480) {
            return false;
        }
        else if (width == 1280 && height == 720) {
            return false;
        }
        else if (width == 1920 && height == 1080) {
            return false;
        }
        else if (width == 2560 && height == 1440) {
            return false;
        }
        else if (width == 3840 && height == 2160) {
            return false;
        }

        return true;
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    public static boolean isSquarishScreen(int width, int height) {
        float longDim = Math.max(width, height);
        float shortDim = Math.min(width, height);

        // We just put the arbitrary cutoff for a square-ish screen at 1.3
        return longDim / shortDim < 1.3f;
    }

    public static boolean isSquarishScreen(Display display) {
        int width, height;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            width = display.getMode().getPhysicalWidth();
            height = display.getMode().getPhysicalHeight();
        }
        else {
            width = display.getWidth();
            height = display.getHeight();
        }

        return isSquarishScreen(width, height);
    }

    private static String convertFromLegacyResolutionString(String resString) {
        if (resString.equalsIgnoreCase("360p")) {
            return RES_360P;
        }
        else if (resString.equalsIgnoreCase("480p")) {
            return RES_480P;
        }
        else if (resString.equalsIgnoreCase("720p")) {
            return RES_720P;
        }
        else if (resString.equalsIgnoreCase("1080p")) {
            return RES_1080P;
        }
        else if (resString.equalsIgnoreCase("1440p")) {
            return RES_1440P;
        }
        else if (resString.equalsIgnoreCase("4K")) {
            return RES_4K;
        }
        else {
            // Should be unreachable
            return RES_720P;
        }
    }

    private static int getWidthFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[0]);
    }

    private static int getHeightFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[1]);
    }

    private static String getResolutionString(int width, int height) {
        switch (height) {
            case 360:
                return RES_360P;
            case 480:
                return RES_480P;
            default:
            case 720:
                return RES_720P;
            case 1080:
                return RES_1080P;
            case 1440:
                return RES_1440P;
            case 2160:
                return RES_4K;
        }
    }

    public static int getDefaultBitrate(String resString, String fpsString) {
        int width = getWidthFromResolutionString(resString);
        int height = getHeightFromResolutionString(resString);
        int fps = Integer.parseInt(fpsString);

        // This logic is shamelessly stolen from Moonlight Qt:
        // https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp

        // Don't scale bitrate linearly beyond 60 FPS. It's definitely not a linear
        // bitrate increase for frame rate once we get to values that high.
        double frameRateFactor = (fps <= 60 ? fps : (Math.sqrt(fps / 60.f) * 60.f)) / 30.f;

        // TODO: Collect some empirical data to see if these defaults make sense.
        // We're just using the values that the Shield used, as we have for years.
        int[] pixelVals = {
            640 * 360,
            854 * 480,
            1280 * 720,
            1920 * 1080,
            2560 * 1440,
            3840 * 2160,
            -1,
        };
        int[] factorVals = {
            1,
            2,
            5,
            10,
            20,
            40,
            -1
        };

        // Calculate the resolution factor by linear interpolation of the resolution table
        float resolutionFactor;
        int pixels = width * height;
        for (int i = 0; ; i++) {
            if (pixels == pixelVals[i]) {
                // We can bail immediately for exact matches
                resolutionFactor = factorVals[i];
                break;
            }
            else if (pixels < pixelVals[i]) {
                if (i == 0) {
                    // Never go below the lowest resolution entry
                    resolutionFactor = factorVals[i];
                }
                else {
                    // Interpolate between the entry greater than the chosen resolution (i) and the entry less than the chosen resolution (i-1)
                    resolutionFactor = ((float)(pixels - pixelVals[i-1]) / (pixelVals[i] - pixelVals[i-1])) * (factorVals[i] - factorVals[i-1]) + factorVals[i-1];
                }
                break;
            }
            else if (pixelVals[i] == -1) {
                // Never go above the highest resolution entry
                resolutionFactor = factorVals[i-1];
                break;
            }
        }

        return (int)Math.round(resolutionFactor * frameRateFactor) * 1000;
    }

    // Matches GPU model strings reported by the host's serverinfo (e.g. "NVIDIA L4",
    // "NVIDIA GeForce RTX 3080 Ti", "NVIDIA A10-4Q") against Space Cloud's dedicated
    // "physical machine" tier hardware. Word boundaries (\b) keep this from false-matching
    // similarly-named but different GPUs, e.g. "A10" must not match "A100", and "L4" must
    // not match "L40"/"L40S".
    private static final Pattern HIGH_TIER_GPU_PATTERN = Pattern.compile("(?i)\\b(L4|A10|RTX 3080 Ti)\\b");

    public static boolean isHighTierGpu(String gpuModel) {
        return gpuModel != null && !gpuModel.isEmpty() && HIGH_TIER_GPU_PATTERN.matcher(gpuModel).find();
    }

    public static final int MIN_BITRATE_KBPS = 500;
    // Cloud VMs (GCP/AWS/IBM/etc) — capped to fit the remote provider's bandwidth/cost budget.
    public static final int MAX_BITRATE_KBPS_STANDARD = 25000;
    // Proxmox "physical machine" hosts (e.g. Eveo) — LAN-grade throughput, up to 100 Mbps
    // (50 Mbps recommended). The real ceiling is reported per-machine by the backend and cached
    // in "launcher_max_bitrate_kbps"; this stays as the fallback when that value isn't present.
    public static final int MAX_BITRATE_KBPS_HIGH_TIER = 100000;

    // Clamps an arbitrary computed bitrate (Kbps) into the valid range for the free-form
    // bitrate editor (seekbar_bitrate_kbps), so a resolution/FPS/YUV444 change never
    // silently produces a value outside what the user can actually select - or worse, an
    // uncapped one that still gets streamed with.
    public static int clampBitrate(int computedKbps) {
        return clampBitrate(computedKbps, false);
    }

    // Same as above, but allows up to 100 Mbps when includeHighTier is true (i.e. the
    // connected PC is one of Space Cloud's dedicated physical-machine hosts).
    public static int clampBitrate(int computedKbps, boolean includeHighTier) {
        int max = includeHighTier ? MAX_BITRATE_KBPS_HIGH_TIER : MAX_BITRATE_KBPS_STANDARD;
        return Math.max(MIN_BITRATE_KBPS, Math.min(max, computedKbps));
    }

    public static boolean getDefaultSmallMode(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager != null) {
            // TVs shouldn't use small mode by default
            if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return false;
            }

            // API 21 uses LEANBACK instead of TELEVISION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return false;
                }
            }
        }

        // Use small mode on anything smaller than a 7" tablet
        return context.getResources().getConfiguration().smallestScreenWidthDp < 500;
    }

    public static int getDefaultBitrate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return clampBitrate(getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION),
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS)));
    }

    private static FormatOption getVideoFormatValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT);
        if (str.equals("auto")) {
            return FormatOption.AUTO;
        }
        else if (str.equals("forceav1")) {
            return FormatOption.FORCE_AV1;
        }
        else if (str.equals("forceh265")) {
            return FormatOption.FORCE_HEVC;
        }
        else {
            // Should never get here
            return FormatOption.AUTO;
        }
    }

    private static int getFramePacingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Migrate legacy never drop frames option to the new location
        if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
            boolean legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false);
            prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, legacyNeverDropFrames ? "balanced" : "latency")
                    .apply();
        }

        String str = prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
        if (str.equals("latency")) {
            return FRAME_PACING_MIN_LATENCY;
        }
        else if (str.equals("balanced")) {
            return FRAME_PACING_BALANCED;
        }
        else if (str.equals("cap-fps")) {
            return FRAME_PACING_CAP_FPS;
        }
        else if (str.equals("smoothness")) {
            return FRAME_PACING_MAX_SMOOTHNESS;
        }
        else {
            // Should never get here
            return FRAME_PACING_MIN_LATENCY;
        }
    }

    private static AnalogStickForScrolling getAnalogStickForScrollingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING);
        if (str.equals("right")) {
            return AnalogStickForScrolling.RIGHT;
        }
        else if (str.equals("left")) {
            return AnalogStickForScrolling.LEFT;
        }
        else {
            return AnalogStickForScrolling.NONE;
        }
    }

    public static void resetStreamingSettings(Context context) {
        // We consider resolution, FPS, bitrate, HDR, and video format as "streaming settings" here
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply();
    }

    public static void completeLanguagePreferenceMigration(Context context) {
        // Put our language option back to default which tells us that we've already migrated it
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply();
    }

    public static boolean isShieldAtvFirmwareWithBrokenHdr() {
        // This particular Shield TV firmware crashes when using HDR
        // https://www.nvidia.com/en-us/geforce/forums/notifications/comment/155192/
        return Build.MANUFACTURER.equalsIgnoreCase("NVIDIA") &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395");
    }

    public static PreferenceConfiguration readPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PreferenceConfiguration config = new PreferenceConfiguration();

        // Migrate legacy preferences to the new locations
        if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
            if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply();
            }
        }

        String str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null);
        if (str != null) {
            if (str.equals("360p30")) {
                config.width = 640;
                config.height = 360;
                config.fps = 30;
            }
            else if (str.equals("360p60")) {
                config.width = 640;
                config.height = 360;
                config.fps = 60;
            }
            else if (str.equals("720p30")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 30;
            }
            else if (str.equals("720p60")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }
            else if (str.equals("1080p30")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 30;
            }
            else if (str.equals("1080p60")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 60;
            }
            else if (str.equals("4K30")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 30;
            }
            else if (str.equals("4K60")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 60;
            }
            else {
                // Should never get here
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }

            prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, ""+config.fps)
                    .apply();
        }
        else {
            // Use the new preference location
            String resStr = prefs.getString(RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);

            // Convert legacy resolution strings to the new style
            if (!resStr.contains("x")) {
                resStr = PreferenceConfiguration.convertFromLegacyResolutionString(resStr);
                prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply();
            }

            config.width = PreferenceConfiguration.getWidthFromResolutionString(resStr);
            config.height = PreferenceConfiguration.getHeightFromResolutionString(resStr);
            config.fps = Integer.parseInt(prefs.getString(FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS));
        }

        if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
            // We need to write small icon mode's default to disk for the settings page to display
            // the current state of the option properly
            prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply();
        }

        if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            // Android 12 has a nasty bug that causes crashes when the app touches the InputDevice's
            // associated InputDeviceSensorManager (just calling getSensorManager() is enough).
            // As a workaround, we will override the default value for the gamepad motion sensor
            // option to disabled on Android 12 to reduce the impact of this bug.
            // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
            prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply();
        }

        // This must happen after the preferences migration to ensure the preferences are populated

        // The bitrate preference is a free-form SeekBarPreference (int-backed). Tolerate a
        // leftover String value from an older build where this key was a fixed-tier
        // ListPreference (String-backed), and an even older leftover int value from the
        // original continuous bitrate slider under a different legacy key.
        int bitrate = 0;
        try {
            bitrate = prefs.getInt(BITRATE_PREF_STRING, 0);
        } catch (ClassCastException e) {
            try {
                String bitrateStr = prefs.getString(BITRATE_PREF_STRING, null);
                if (bitrateStr != null) {
                    bitrate = Integer.parseInt(bitrateStr);
                }
            } catch (ClassCastException | NumberFormatException e2) {
                bitrate = 0;
            }
        }
        if (bitrate == 0) {
            bitrate = prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000;
        }
        config.bitrate = bitrate;
        if (config.bitrate == 0) {
            config.bitrate = getDefaultBitrate(context);
        }

        String audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG);
        if (audioConfig.equals("71")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        else if (audioConfig.equals("51")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        else /* if (audioConfig.equals("2")) */ {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        }

        config.videoFormat = getVideoFormatValue(context);
        config.framePacing = getFramePacingValue(context);

        // Ported from Artemis: two additional experimental "Warp Drive" frame pacing options
        // that request 2x/4x the actual target frame rate from the host. Some devices/decoders
        // exhibit smoother perceived pacing when the host is asked for a higher rate than
        // strictly needed - purely opt-in (framePacingWarpFactor defaults to 0 = no multiplier,
        // and the two new "warp"/"warp2" list entries are additive, existing frame_pacing
        // values are completely unaffected).
        String framePacingStr = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
        if (framePacingStr.equals("warp")) {
            config.framePacingWarpFactor = 2;
        }
        else if (framePacingStr.equals("warp2")) {
            config.framePacingWarpFactor = 4;
        }

        config.analogStickForScrolling = getAnalogStickForScrollingValue(context);

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);

        config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY);
        config.enableNewAnalogStick = prefs.getBoolean(ENABLE_ANALOG_STICK_NEW_PREF_STRING, false);
        config.enableNewAnalogStickOpacity = prefs.getInt(ANALOG_STICK_NEW_OPACITY_PREF_STRING, 20);

        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);

        // Checkbox preferences
        config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS);
        config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS);

        // Ported from Artemis: 3-way Fit/Fill/Stretch mode, replacing the old binary stretch
        // checkbox. Migrate existing users' choice: if the new list preference hasn't been set
        // yet but the old checkbox was, honor it (true -> STRETCH, false/unset -> FIT).
        if (!prefs.contains(VIDEO_SCALE_MODE_PREF_STRING) && prefs.contains(LEGACY_STRETCH_PREF_STRING)) {
            config.videoScaleMode = prefs.getBoolean(LEGACY_STRETCH_PREF_STRING, false) ?
                    ScaleMode.STRETCH : ScaleMode.FIT;
        }
        else {
            String scaleModeStr = prefs.getString(VIDEO_SCALE_MODE_PREF_STRING, DEFAULT_VIDEO_SCALE_MODE);
            if (scaleModeStr.equals("fill")) {
                config.videoScaleMode = ScaleMode.FILL;
            }
            else if (scaleModeStr.equals("stretch")) {
                config.videoScaleMode = ScaleMode.STRETCH;
            }
            else {
                config.videoScaleMode = ScaleMode.FIT;
            }
        }

        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);
        config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context));
        config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER);
        config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER);
        config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, ONSCREEN_CONTROLLER_DEFAULT);
        config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT);
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr();
        config.enableExDisplay = prefs.getBoolean("checkbox_enable_exdisplay", false);
        config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP);
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.enablePerfOverlayLite = prefs.getBoolean(ENABLE_PERF_OVERLAY_LITE_STRING, DEFAULT_ENABLE_PERF_OVERLAY_LITE);
        config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS);
        config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS);
        config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC);
        config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK);
        config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);
        config.touchscreenTrackpad = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD);
        config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX);
        config.micForwardingDevice = prefs.getString(MIC_FORWARDING_DEVICE_PREF_STRING, DEFAULT_MIC_FORWARDING_DEVICE);
        config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);
        config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK);

        // Ported from Artemis
        config.enableJoyConFix = prefs.getBoolean(CHECKBOX_ENABLE_JOYCON_FIX_STRING, DEFAULT_ENABLE_JOYCON_FIX);
        config.enableDeviceRumble = prefs.getBoolean(CHECKBOX_ENABLE_DEVICE_RUMBLE_STRING, DEFAULT_ENABLE_DEVICE_RUMBLE);
        config.forceQwerty = prefs.getBoolean(CHECKBOX_FORCE_QWERTY_STRING, DEFAULT_FORCE_QWERTY);
        config.backAsMeta = prefs.getBoolean(CHECKBOX_BACK_AS_META_STRING, DEFAULT_BACK_AS_META);
        config.autoOrientation = prefs.getBoolean(CHECKBOX_AUTO_ORIENTATION_STRING, DEFAULT_AUTO_ORIENTATION);
        config.alignDisplayTopCenter = prefs.getBoolean(CHECKBOX_ALIGN_DISPLAY_TOP_CENTER_STRING, DEFAULT_ALIGN_DISPLAY_TOP_CENTER);
        config.trackpadSensitivityX = prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_X_STRING, DEFAULT_TRACKPAD_SENSITIVITY_X);
        config.trackpadSensitivityY = prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_Y_STRING, DEFAULT_TRACKPAD_SENSITIVITY_Y);
        config.trackpadSwapAxis = prefs.getBoolean(CHECKBOX_TRACKPAD_SWAP_AXIS_STRING, DEFAULT_TRACKPAD_SWAP_AXIS);

        return config;
    }
}
