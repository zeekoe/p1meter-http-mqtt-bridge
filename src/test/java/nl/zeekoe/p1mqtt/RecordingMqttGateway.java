package nl.zeekoe.p1mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RecordingMqttGateway implements MqttGateway {

    private final List<TopicMessage> messages = new ArrayList<>();
    private final List<Runnable> connectListeners = new ArrayList<>();
    private boolean started;
    private boolean connected;
    private boolean closed;

    @Override
    public synchronized void start() {
        started = true;
        connected = true;
        connectListeners.forEach(Runnable::run);
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public synchronized void publish(TopicMessage message) {
        messages.add(message);
    }

    @Override
    public synchronized void publishAndWait(TopicMessage message, Duration timeout) {
        messages.add(message);
    }

    @Override
    public synchronized void addConnectListener(Runnable listener) {
        connectListeners.add(listener);
    }

    @Override
    public String describe() {
        return connected ? "test broker (connected)" : "test broker (not connected)";
    }

    @Override
    public synchronized void close() {
        closed = true;
        connected = false;
    }

    synchronized void simulateConnected() {
        connected = true;
        connectListeners.forEach(Runnable::run);
    }

    synchronized void simulateDisconnected() {
        connected = false;
    }

    synchronized boolean started() {
        return started;
    }

    synchronized boolean closed() {
        return closed;
    }

    synchronized List<TopicMessage> messages() {
        return List.copyOf(messages);
    }

    synchronized List<TopicMessage> onTopic(String topic) {
        return messages.stream().filter(message -> message.topic().equals(topic)).toList();
    }

    synchronized String lastPayload(String topic) {
        List<TopicMessage> found = onTopic(topic);
        return found.isEmpty() ? null : found.get(found.size() - 1).payload();
    }

    synchronized Map<String, Object> lastPayloadAsMap(String topic) {
        String payload = lastPayload(topic);
        if (payload == null) {
            return null;
        }
        try {
            return new ObjectMapper().readValue(payload, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("payload is not JSON: " + payload, e);
        }
    }

    synchronized void clear() {
        messages.clear();
    }
}
