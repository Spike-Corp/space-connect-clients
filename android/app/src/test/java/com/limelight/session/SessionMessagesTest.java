package com.limelight.session;

import com.google.gson.Gson;
import com.limelight.account.SpaceConnectApiClient.StatusResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SessionMessagesTest {
    @Test public void realPositionAndAdminStatusSurviveWithoutDashboardTips() {
        StatusResponse queued = new Gson().fromJson(
                "{\"state\":\"queued\",\"queue\":{\"position\":3,\"total\":8,\"blockedReason\":\"capacity\"},"
                + "\"bootTexts\":{\"queueTitle\":\"Minha fila {position}/{total}\",\"reasons\":{\"capacity\":\"Texto do admin\"},"
                + "\"tips\":[\"Dica apenas no painel\"]}}", StatusResponse.class);
        String message = SessionMessages.message(RuntimeEnvironment.getApplication(), SessionOrchestrator.Stage.QUEUED, queued);
        assertTrue(message.contains("Minha fila 3/8"));
        assertTrue(message.contains("Texto do admin"));
        assertFalse(message.contains("Dica apenas"));

        StatusResponse boot = new Gson().fromJson(
                "{\"state\":\"starting\",\"bootElapsedSeconds\":90,\"session\":{\"machine\":{\"creationPhase\":\"waiting_agent\"}},"
                + "\"bootTexts\":{\"bootingTitle\":\"Título do admin\",\"phases\":{\"waiting_agent\":\"Fase editada\"},"
                + "\"takingLonger\":\"Aguarde: {reason}\",\"tips\":[\"Outra dica\"]}}", StatusResponse.class);
        message = SessionMessages.message(RuntimeEnvironment.getApplication(), SessionOrchestrator.Stage.STARTING, boot);
        assertEquals("Título do admin\nAguarde: Fase editada", message);
    }
}
