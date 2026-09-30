package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveMqttGatewayTest {

    @Test
    @Timeout(30)
    void connectsToBrokerAndPublishesDiscoveryAndState() throws Exception {
        try (TestMqttBroker broker = new TestMqttBroker()) {
            AppConfig config = AppConfig.of(Map.of(
                    "mqtt.server", "127.0.0.1",
                    "mqtt.port", String.valueOf(broker.port()),
                    "mqtt.clientId", "p1mqtt-test",
                    "mqtt.qos", "1",
                    "mqtt.dryRun", "false",
                    "mqtt.keepAliveSeconds", "10"));
            TopicMessage will = new TopicMessage("p1meter/availability", "offline", true);

            try (HiveMqttGateway gateway = new HiveMqttGateway(config, will)) {
                gateway.start();
                assertTrue(broker.awaitConnected(Duration.ofSeconds(10)), "client never connected");
                awaitConnected(gateway, Duration.ofSeconds(10));

                gateway.publishAndWait(new TopicMessage(
                        "homeassistant/sensor/p1_meter_power_usage/config", "{\"name\":\"Power Usage\"}", true),
                        Duration.ofSeconds(10));
                gateway.publishAndWait(new TopicMessage("p1meter/power_usage/state", "418", false),
                        Duration.ofSeconds(10));

                TestMqttBroker.Connect connect = broker.connects().get(0);
                assertEquals("p1mqtt-test", connect.clientId());
                assertEquals(5, connect.protocolVersion());
                assertTrue(connect.cleanStart());
                assertNull(connect.username());
                assertEquals("p1meter/availability", connect.willTopic());
                assertEquals("offline", connect.willPayload());

                TestMqttBroker.Publish discovery =
                        broker.publishesOn("homeassistant/sensor/p1_meter_power_usage/config").get(0);
                assertEquals("{\"name\":\"Power Usage\"}", discovery.payload());
                assertEquals(1, discovery.qos());
                assertTrue(discovery.retain());

                TestMqttBroker.Publish state = broker.publishesOn("p1meter/power_usage/state").get(0);
                assertEquals("418", state.payload());
                assertEquals(1, state.qos());
                assertFalse(state.retain());

                gateway.close();
                assertTrue(broker.awaitDisconnect(Duration.ofSeconds(10)), "client never sent DISCONNECT");
            }
        }
    }

    private static void awaitConnected(HiveMqttGateway gateway, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (gateway.connected()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(gateway.connected(), "gateway should report connected");
    }
}
