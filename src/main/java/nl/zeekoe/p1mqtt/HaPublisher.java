package nl.zeekoe.p1mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HaPublisher {

    public static final String PAYLOAD_ONLINE = "online";
    public static final String PAYLOAD_OFFLINE = "offline";

    private static final Logger log = LoggerFactory.getLogger(HaPublisher.class);

    private final MqttGateway gateway;
    private final HaDiscovery discovery;
    private final List<SensorSpec> specs;
    private final String availabilityTopic;
    private final String jsonStateTopic;
    private final boolean retainState;
    private final boolean retainDiscovery;
    private final boolean publishJsonState;
    private final Duration republishInterval;
    private final Clock clock;
    private final Map<String, String> lastPayloads = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastPublishedAt = new ConcurrentHashMap<>();
    private volatile String lastJsonState;
    private volatile Instant lastPublishAt;
    private volatile long publishCount;
    private volatile long suppressedCount;

    public HaPublisher(AppConfig config, MqttGateway gateway, List<SensorSpec> specs) {
        this(config, gateway, specs, Clock.systemUTC());
    }

    public HaPublisher(AppConfig config, MqttGateway gateway, List<SensorSpec> specs, Clock clock) {
        this.gateway = gateway;
        this.specs = List.copyOf(specs);
        this.clock = clock;
        this.retainState = config.booleanValue(AppConfig.MQTT_RETAIN_STATE);
        this.retainDiscovery = config.booleanValue(AppConfig.MQTT_RETAIN);
        this.publishJsonState = config.booleanValue(AppConfig.PUBLISH_JSON_STATE);
        this.republishInterval = config.durationValue(AppConfig.PUBLISH_REPUBLISH_INTERVAL);
        this.discovery = new HaDiscovery(config);
        this.availabilityTopic = discovery.availabilityTopic();
        this.jsonStateTopic = discovery.topicPrefix() + "/state";
    }

    public void start() {
        gateway.addConnectListener(this::onConnected);
        gateway.start();
    }

    public HaDiscovery discovery() {
        return discovery;
    }

    public String stateTopic(SensorSpec spec) {
        return discovery.stateTopic(spec);
    }

    public String configTopic(SensorSpec spec) {
        return discovery.configTopic(spec);
    }

    public List<SensorSpec> specs() {
        return specs;
    }

    public MqttGateway gateway() {
        return gateway;
    }

    public boolean publishState(SensorSpec spec, String payload) {
        boolean changed = !payload.equals(lastPayloads.get(spec.id()));
        if (!changed && !isDue(spec)) {
            suppressedCount++;
            return false;
        }
        Instant now = clock.instant();
        lastPayloads.put(spec.id(), payload);
        lastPublishedAt.put(spec.id(), now);
        lastPublishAt = now;
        publishCount++;
        gateway.publish(new TopicMessage(stateTopic(spec), payload, retainState));
        if (changed) {
            log.info("{} = {} -> {}", spec.id(), payload, stateTopic(spec));
        }
        return true;
    }

    public void publishJsonState(Map<String, BigDecimal> values) {
        if (!publishJsonState || values.isEmpty()) {
            return;
        }
        Map<String, Number> payload = new LinkedHashMap<>();
        values.forEach((key, value) -> payload.put(key, Numbers.stripped(value)));
        String json = Json.write(payload);
        if (json.equals(lastJsonState)) {
            suppressedCount++;
            return;
        }
        lastJsonState = json;
        lastPublishAt = clock.instant();
        gateway.publish(new TopicMessage(jsonStateTopic, json, retainState));
    }

    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(lastPayloads);
    }

    public Instant lastPublishAt() {
        return lastPublishAt;
    }

    public long publishCount() {
        return publishCount;
    }

    public long suppressedCount() {
        return suppressedCount;
    }

    public void close() {
        gateway.publishAndWait(new TopicMessage(availabilityTopic, PAYLOAD_OFFLINE, true), Duration.ofSeconds(3));
        gateway.close();
    }

    private void onConnected() {
        for (TopicMessage message : discovery.messages(specs, retainDiscovery)) {
            gateway.publish(message);
        }
        gateway.publish(new TopicMessage(availabilityTopic, PAYLOAD_ONLINE, true));
        Instant now = clock.instant();
        for (SensorSpec spec : specs) {
            String payload = lastPayloads.get(spec.id());
            if (payload != null) {
                gateway.publish(new TopicMessage(stateTopic(spec), payload, retainState));
                lastPublishedAt.put(spec.id(), now);
            }
        }
        log.info("MQTT ready: discovery published for {} sensors as device '{}' on {}",
                specs.size(), discovery.deviceId(), discovery.discoveryPrefix());
    }

    private boolean isDue(SensorSpec spec) {
        if (republishInterval.isZero()) {
            return false;
        }
        Instant last = lastPublishedAt.get(spec.id());
        return last == null || last.plus(republishInterval).isBefore(clock.instant());
    }
}
