package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaDiscoveryTest {

    private static HaDiscovery discovery(Map<String, String> overrides) {
        return new HaDiscovery(AppConfig.of(overrides));
    }

    @Test
    void buildsAPowerSensorPayload() {
        Map<String, Object> payload = TestJson.parse(discovery(Map.of()).payload(SensorSpec.POWER_USAGE));

        assertEquals("Power Usage", payload.get("name"));
        assertEquals("p1_meter_power_usage", payload.get("unique_id"));
        assertEquals("p1_meter_power_usage", payload.get("object_id"));
        assertEquals("p1meter/power_usage/state", payload.get("state_topic"));
        assertEquals("W", payload.get("unit_of_measurement"));
        assertEquals("power", payload.get("device_class"));
        assertEquals("measurement", payload.get("state_class"));
        assertEquals(0, payload.get("suggested_display_precision"));
        assertFalse(payload.containsKey("entity_category"));
        assertFalse(payload.containsKey("expire_after"));
        assertEquals(List.of(Map.of("topic", "p1meter/availability",
                "payload_available", "online", "payload_not_available", "offline")), payload.get("availability"));
    }

    @Test
    void totalsAreEnergySensorsWithStatistics() {
        Map<String, Object> imported = TestJson.parse(discovery(Map.of()).payload(SensorSpec.ENERGY_IMPORTED));

        assertEquals("kWh", imported.get("unit_of_measurement"));
        assertEquals("energy", imported.get("device_class"));
        assertEquals("total_increasing", imported.get("state_class"));
        assertEquals("p1meter/energy_imported/state", imported.get("state_topic"));
    }

    @Test
    void diagnosticCountersAreMarkedAsSuch() {
        Map<String, Object> low = TestJson.parse(discovery(Map.of()).payload(SensorSpec.ENERGY_IMPORTED_LOW));

        assertEquals("diagnostic", low.get("entity_category"));
        assertEquals("measurement", low.get("state_class"));
    }

    @Test
    void expireAfterIsOnlySentWhenConfigured() {
        Map<String, Object> payload = TestJson.parse(
                discovery(Map.of("ha.expireAfterSeconds", "300")).payload(SensorSpec.POWER_USAGE));

        assertEquals(300, payload.get("expire_after"));
    }

    @Test
    void deviceBlockIdentifiesTheBridge() {
        AppConfig config = AppConfig.of(Map.of(
                "ha.deviceId", "smart_meter",
                "ha.deviceName", "Fusie 1",
                "ha.deviceManufacturer", "Landis+Gyr",
                "ha.deviceModel", "E360"));
        Map<String, Object> payload = TestJson.parse(new HaDiscovery(config).payload(SensorSpec.POWER_USAGE));

        assertEquals("smart_meter_power_usage", payload.get("unique_id"));
        assertEquals("homeassistant/sensor/smart_meter_power_usage/config",
                new HaDiscovery(config).configTopic(SensorSpec.POWER_USAGE));

        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) payload.get("device");
        assertEquals(List.of("smart_meter"), device.get("identifiers"));
        assertEquals("Fusie 1", device.get("name"));
        assertEquals("Landis+Gyr", device.get("manufacturer"));
        assertEquals("E360", device.get("model"));
    }

    @Test
    void everySensorHasItsOwnStateAndConfigTopic() {
        HaDiscovery haDiscovery = discovery(Map.of("ha.deviceId", "p1_meter"));
        List<SensorSpec> all = List.of(SensorSpec.values());
        List<String> stateTopics = all.stream().map(haDiscovery::stateTopic).toList();
        List<String> configTopics = all.stream().map(haDiscovery::configTopic).toList();

        assertEquals(all.size(), stateTopics.stream().distinct().count());
        assertEquals(all.size(), configTopics.stream().distinct().count());
        assertTrue(stateTopics.contains("p1meter/power_usage/state"));
        assertTrue(configTopics.contains("homeassistant/sensor/p1_meter_energy_exported/config"));
        assertEquals(all.size(), haDiscovery.messages(all, true).size());
        assertTrue(haDiscovery.messages(all, true).stream().allMatch(TopicMessage::retain));
    }

    @Test
    void stateAndConfigPrefixesAreIndependent() {
        HaDiscovery haDiscovery = discovery(Map.of(
                "mqtt.topicPrefix", "huis/p1",
                "mqtt.discoveryPrefix", "ha"));

        assertEquals("huis/p1/power_usage/state", haDiscovery.stateTopic(SensorSpec.POWER_USAGE));
        assertEquals("ha/sensor/p1_meter_power_usage/config", haDiscovery.configTopic(SensorSpec.POWER_USAGE));
        assertEquals("huis/p1/availability", haDiscovery.availabilityTopic());
    }
}
