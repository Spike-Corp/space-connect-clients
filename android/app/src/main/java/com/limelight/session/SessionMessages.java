package com.limelight.session;

import android.content.Context;
import com.limelight.R;
import com.limelight.account.SpaceConnectApiClient.BootTexts;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;

final class SessionMessages {
    private SessionMessages() {}

    static String message(Context context, SessionOrchestrator.Stage stage, StatusResponse status) {
        BootTexts texts = status == null ? null : status.bootTexts;
        if (stage == SessionOrchestrator.Stage.QUEUED && status != null && status.queue != null) {
            int position = status.queue.position;
            int total = status.queue.total;
            String positionText = context.getString(R.string.launcher_queue_position, position, total);
            String title = texts == null ? context.getString(R.string.launcher_status_queued)
                    : texts.queueTitle(position, total, context.getString(R.string.launcher_status_queued));
            String reason = texts == null || texts.reasons == null ? null : texts.reasons.get(status.queue.blockedReason);
            reason = BootTexts.text(reason, texts == null ? "" : BootTexts.text(texts.queueSearching, ""));
            reason = reason.replace("{position}", String.valueOf(position)).replace("{total}", String.valueOf(total));
            return title + "\n" + positionText + (reason.isEmpty() || reason.equals(positionText) ? "" : "\n" + reason);
        }
        if (stage == SessionOrchestrator.Stage.STARTING) {
            String phase = status != null && status.session != null && status.session.machine != null
                    ? status.session.machine.creationPhase : null;
            String reason = texts == null || texts.phases == null ? null : texts.phases.get(phase);
            int phaseResource = phaseResource(phase);
            String fallback = context.getString(phaseResource);
            if (phaseResource == R.string.phase_generic && texts != null) {
                fallback = BootTexts.text(texts.defaultTitle, fallback);
            }
            reason = BootTexts.text(reason, fallback);
            if (status != null && status.bootElapsedSeconds >= 60 && texts != null) {
                reason = BootTexts.text(texts.takingLonger, "{reason}").replace("{reason}", reason);
            }
            return BootTexts.text(texts == null ? null : texts.bootingTitle,
                    context.getString(R.string.launcher_status_starting)) + "\n" + reason;
        }
        if (stage == SessionOrchestrator.Stage.PAIRING) return context.getString(R.string.session_pairing);
        if (stage == SessionOrchestrator.Stage.WAITING_FOR_FRAME) return context.getString(R.string.session_waiting_frame);
        if (stage == SessionOrchestrator.Stage.CONNECTING) return context.getString(R.string.session_connecting);
        return context.getString(R.string.session_preparing);
    }

    private static int phaseResource(String phase) {
        if (phase == null) return R.string.phase_generic;
        switch (phase) {
            case "queued": return R.string.phase_queued;
            case "checking_snapshot": return R.string.phase_checking_snapshot;
            case "restoring_disk": return R.string.phase_restoring_disk;
            case "restoring_instance": return R.string.phase_restoring_instance;
            case "creating_instance": return R.string.phase_creating_instance;
            case "attaching_gpu": return R.string.phase_attaching_gpu;
            case "starting": return R.string.phase_starting;
            case "securing": return R.string.phase_securing;
            case "setting_password": return R.string.phase_setting_password;
            case "configuring_network": return R.string.phase_configuring_network;
            case "waiting_agent": return R.string.phase_waiting_agent;
            case "ready": return R.string.phase_ready;
            default: return R.string.phase_generic;
        }
    }

    static int error(SessionOrchestrator.Failure failure) {
        switch (failure) {
            case DESKTOP_MISSING: return R.string.session_desktop_missing;
            case PAIRING: return R.string.session_pair_failed;
            case HOST_BUSY: return R.string.session_host_busy;
            default: return R.string.session_connection_failed;
        }
    }
}
