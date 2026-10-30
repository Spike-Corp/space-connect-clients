package com.limelight.session;

import com.limelight.nvstream.http.NvApp;

public final class DesktopSelector {
    private DesktopSelector() {}

    public static NvApp select(Iterable<NvApp> apps) throws SessionOrchestrator.SessionException {
        if (apps != null) {
            for (NvApp app : apps) {
                if (app != null && app.isInitialized() && app.getAppId() != 0
                        && app.getAppName() != null && "Desktop".equalsIgnoreCase(app.getAppName().trim())) {
                    return app;
                }
            }
        }
        throw new SessionOrchestrator.SessionException(SessionOrchestrator.Failure.DESKTOP_MISSING);
    }
}
