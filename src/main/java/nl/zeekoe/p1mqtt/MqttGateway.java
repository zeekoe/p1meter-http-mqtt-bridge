package nl.zeekoe.p1mqtt;

import java.time.Duration;

public interface MqttGateway extends AutoCloseable {

    void start();

    boolean connected();

    void publish(TopicMessage message);

    void publishAndWait(TopicMessage message, Duration timeout);

    void addConnectListener(Runnable listener);

    String describe();

    @Override
    void close();
}
