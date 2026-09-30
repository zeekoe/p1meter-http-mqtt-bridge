package nl.zeekoe.p1mqtt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P1HttpServerTest {

    private static final String SAMPLE_QUERY =
            "type=command&param=udevice&idx=294&nvalue=0&svalue=mEVLT=10584731;mEVHT=10414854;"
                    + "meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0";

    private final RecordingMqttGateway gateway = new RecordingMqttGateway();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private P1HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        startServer(Map.of("server.port", "0"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private void startServer(Map<String, String> overrides) throws IOException {
        AppConfig config = AppConfig.of(overrides);
        HaPublisher publisher = new HaPublisher(config, gateway, P1Service.activeSpecs(true));
        publisher.start();
        gateway.clear();
        P1Service service = new P1Service(config, publisher);
        server = new P1HttpServer(config, service, publisher);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @Test
    void acceptsTheExactRequestOfTheP1Sender() throws Exception {
        HttpResponse<String> response = get("/p1meter.php?" + SAMPLE_QUERY);

        assertEquals(200, response.statusCode());
        Map<String, Object> body = TestJson.parse(response.body());
        assertEquals("OK", body.get("status"));
        assertEquals("Update Device", body.get("title"));
        assertEquals(8, body.get("published"));
        assertEquals("418", gateway.lastPayload("p1meter/power_usage/state"));
        assertEquals("10584.731", gateway.lastPayload("p1meter/energy_imported_low/state"));
    }

    @Test
    void acceptsAnyPathSoItIsADropInReplacement() throws Exception {
        HttpResponse<String> response = get("/anything/else?" + SAMPLE_QUERY);

        assertEquals(200, response.statusCode());
        assertEquals("418", gateway.lastPayload("p1meter/power_usage/state"));
    }

    @Test
    void acceptsPercentEncodedValues() throws Exception {
        HttpResponse<String> response = get("/p1meter.php?type=command&svalue=mEAV%3D418%3BmEAT%3D250");

        assertEquals(200, response.statusCode());
        assertEquals("418", gateway.lastPayload("p1meter/power_usage/state"));
        assertEquals("250", gateway.lastPayload("p1meter/power_returned/state"));
    }

    @Test
    void acceptsPostWithAFormEncodedBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/p1meter.php"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("type=command&svalue=mEAV=99;mEAT=1"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("99", gateway.lastPayload("p1meter/power_usage/state"));
    }

    @Test
    void rejectsRequestsWithoutSvalue() throws Exception {
        HttpResponse<String> response = get("/p1meter.php?type=command&param=udevice&idx=294");

        assertEquals(400, response.statusCode());
        assertEquals("ERROR", TestJson.parse(response.body()).get("status"));
        assertTrue(gateway.messages().isEmpty());
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/p1meter.php"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void enforcesTheTokenWhenConfigured() throws Exception {
        stopServer();
        startServer(Map.of("server.port", "0", "server.token", "s3cret"));

        assertEquals(401, get("/p1meter.php?" + SAMPLE_QUERY).statusCode());
        assertEquals(401, get("/p1meter.php?token=wrong&" + SAMPLE_QUERY).statusCode());
        assertEquals(200, get("/p1meter.php?token=s3cret&" + SAMPLE_QUERY).statusCode());
        assertEquals("418", gateway.lastPayload("p1meter/power_usage/state"));
    }

    @Test
    void healthEndpointReportsStatus() throws Exception {
        get("/p1meter.php?" + SAMPLE_QUERY);

        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        Map<String, Object> body = TestJson.parse(response.body());
        assertEquals("ok", body.get("status"));
        assertEquals(1, body.get("readings"));
        assertEquals("418", ((Map<?, ?>) body.get("sensors")).get("power_usage"));
        assertNotEquals(null, body.get("version"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    void rootServesAStatusPage() throws Exception {
        get("/p1meter.php?" + SAMPLE_QUERY);

        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<html"));
        assertTrue(response.body().contains("p1meter/power_usage/state"));
        assertTrue(response.body().contains("Power Usage"));
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
