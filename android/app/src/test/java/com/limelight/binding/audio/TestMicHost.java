package com.limelight.binding.audio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class TestMicHost implements AutoCloseable {
    public static final String ID = MicPacketTest.ID, KEY = MicPacketTest.KEY;
    private final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread worker;

    public TestMicHost() throws IOException {
        worker = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
                    socket.receive(packet);
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    MicPacketTest.decrypt(data);
                    byte[] ack = MicPacketTest.acknowledge(data);
                    socket.send(new DatagramPacket(ack, ack.length, packet.getSocketAddress()));
                }
            } catch (Exception error) {
                if (!socket.isClosed()) failure.set(error);
            }
        }, "Test microphone receiver");
        worker.start();
    }

    public int port() { return socket.getLocalPort(); }

    @Override public void close() throws Exception {
        socket.close();
        worker.join(2000);
        if (worker.isAlive()) throw new AssertionError("Test receiver did not stop");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
