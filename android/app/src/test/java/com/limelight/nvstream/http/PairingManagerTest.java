package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.ComputerDetails.AddressTuple;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PairingManagerTest {
    private static final String SERVER_INFO =
            "<root status_code=\"200\"><appversion>7.1.431.1</appversion></root>";
    private MockWebServer server;
    private AndroidCryptoProvider crypto;
    private NvHTTP http;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        crypto = new AndroidCryptoProvider(RuntimeEnvironment.getApplication());
        http = new NvHTTP(new AddressTuple(server.getHostName(), server.getPort()),
                0, "test-client", null, crypto);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void missingLegacyUnpairDoesNotHideAnExistingPairingAttempt() throws Exception {
        server.enqueue(new MockResponse().setBody("<root status_code=\"200\"><paired>1</paired></root>"));
        server.enqueue(new MockResponse().setResponseCode(404));

        PairingManager pairing = http.getPairingManager();
        assertEquals(PairingManager.PairState.ALREADY_IN_PROGRESS, pairing.pair(SERVER_INFO, "1234"));
        assertNull(pairing.getPairedCert());
        assertEquals("getservercert", server.takeRequest(2, TimeUnit.SECONDS).getRequestUrl().queryParameter("phrase"));
        assertEquals("/unpair", server.takeRequest(2, TimeUnit.SECONDS).getRequestUrl().encodedPath());
    }

    @Test
    public void cleanupServerErrorDoesNotHideAFailedHandshake() throws Exception {
        String certificate = ByteString.of(crypto.getPemEncodedClientCertificate()).hex();
        server.enqueue(new MockResponse().setBody(
                "<root status_code=\"200\"><paired>1</paired><plaincert>" + certificate + "</plaincert></root>"));
        server.enqueue(new MockResponse().setBody("<root status_code=\"200\"><paired>0</paired></root>"));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertEquals(PairingManager.PairState.FAILED, http.getPairingManager().pair(SERVER_INFO, "1234"));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void explicitUnpairStillReportsFailure() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThrows(IOException.class, () -> http.unpair());
    }
}
