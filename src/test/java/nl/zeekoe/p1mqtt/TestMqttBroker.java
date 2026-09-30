package nl.zeekoe.p1mqtt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class TestMqttBroker implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final List<Connect> connects = new CopyOnWriteArrayList<>();
    private final List<Publish> publishes = new CopyOnWriteArrayList<>();
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch disconnectLatch = new CountDownLatch(1);
    private volatile boolean stopped;

    TestMqttBroker() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        acceptor = new Thread(this::acceptLoop, "test-mqtt-broker");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    List<Connect> connects() {
        return List.copyOf(connects);
    }

    List<Publish> publishes() {
        return List.copyOf(publishes);
    }

    List<Publish> publishesOn(String topic) {
        return publishes.stream().filter(publish -> publish.topic().equals(topic)).toList();
    }

    boolean awaitConnected(Duration timeout) throws InterruptedException {
        return connectedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitDisconnect(Duration timeout) throws InterruptedException {
        return disconnectLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> serve(socket), "test-mqtt-connection");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!stopped) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            DataInputStream dataIn = new DataInputStream(in);
            while (true) {
                int header = dataIn.read();
                if (header < 0) {
                    return;
                }
                Cursor packet = new Cursor(dataIn.readNBytes(readVariableByteInteger(dataIn)));
                switch (header >> 4) {
                    case 1 -> {
                        connects.add(readConnect(packet));
                        out.write(new byte[]{0x20, 0x03, 0x00, 0x00, 0x00});
                        out.flush();
                        connectedLatch.countDown();
                    }
                    case 3 -> {
                        Publish publish = readPublish(packet, (header >> 1) & 0x03, (header & 0x01) == 0x01);
                        publishes.add(publish);
                        if (publish.qos() == 1) {
                            out.write(new byte[]{0x40, 0x02, (byte) (publish.packetId() >> 8), (byte) publish.packetId()});
                            out.flush();
                        }
                    }
                    case 12 -> {
                        out.write(new byte[]{(byte) 0xD0, 0x00});
                        out.flush();
                    }
                    case 14 -> {
                        disconnectLatch.countDown();
                        return;
                    }
                    default -> {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            if (!stopped) {
                return;
            }
        }
    }

    private Connect readConnect(Cursor packet) throws IOException {
        packet.readString();
        int protocolVersion = packet.readByte();
        int flags = packet.readByte();
        packet.skip(2);
        packet.skipProperties();
        String clientId = packet.readString();
        String willTopic = null;
        String willPayload = null;
        if ((flags & 0x04) != 0) {
            packet.skipProperties();
            willTopic = packet.readString();
            willPayload = new String(packet.readBinary(), StandardCharsets.UTF_8);
        }
        String username = (flags & 0x80) != 0 ? packet.readString() : null;
        if ((flags & 0x40) != 0) {
            packet.readBinary();
        }
        return new Connect(clientId, protocolVersion, (flags & 0x02) != 0, willTopic, willPayload, username);
    }

    private Publish readPublish(Cursor packet, int qos, boolean retain) throws IOException {
        String topic = packet.readString();
        int packetId = qos > 0 ? packet.readUnsignedShort() : 0;
        packet.skipProperties();
        return new Publish(topic, new String(packet.readRest(), StandardCharsets.UTF_8), qos, retain, packetId);
    }

    private static int readVariableByteInteger(DataInputStream in) throws IOException {
        int value = 0;
        int multiplier = 1;
        int digit;
        do {
            digit = in.readUnsignedByte();
            value += (digit & 127) * multiplier;
            multiplier *= 128;
        } while ((digit & 128) != 0);
        return value;
    }

    @Override
    public void close() throws IOException {
        stopped = true;
        serverSocket.close();
    }

    record Connect(String clientId, int protocolVersion, boolean cleanStart, String willTopic, String willPayload,
                   String username) {
    }

    record Publish(String topic, String payload, int qos, boolean retain, int packetId) {
    }

    private static final class Cursor {

        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        int readByte() {
            return data[position++] & 0xFF;
        }

        int readUnsignedShort() {
            return ((data[position++] & 0xFF) << 8) | (data[position++] & 0xFF);
        }

        String readString() {
            return new String(readBinary(), StandardCharsets.UTF_8);
        }

        byte[] readBinary() {
            return readRest(readUnsignedShort());
        }

        byte[] readRest() {
            return readRest(data.length - position);
        }

        byte[] readRest(int length) {
            byte[] slice = new byte[length];
            System.arraycopy(data, position, slice, 0, length);
            position += length;
            return slice;
        }

        void skip(int count) {
            position += count;
        }

        void skipProperties() {
            int length = 0;
            int multiplier = 1;
            int digit;
            do {
                digit = readByte();
                length += (digit & 127) * multiplier;
                multiplier *= 128;
            } while ((digit & 128) != 0);
            skip(length);
        }
    }
}
