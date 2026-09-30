package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void commandLineOverridesDefaults() {
        AppConfig config = AppConfig.load(new String[]{"--mqtt.server=10.0.0.5", "--server.port=9000"});

        assertEquals("10.0.0.5", config.getString(AppConfig.MQTT_SERVER));
        assertEquals(9000, config.intValue(AppConfig.SERVER_PORT));
        assertEquals("command line", config.source(AppConfig.MQTT_SERVER));
        assertTrue(config.validate().isEmpty());
    }

    @Test
    void commandLineOverridesAPropertiesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.properties");
        Files.writeString(file, "mqtt.server=from.file\nmqtt.port=1884\n");

        AppConfig config = AppConfig.load(new String[]{"--config=" + file, "--mqtt.port=1885"});

        assertEquals("from.file", config.getString(AppConfig.MQTT_SERVER));
        assertEquals(1885, config.intValue(AppConfig.MQTT_PORT));
        assertTrue(config.source(AppConfig.MQTT_SERVER).contains("file"));
    }

    @Test
    void environmentVariableNamesAreDerivedFromKeys() {
        assertEquals("P1MQTT_MQTT_SERVER", AppConfig.envKeyFor("mqtt.server"));
        assertEquals("P1MQTT_HA_EXPIRE_AFTER_SECONDS", AppConfig.envKeyFor("ha.expireAfterSeconds"));
    }

    @Test
    void unknownCommandLineKeysAreReportedNotFatal() {
        AppConfig config = AppConfig.load(new String[]{"--nope=1", "--broken", "--mqtt.dryRun=true"});

        assertEquals(1, config.warnings().stream().filter(w -> w.contains("nope")).count());
        assertEquals(1, config.warnings().stream().filter(w -> w.contains("broken")).count());
        assertTrue(config.booleanValue(AppConfig.MQTT_DRY_RUN));
    }

    @Test
    void secretsAreMaskedInTheDescription() {
        AppConfig config = AppConfig.of(Map.of("mqtt.password", "hunter2", "server.token", "abc"));

        String description = config.describe();

        assertFalse(description.contains("hunter2"));
        assertFalse(description.contains("abc\n"));
        assertTrue(description.contains("mqtt.password = ********"));
    }

    @Test
    void missingBrokerIsRejectedUnlessDryRun() {
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of()).validate());
        assertTrue(AppConfig.of(Map.of("mqtt.dryRun", "true")).validate().isEmpty());
        assertTrue(AppConfig.of(Map.of("mqtt.server", "10.0.0.5")).validate().isEmpty());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of("mqtt.server", "x", "server.port", "70000")).validate());
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of("mqtt.qos", "9", "mqtt.dryRun", "true")).validate());
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of("mqtt.retain", "maybe", "mqtt.dryRun", "true")).validate());
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of("log.level", "LOUD", "mqtt.dryRun", "true")).validate());
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of("mqtt.topicPrefix", "p1/#", "mqtt.dryRun", "true")).validate());
        assertThrows(ConfigException.class, () -> AppConfig.of(Map.of("ha.expireAfterSeconds", "-1", "mqtt.dryRun", "true")).validate());
    }

    @Test
    void halfConfiguredCredentialsProduceAWarning() {
        assertEquals(1, AppConfig.of(Map.of("mqtt.server", "x", "mqtt.username", "user")).validate().size());
    }

    @Test
    void booleansAcceptCommonSpellings() {
        AppConfig config = AppConfig.of(Map.of("mqtt.dryRun", "yes"));

        assertTrue(config.booleanValue(AppConfig.MQTT_DRY_RUN));
        assertFalse(AppConfig.of(Map.of("mqtt.dryRun", "off")).booleanValue(AppConfig.MQTT_DRY_RUN));
    }

    @Test
    void defaultsAreUsableOutOfTheBox() {
        AppConfig config = AppConfig.of(Map.of());

        assertEquals(8267, config.intValue(AppConfig.SERVER_PORT));
        assertEquals("/p1meter.php", config.getString(AppConfig.SERVER_PATH));
        assertEquals("p1meter", config.getString(AppConfig.MQTT_TOPIC_PREFIX));
        assertEquals("homeassistant", config.getString(AppConfig.MQTT_DISCOVERY_PREFIX));
        assertEquals(8, P1Service.activeSpecs(true).size());
        assertEquals(4, P1Service.activeSpecs(false).size());
    }
}
