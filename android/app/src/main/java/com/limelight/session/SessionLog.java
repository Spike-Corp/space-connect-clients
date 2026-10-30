package com.limelight.session;

import com.limelight.LimeLog;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.nvstream.http.HostHttpResponseException;

public final class SessionLog {
    private SessionLog() {}
    private static volatile String diagnostic = "";

    public static String diagnostic() { return diagnostic; }
    public static void clear() { diagnostic = ""; }
    public static void streamFailure(String stage, int code) {
        String safeStage = stage == null ? "STREAM" : stage.replaceAll("[^A-Za-z0-9 _-]", "");
        if (safeStage.length() > 80) safeStage = safeStage.substring(0, 80);
        diagnostic = safeStage + " / " + code;
        LimeLog.warning("Session stream failure: " + diagnostic);
    }

    static void failure(String stage, Exception error) {
        // Exception messages/HTTP URLs can include PINs, tokens or RI keys.
        String code = error instanceof SpaceConnectApiClient.ApiException
                ? " HTTP " + ((SpaceConnectApiClient.ApiException) error).status
                : error instanceof HostHttpResponseException
                    ? " host " + ((HostHttpResponseException) error).getErrorCode() : "";
        diagnostic = stage + " / " + error.getClass().getSimpleName() + code;
        LimeLog.warning("Session " + diagnostic);
        if (error.getStackTrace().length > 0) {
            LimeLog.warning("Session failure at " + error.getStackTrace()[0]);
        }
    }
}
