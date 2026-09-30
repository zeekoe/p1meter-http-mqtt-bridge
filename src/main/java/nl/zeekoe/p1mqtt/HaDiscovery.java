package nl.zeekoe.p1mqtt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HaDiscovery {

    private final String topicPrefix;
    private final String discoveryPrefix;
    private final String availabilityTopic;
    private final int expireAfterSeconds;
    private final String deviceId;
    private final Map<String, Object> device;

    public HaDiscovery(AppConfig config) {
        this.topicPrefix = config.getString(AppConfig.MQTT_TOPIC_PREFIX);
        this.discoveryPrefix = config.getString(AppConfig.MQTT_DISCOVERY_PREFIX);
        this.availabilityTopic = topicPrefix + "/availability";
        this.expireAfterSeconds = (int) config.longValue(AppConfig.HA_EXPIRE_AFTER);
        this.deviceId = config.getString(AppConfig.HA_DEVICE_ID);
        Map<String, Object> deviceBlock = new LinkedHashMap<>();
        deviceBlock.put("identifiers", List.of(deviceId));
        deviceBlock.put("name", config.getString(AppConfig.HA_DEVICE_NAME));
        if (!config.getString(AppConfig.HA_DEVICE_MANUFACTURER).isEmpty()) {
            deviceBlock.put("manufacturer", config.getString(AppConfig.HA_DEVICE_MANUFACTURER));
        }
        if (!config.getString(AppConfig.HA_DEVICE_MODEL).isEmpty()) {
            deviceBlock.put("model", config.getString(AppConfig.HA_DEVICE_MODEL));
        }
        String version = BuildInfo.version();
        if (!version.isEmpty()) {
            deviceBlock.put("sw_version", version);
        }
        this.device = Map.copyOf(deviceBlock);
    }

    public String deviceId() {
        return deviceId;
    }

    public String topicPrefix() {
        return topicPrefix;
    }

    public String discoveryPrefix() {
        return discoveryPrefix;
    }

    public String availabilityTopic() {
        return availabilityTopic;
    }

    public String stateTopic(SensorSpec spec) {
        return spec.stateTopic(topicPrefix);
    }

    public String configTopic(SensorSpec spec) {
        return spec.discoveryTopic(discoveryPrefix, deviceId);
    }

    public String payload(SensorSpec spec) {
        String uniqueId = deviceId + "_" + spec.id();
        Map<String, Object> config = Json.object();
        config.put("name", spec.displayName());
        config.put("unique_id", uniqueId);
        config.put("object_id", uniqueId);
        config.put("state_topic", stateTopic(spec));
        config.put("unit_of_measurement", spec.unit());
        config.put("device_class", spec.deviceClass());
        config.put("state_class", spec.stateClass());
        config.put("icon", spec.icon());
        config.put("suggested_display_precision", spec.displayPrecision());
        if (spec.diagnostic()) {
            config.put("entity_category", "diagnostic");
        }
        if (expireAfterSeconds > 0) {
            config.put("expire_after", expireAfterSeconds);
        }
        Map<String, Object> availability = Json.object();
        availability.put("topic", availabilityTopic);
        availability.put("payload_available", HaPublisher.PAYLOAD_ONLINE);
        availability.put("payload_not_available", HaPublisher.PAYLOAD_OFFLINE);
        config.put("availability", List.of(availability));
        config.put("device", new LinkedHashMap<>(device));
        return Json.write(config);
    }

    public List<TopicMessage> messages(List<SensorSpec> specs, boolean retain) {
        List<TopicMessage> messages = new ArrayList<>(specs.size());
        for (SensorSpec spec : specs) {
            messages.add(new TopicMessage(configTopic(spec), payload(spec), retain));
        }
        return messages;
    }
}
