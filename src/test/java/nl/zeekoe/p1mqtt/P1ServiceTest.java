package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P1ServiceTest {

    private static final String SAMPLE =
            "mEVLT=10584731;mEVHT=10414854;meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0";

    private final RecordingMqttGateway gateway = new RecordingMqttGateway();

    private final MutableClock clock = new MutableClock();

    private P1Service service(Map<String, String> overrides) {
        AppConfig config = AppConfig.of(overrides);
        HaPublisher publisher = new HaPublisher(config, gateway, P1Service.activeSpecs(true), clock);
        publisher.start();
        gateway.clear();
        return new P1Service(config, publisher, clock);
    }

    @Test
    void publishesEverySensorOfAReading() {
        P1Service service = service(Map.of());

        P1Service.HandleResult result = service.handle(reading(SAMPLE));

        assertEquals(8, result.published());
        assertTrue(result.complete());
        assertEquals("418", gateway.lastPayload("p1meter/power_usage/state"));
        assertEquals("0", gateway.lastPayload("p1meter/power_returned/state"));
        assertEquals("20999.585", gateway.lastPayload("p1meter/energy_imported/state"));
        assertEquals("10463.453", gateway.lastPayload("p1meter/energy_exported/state"));
        assertEquals("10584.731", gateway.lastPayload("p1meter/energy_imported_low/state"));
        assertEquals("10414.854", gateway.lastPayload("p1meter/energy_imported_high/state"));
        assertEquals("3255.478", gateway.lastPayload("p1meter/energy_exported_low/state"));
        assertEquals("7207.975", gateway.lastPayload("p1meter/energy_exported_high/state"));
    }

    @Test
    void energyScaleIsConfigurable() {
        P1Service service = service(Map.of("ha.energyScale", "1"));

        service.handle(reading(SAMPLE));

        assertEquals("10584731", gateway.lastPayload("p1meter/energy_imported_low/state"));
    }

    @Test
    void diagnosticSensorsCanBeDisabled() {
        AppConfig config = AppConfig.of(Map.of("ha.includeDiagnosticSensors", "false"));
        HaPublisher publisher = new HaPublisher(config, gateway, P1Service.activeSpecs(false), clock);
        publisher.start();
        gateway.clear();

        P1Service.HandleResult result = new P1Service(config, publisher, clock).handle(reading(SAMPLE));

        assertEquals(4, result.published());
        assertNull(gateway.lastPayload("p1meter/energy_imported_low/state"));
        assertEquals("418", gateway.lastPayload("p1meter/power_usage/state"));
    }

    @Test
    void unchangedValuesAreNotRepublished() {
        P1Service service = service(Map.of("publish.republishIntervalSeconds", "0"));

        service.handle(reading(SAMPLE));
        gateway.clear();
        P1Service.HandleResult result = service.handle(reading(SAMPLE));

        assertEquals(0, result.published());
        assertEquals(8, result.unchanged());
        assertTrue(gateway.messages().isEmpty());
    }

    @Test
    void changedValuesArePublishedAgain() {
        P1Service service = service(Map.of("publish.republishIntervalSeconds", "0"));

        service.handle(reading(SAMPLE));
        gateway.clear();
        P1Service.HandleResult result = service.handle(reading("mEVLT=10584731;mEVHT=10414854;"
                + "meOLT=3255478;mEOHT=7207975;mEAV=419;mEAT=0"));

        assertEquals(1, result.published());
        assertEquals(7, result.unchanged());
        assertEquals(1, gateway.onTopic("p1meter/power_usage/state").size());
        assertTrue(gateway.onTopic("p1meter/power_returned/state").isEmpty());
    }

    @Test
    void republishIntervalForcesPeriodicUpdates() {
        P1Service service = service(Map.of("publish.republishIntervalSeconds", "60"));

        service.handle(reading(SAMPLE));
        gateway.clear();
        clock.advance(Duration.ofSeconds(30));
        assertEquals(0, service.handle(reading(SAMPLE)).published());
        clock.advance(Duration.ofSeconds(31));

        P1Service.HandleResult result = service.handle(reading(SAMPLE));

        assertEquals(8, result.published());
        assertEquals(1, gateway.onTopic("p1meter/power_usage/state").size());
        assertEquals(8, gateway.messages().size());
    }

    @Test
    void missingValuesAreReportedAndLeaveTheStateUntouched() {
        P1Service service = service(Map.of());

        service.handle(reading(SAMPLE));
        gateway.clear();
        P1Service.HandleResult result = service.handle(reading("mEAV=512;mEAT=3"));

        assertFalse(result.complete());
        assertEquals(List.of("energy_imported missing (mEVLT/mEVHT)", "energy_exported missing (meOLT/meOHT)",
                "energy_imported_low missing (mEVLT)", "energy_imported_high missing (mEVHT)",
                "energy_exported_low missing (meOLT)", "energy_exported_high missing (meOHT)"), result.problems());
        assertEquals("512", gateway.lastPayload("p1meter/power_usage/state"));
        assertNull(gateway.lastPayload("p1meter/energy_imported/state"));
    }

    @Test
    void publishesACombinedJsonState() {
        P1Service service = service(Map.of());

        service.handle(reading(SAMPLE));

        assertEquals(Map.of("mEVLT", 10584731, "mEVHT", 10414854, "meOLT", 3255478,
                "mEOHT", 7207975, "mEAV", 418, "mEAT", 0), gateway.lastPayloadAsMap("p1meter/state"));
    }

    @Test
    void jsonStateCanBeDisabled() {
        P1Service service = service(Map.of("publish.jsonState", "false"));

        service.handle(reading(SAMPLE));

        assertTrue(gateway.onTopic("p1meter/state").isEmpty());
    }

    @Test
    void discoveryIsRetainedAndStateIsNot() {
        P1Service service = service(Map.of());

        service.handle(reading(SAMPLE));

        assertTrue(gateway.onTopic("p1meter/power_usage/state").stream().noneMatch(TopicMessage::retain));
        assertTrue(gateway.onTopic("homeassistant/sensor/p1_meter_power_usage/config").stream()
                .allMatch(TopicMessage::retain));
    }

    @Test
    void stateCanBeRetainedOnRequest() {
        P1Service service = service(Map.of("mqtt.retainState", "true"));

        service.handle(reading(SAMPLE));

        assertTrue(gateway.onTopic("p1meter/power_usage/state").stream().allMatch(TopicMessage::retain));
    }

    @Test
    void topicPrefixIsConfigurable() {
        P1Service service = service(Map.of("mqtt.topicPrefix", "huis/p1"));

        service.handle(reading(SAMPLE));

        assertEquals("418", gateway.lastPayload("huis/p1/power_usage/state"));
        assertTrue(gateway.messages().stream().noneMatch(m -> m.topic().startsWith("p1meter/")));
    }

    @Test
    void discoveryAndAvailabilityArePublishedOnConnect() {
        AppConfig config = AppConfig.of(Map.of());
        new HaPublisher(config, gateway, P1Service.activeSpecs(true)).start();

        assertEquals(9, gateway.messages().size());
        assertEquals(8, discoveryMessages());
        assertEquals("online", gateway.lastPayload("p1meter/availability"));
        assertTrue(gateway.onTopic("p1meter/availability").stream().allMatch(TopicMessage::retain));
    }

    @Test
    void reconnectRepublishesTheCachedState() {
        P1Service service = service(Map.of());
        service.handle(reading(SAMPLE));
        gateway.clear();
        gateway.simulateDisconnected();

        gateway.simulateConnected();

        assertEquals(17, gateway.messages().size());
        assertEquals(1, gateway.onTopic("p1meter/power_usage/state").size());
        assertEquals("online", gateway.lastPayload("p1meter/availability"));
    }

    @Test
    void closingPublishesTheOfflineAvailability() {
        AppConfig config = AppConfig.of(Map.of());
        HaPublisher publisher = new HaPublisher(config, gateway, P1Service.activeSpecs(true));
        publisher.start();
        gateway.clear();

        publisher.close();

        assertEquals("offline", gateway.lastPayload("p1meter/availability"));
        assertTrue(gateway.closed());
    }

    @Test
    void numbersAreFormattedWithoutRedundantZeros() {
        assertEquals("418", Numbers.format(new BigDecimal("418.0")));
        assertEquals("0", Numbers.format(new BigDecimal("0.000")));
        assertEquals("10584.731", Numbers.format(new BigDecimal("10584731").multiply(new BigDecimal("0.001"))));
        assertEquals(new BigDecimal("418"), Numbers.parse(" 418 "));
        assertEquals(new BigDecimal("1.5"), Numbers.parse("1,5"));
        assertNull(Numbers.parse("n/a"));
    }

    private long discoveryMessages() {
        return gateway.messages().stream()
                .filter(message -> message.topic().startsWith("homeassistant/sensor/"))
                .count();
    }

    private static P1Reading reading(String svalue) {
        return P1Reading.of(SValueParser.parseValues(svalue), "294", Instant.now());
    }
}
