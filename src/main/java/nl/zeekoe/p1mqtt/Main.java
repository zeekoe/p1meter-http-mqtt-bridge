package nl.zeekoe.p1mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        if (hasHelpFlag(args)) {
            printUsage();
            return;
        }

        AppConfig config;
        try {
            config = AppConfig.load(args);
            System.setProperty(AppConfig.LOG_LEVEL, config.getString(AppConfig.LOG_LEVEL).toUpperCase(java.util.Locale.ROOT));
            List<String> validationWarnings = config.validate();
            config.warnings().forEach(warning -> LoggerFactory.getLogger(Main.class).warn(warning));
            validationWarnings.forEach(warning -> LoggerFactory.getLogger(Main.class).warn(warning));
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        log.info("p1mqtt {} starting with configuration:{}{}", BuildInfo.version(), System.lineSeparator(), config.describe());

        MqttGateway gateway = createGateway(config);
        List<SensorSpec> specs = P1Service.activeSpecs(config.booleanValue(AppConfig.HA_DIAGNOSTIC_SENSORS));
        HaPublisher publisher = new HaPublisher(config, gateway, specs);
        P1Service service = new P1Service(config, publisher);
        P1HttpServer http = new P1HttpServer(config, service, publisher);

        try {
            publisher.start();
            http.start();
        } catch (IOException e) {
            log.error("could not start HTTP endpoint on port {}: {}", config.intValue(AppConfig.SERVER_PORT), e.getMessage());
            publisher.close();
            System.exit(1);
            return;
        }

        log.info("p1mqtt is ready; point your P1 sender at {}", http.url());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            http.stop();
            publisher.close();
            log.info("p1mqtt stopped");
        }, "p1mqtt-shutdown"));
    }

    private static MqttGateway createGateway(AppConfig config) {
        if (!config.booleanValue(AppConfig.MQTT_ENABLED)) {
            log.warn("{} is false, no MQTT messages will be published", AppConfig.MQTT_ENABLED);
            return new LoggingMqttGateway();
        }
        if (config.booleanValue(AppConfig.MQTT_DRY_RUN)) {
            return new LoggingMqttGateway();
        }
        return new HiveMqttGateway(config,
                new TopicMessage(config.getString(AppConfig.MQTT_TOPIC_PREFIX) + "/availability",
                        HaPublisher.PAYLOAD_OFFLINE, true));
    }

    private static boolean hasHelpFlag(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println(AppConfig.usage());
        AppConfig.knownKeys().forEach(key -> System.out.println("  " + key));
    }
}
