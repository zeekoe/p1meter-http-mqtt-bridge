package nl.zeekoe.p1mqtt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class AppConfig {

    public static final String SERVER_BIND = "server.bind";
    public static final String SERVER_PORT = "server.port";
    public static final String SERVER_PATH = "server.path";
    public static final String SERVER_TOKEN = "server.token";
    public static final String SERVER_THREADS = "server.threads";
    public static final String SERVER_MAX_BODY_BYTES = "server.maxBodyBytes";
    public static final String SERVER_LOG_REQUESTS = "server.logRequests";

    public static final String MQTT_ENABLED = "mqtt.enabled";
    public static final String MQTT_SERVER = "mqtt.server";
    public static final String MQTT_PORT = "mqtt.port";
    public static final String MQTT_USERNAME = "mqtt.username";
    public static final String MQTT_PASSWORD = "mqtt.password";
    public static final String MQTT_CLIENT_ID = "mqtt.clientId";
    public static final String MQTT_TOPIC_PREFIX = "mqtt.topicPrefix";
    public static final String MQTT_DISCOVERY_PREFIX = "mqtt.discoveryPrefix";
    public static final String MQTT_QOS = "mqtt.qos";
    public static final String MQTT_RETAIN = "mqtt.retain";
    public static final String MQTT_RETAIN_STATE = "mqtt.retainState";
    public static final String MQTT_SSL = "mqtt.ssl";
    public static final String MQTT_KEEP_ALIVE = "mqtt.keepAliveSeconds";
    public static final String MQTT_DRY_RUN = "mqtt.dryRun";

    public static final String HA_DEVICE_NAME = "ha.deviceName";
    public static final String HA_DEVICE_ID = "ha.deviceId";
    public static final String HA_DEVICE_MANUFACTURER = "ha.deviceManufacturer";
    public static final String HA_DEVICE_MODEL = "ha.deviceModel";
    public static final String HA_EXPIRE_AFTER = "ha.expireAfterSeconds";
    public static final String HA_ENERGY_SCALE = "ha.energyScale";
    public static final String HA_DIAGNOSTIC_SENSORS = "ha.includeDiagnosticSensors";

    public static final String PUBLISH_REPUBLISH_INTERVAL = "publish.republishIntervalSeconds";
    public static final String PUBLISH_JSON_STATE = "publish.jsonState";
    public static final String LOG_LEVEL = "log.level";

    private static final Set<String> LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Set<String> BOOLEAN_KEYS = Set.of(
            SERVER_LOG_REQUESTS, MQTT_ENABLED, MQTT_RETAIN, MQTT_RETAIN_STATE, MQTT_SSL, MQTT_DRY_RUN,
            HA_DIAGNOSTIC_SENSORS, PUBLISH_JSON_STATE);
    private static final Set<String> SECRET_KEYS = Set.of(MQTT_PASSWORD, SERVER_TOKEN);
    private static final String ENV_PREFIX = "P1MQTT_";
    private static final String CONFIG_ARG = "--config";
    private static final String RESOURCE = "/application.properties";

    private final Map<String, String> values;
    private final Map<String, String> sources;
    private final List<String> warnings;
    private final Path configFile;

    private AppConfig(Map<String, String> values, Map<String, String> sources, List<String> warnings, Path configFile) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        this.warnings = List.copyOf(warnings);
        this.configFile = configFile;
    }

    public static AppConfig load(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        Properties defaults = readClasspathProperties(RESOURCE);
        for (String key : defaults.stringPropertyNames()) {
            values.put(key, defaults.getProperty(key));
            sources.put(key, "built-in default");
        }

        Path file = configFileFrom(args);
        if (file != null) {
            Properties properties = readFileProperties(file, warnings);
            for (String key : properties.stringPropertyNames()) {
                values.put(key, properties.getProperty(key));
                sources.put(key, "file " + file);
            }
        }

        for (String key : values.keySet()) {
            String envKey = envKeyFor(key);
            String envValue = System.getenv(envKey);
            if (envValue != null) {
                values.put(key, envValue);
                sources.put(key, "environment " + envKey);
            }
        }

        for (String arg : args) {
            if (!arg.startsWith("--") || arg.startsWith(CONFIG_ARG)) {
                continue;
            }
            int equals = arg.indexOf('=');
            if (equals < 0) {
                warnings.add("ignoring argument without '=': " + arg);
                continue;
            }
            String key = arg.substring(2, equals);
            if (!values.containsKey(key)) {
                warnings.add("ignoring unknown configuration key on the command line: " + key);
                continue;
            }
            values.put(key, arg.substring(equals + 1));
            sources.put(key, "command line");
        }

        return new AppConfig(values, sources, warnings, file);
    }

    static AppConfig of(Map<String, String> overrides) {
        Properties defaults = readClasspathProperties(RESOURCE);
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : defaults.stringPropertyNames()) {
            values.put(key, defaults.getProperty(key));
            sources.put(key, "built-in default");
        }
        overrides.forEach((key, value) -> {
            values.put(key, value);
            sources.put(key, "test");
        });
        return new AppConfig(values, sources, List.of(), null);
    }

    public List<String> validate() {
        requirePort(SERVER_PORT);
        requirePort(MQTT_PORT);
        int threads = intValue(SERVER_THREADS);
        if (threads < 1 || threads > 64) {
            throw new ConfigException(SERVER_THREADS + " must be between 1 and 64, was " + threads);
        }
        if (longValue(SERVER_MAX_BODY_BYTES) < 16) {
            throw new ConfigException(SERVER_MAX_BODY_BYTES + " must be at least 16");
        }
        int qos = intValue(MQTT_QOS);
        if (qos < 0 || qos > 2) {
            throw new ConfigException(MQTT_QOS + " must be 0, 1 or 2, was " + qos);
        }
        if (longValue(MQTT_KEEP_ALIVE) < 0) {
            throw new ConfigException(MQTT_KEEP_ALIVE + " must not be negative");
        }
        if (longValue(HA_EXPIRE_AFTER) < 0) {
            throw new ConfigException(HA_EXPIRE_AFTER + " must not be negative");
        }
        if (longValue(PUBLISH_REPUBLISH_INTERVAL) < 0) {
            throw new ConfigException(PUBLISH_REPUBLISH_INTERVAL + " must not be negative");
        }
        requireTopic(getString(MQTT_TOPIC_PREFIX), MQTT_TOPIC_PREFIX);
        requireTopic(getString(MQTT_DISCOVERY_PREFIX), MQTT_DISCOVERY_PREFIX);
        requireTopic(getString(HA_DEVICE_ID), HA_DEVICE_ID);
        for (String key : BOOLEAN_KEYS) {
            booleanValue(key);
        }
        if (new BigDecimal(getString(HA_ENERGY_SCALE)).signum() < 0) {
            throw new ConfigException(HA_ENERGY_SCALE + " must not be negative");
        }
        if (!LOG_LEVELS.contains(getString(LOG_LEVEL).toUpperCase(Locale.ROOT))) {
            throw new ConfigException(LOG_LEVEL + " must be one of " + LOG_LEVELS + ", was '" + getString(LOG_LEVEL) + "'");
        }
        if (booleanValue(HA_DIAGNOSTIC_SENSORS) && !booleanValue(MQTT_ENABLED)) {
            throw new ConfigException(HA_DIAGNOSTIC_SENSORS + " requires " + MQTT_ENABLED + " to be true");
        }
        if (booleanValue(MQTT_ENABLED) && !booleanValue(MQTT_DRY_RUN) && getString(MQTT_SERVER).isBlank()) {
            throw new ConfigException(MQTT_SERVER + " is required when " + MQTT_ENABLED + " is true and "
                    + MQTT_DRY_RUN + " is false");
        }
        if (getString(MQTT_USERNAME).isBlank() != getString(MQTT_PASSWORD).isEmpty()) {
            return List.of("only one of " + MQTT_USERNAME + " / " + MQTT_PASSWORD + " is set, check your configuration");
        }
        return List.of();
    }

    public String getString(String key) {
        String value = values.get(key);
        return value == null ? "" : value.trim();
    }

    public int intValue(String key) {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be a number, was '" + value + "'");
        }
    }

    public long longValue(String key) {
        String value = getString(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be a number, was '" + value + "'");
        }
    }

    public boolean booleanValue(String key) {
        String value = getString(key).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0", "" -> false;
            default -> throw new ConfigException(key + " must be a boolean, was '" + value + "'");
        };
    }

    public Duration durationValue(String key) {
        return Duration.ofSeconds(longValue(key));
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public String source(String key) {
        return sources.getOrDefault(key, "unknown");
    }

    public List<String> warnings() {
        return warnings;
    }

    public Path configFile() {
        return configFile;
    }

    public String describe() {
        List<String> sortedKeys = new ArrayList<>(values.keySet());
        sortedKeys.sort(String::compareTo);
        StringBuilder out = new StringBuilder();
        for (String key : sortedKeys) {
            String shown = SECRET_KEYS.contains(key) && !getString(key).isEmpty() ? "********" : getString(key);
            out.append(System.lineSeparator())
                    .append("  ").append(key).append(" = ").append(shown)
                    .append("  [").append(source(key)).append(']');
        }
        return out.toString();
    }

    public static List<String> knownKeys() {
        Properties defaults = readClasspathProperties(RESOURCE);
        List<String> keys = new ArrayList<>(defaults.stringPropertyNames());
        keys.sort(String::compareTo);
        return keys;
    }

    public static String envKeyFor(String key) {
        StringBuilder builder = new StringBuilder(ENV_PREFIX);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i > 0 && (c == '.' || c == '-')) {
                builder.append('_');
                continue;
            }
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(key.charAt(i - 1))) {
                builder.append('_');
            }
            builder.append(Character.toUpperCase(c));
        }
        return builder.toString();
    }

    public static String usage() {
        return """
                Usage: java -jar p1mqtt.jar [options]

                  --key=value      override a configuration key
                  --config=<file>  also read properties from <file>
                  --help           show this message

                Precedence, highest first:
                  command line   --mqtt.server=192.168.1.10
                  environment    P1MQTT_MQTT_SERVER=192.168.1.10
                  properties     --config=/etc/p1mqtt/application.properties
                  defaults       bundled application.properties

                Known configuration keys:
                """;
    }

    private void requirePort(String key) {
        int port = intValue(key);
        if (port < 0 || port > 65535) {
            throw new ConfigException(key + " must be between 0 and 65535, was " + port);
        }
    }

    private void requireTopic(String value, String key) {
        if (value.isEmpty()) {
            throw new ConfigException(key + " must not be empty");
        }
        if (value.contains("#") || value.contains("+")) {
            throw new ConfigException(key + " must not contain wildcards, was '" + value + "'");
        }
    }

    private static Path configFileFrom(String[] args) {
        String path = null;
        for (String arg : args) {
            if (arg.startsWith(CONFIG_ARG + "=")) {
                path = arg.substring((CONFIG_ARG + "=").length());
            }
        }
        if (path == null || path.isBlank()) {
            path = System.getenv(ENV_PREFIX + "CONFIG");
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new ConfigException("configuration file is not readable: " + file);
        }
        return file;
    }

    private static Properties readClasspathProperties(String resource) {
        Properties properties = new Properties();
        try (var stream = AppConfig.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new ConfigException("bundled resource " + resource + " is missing");
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private static Properties readFileProperties(Path file, List<String> warnings) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            warnings.add("could not fully read configuration file " + file + ": " + e.getMessage());
        }
        return properties;
    }
}
