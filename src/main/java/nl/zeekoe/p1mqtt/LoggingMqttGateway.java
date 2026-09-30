package nl.zeekoe.p1mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class LoggingMqttGateway implements MqttGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingMqttGateway.class);

    private final List<Runnable> connectListeners = new ArrayList<>();
    private volatile boolean closed;

    @Override
    public void start() {
        log.info("dry-run mode: messages are logged instead of published to a broker");
        connectListeners.forEach(Runnable::run);
    }

    @Override
    public boolean connected() {
        return !closed;
    }

    @Override
    public void publish(TopicMessage message) {
        log.info("[dry-run] publish topic={} retain={} payload={}", message.topic(), message.retain(), message.payload());
    }

    @Override
    public void publishAndWait(TopicMessage message, Duration timeout) {
        publish(message);
    }

    @Override
    public void addConnectListener(Runnable listener) {
        connectListeners.add(listener);
    }

    @Override
    public String describe() {
        return "dry-run (no broker)";
    }

    @Override
    public void close() {
        closed = true;
    }
}
