package com.limelight.session;

import com.limelight.LimeLog;
import com.limelight.account.SpaceConnectApiClient;
import com.limelight.nvstream.http.HostHttpResponseException;

final class SessionLog {
    private SessionLog() {}

    static void failure(String stage, Exception error) {
        // Exception messages/HTTP URLs can include PINs, tokens or RI keys.
        String code = error instanceof SpaceConnectApiClient.ApiException
                ? " HTTP " + ((SpaceConnectApiClient.ApiException) error).status
                : error instanceof HostHttpResponseException
                    ? " host " + ((HostHttpResponseException) error).getErrorCode() : "";
        LimeLog.warning("Session " + stage + ": " + error.getClass().getSimpleName() + code);
        if (error.getStackTrace().length > 0) {
            LimeLog.warning("Session failure at " + error.getStackTrace()[0]);
        }
    }
}
