package nl.zeekoe.p1mqtt;

import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5WillPublish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HiveMqttGateway implements MqttGateway {

    private static final Logger log = LoggerFactory.getLogger(HiveMqttGateway.class);

    private final Mqtt5Client client;
    private final MqttQos qos;
    private final int keepAliveSeconds;
    private final String endpoint;
    private final List<Runnable> connectListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public HiveMqttGateway(AppConfig config, TopicMessage will) {
        this.endpoint = config.getString(AppConfig.MQTT_SERVER) + ":" + config.intValue(AppConfig.MQTT_PORT);
        this.keepAliveSeconds = (int) Math.min(Integer.MAX_VALUE, Math.max(0, config.longValue(AppConfig.MQTT_KEEP_ALIVE)));
        this.qos = switch (config.intValue(AppConfig.MQTT_QOS)) {
            case 0 -> MqttQos.AT_MOST_ONCE;
            case 2 -> MqttQos.EXACTLY_ONCE;
            default -> MqttQos.AT_LEAST_ONCE;
        };

        Mqtt5WillPublish willPublish = Mqtt5WillPublish.builder()
                .topic(will.topic())
                .payload(payload(will.payload()))
                .qos(qos)
                .retain(will.retain())
                .build();

        Mqtt5ClientBuilder builder = Mqtt5Client.builder()
                .identifier(config.getString(AppConfig.MQTT_CLIENT_ID))
                .serverHost(config.getString(AppConfig.MQTT_SERVER))
                .serverPort(config.intValue(AppConfig.MQTT_PORT))
                .willPublish(willPublish)
                .automaticReconnect()
                    .initialDelay(1, TimeUnit.SECONDS)
                    .maxDelay(30, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
                .addConnectedListener(context -> {
                    log.info("connected to MQTT broker {}", endpoint);
                    connectListeners.forEach(Runnable::run);
                })
                .addDisconnectedListener(context -> log.warn("disconnected from MQTT broker {}: {}",
                        endpoint, context.getSource() + (context.getCause() == null ? "" : " (" + context.getCause() + ")")));

        if (config.booleanValue(AppConfig.MQTT_SSL)) {
            builder = builder.sslWithDefaultConfig();
        }
        String username = config.getString(AppConfig.MQTT_USERNAME);
        if (!username.isEmpty()) {
            builder = builder.simpleAuth()
                    .username(username)
                    .password(config.getString(AppConfig.MQTT_PASSWORD).getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth();
        }
        this.client = builder.build();
    }

    @Override
    public void start() {
        Mqtt5Connect connect = Mqtt5Connect.builder()
                .keepAlive(keepAliveSeconds)
                .cleanStart(true)
                .build();
        client.toAsync().connect(connect).whenComplete((connAck, error) -> {
            if (error != null) {
                log.warn("initial connection to MQTT broker {} failed, will keep retrying: {}",
                        endpoint, rootMessage(error));
            } else {
                log.info("MQTT session established with {}", endpoint);
            }
        });
    }

    @Override
    public boolean connected() {
        MqttClientState state = client.getState();
        return state != null && state.isConnected();
    }

    @Override
    public void publish(TopicMessage message) {
        send(message).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("publish to {} failed: {}", message.topic(), rootMessage(error));
            } else if (log.isTraceEnabled()) {
                log.trace("published {} retain={} payload={}", message.topic(), message.retain(), message.payload());
            }
        });
    }

    @Override
    public void publishAndWait(TopicMessage message, Duration timeout) {
        try {
            send(message).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("publish to {} did not complete within {}", message.topic(), timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("publish to {} failed: {}", message.topic(), rootMessage(e));
        }
    }

    @Override
    public void addConnectListener(Runnable listener) {
        connectListeners.add(listener);
    }

    @Override
    public String describe() {
        return endpoint + (connected() ? " (connected)" : " (not connected)");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (connected()) {
                client.toAsync().disconnect().get(5, TimeUnit.SECONDS);
                log.info("disconnected from MQTT broker {}", endpoint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("error while disconnecting from {}: {}", endpoint, rootMessage(e));
        }
    }

    private CompletableFuture<Mqtt5PublishResult> send(TopicMessage message) {
        return client.toAsync().publishWith()
                .topic(message.topic())
                .payload(payload(message.payload()))
                .qos(qos)
                .retain(message.retain())
                .send();
    }

    private static byte[] payload(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.toString() : message;
    }
}
