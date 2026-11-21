package nl.zeekoe.p1mqtt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class P1HttpServer {

    private static final Logger log = LoggerFactory.getLogger(P1HttpServer.class);
    private static final Set<String> READING_METHODS = Set.of("GET", "POST", "HEAD");
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final AppConfig config;
    private final P1Service service;
    private final HaPublisher publisher;
    private final Instant startedAt = Instant.now();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final String expectedPath;
    private final String bindAddress;
    private final int port;
    private final String token;
    private final boolean logRequests;
    private HttpServer server;
    private ExecutorService executor;

    public P1HttpServer(AppConfig config, P1Service service, HaPublisher publisher) {
        this.config = config;
        this.service = service;
        this.publisher = publisher;
        this.expectedPath = config.getString(AppConfig.SERVER_PATH);
        this.bindAddress = config.getString(AppConfig.SERVER_BIND);
        this.port = config.intValue(AppConfig.SERVER_PORT);
        this.token = config.getString(AppConfig.SERVER_TOKEN);
        this.logRequests = config.booleanValue(AppConfig.SERVER_LOG_REQUESTS);
    }

    public void start() throws IOException {
        InetSocketAddress address = bindAddress.isEmpty()
                ? new InetSocketAddress(port)
                : new InetSocketAddress(InetAddress.getByName(bindAddress), port);
        server = HttpServer.create(address, 32);
        executor = Executors.newFixedThreadPool(config.intValue(AppConfig.SERVER_THREADS), runnable -> {
            Thread thread = new Thread(runnable, "p1mqtt-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        log.info("HTTP endpoint listening on {}:{}", actualBindAddress(), actualPort());
        if (logRequests) {
            log.info("expected sender URL: http://{}:{}{}?type=command&param=udevice&idx=1&nvalue=0&svalue=mEAV=0;mEAT=0",
                    localAddress(), actualPort(), expectedPath);
        }
    }

    public int port() {
        return server == null ? port : actualPort();
    }

    public String url() {
        return "http://" + localAddress() + ":" + actualPort() + expectedPath;
    }

    public long requestCount() {
        return requestCount.get();
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("HTTP endpoint stopped");
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        long startedNanos = System.nanoTime();
        try {
            if (logRequests) {
                log.info("{} {} from {}", method, exchange.getRequestURI(), remote);
            }
            if ("/health".equals(path)) {
                respond(exchange, 200, "application/json; charset=utf-8", Json.write(status()));
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                respond(exchange, 200, "text/html; charset=utf-8", statusPage());
                return;
            }
            if ("/simple".equals(path) || "/simple.html".equals(path)) {
                respond(exchange, 200, "text/html; charset=utf-8", simpleStatusPage());
                return;
            }
            if (!READING_METHODS.contains(method)) {
                rejectedCount.incrementAndGet();
                respondError(exchange, 405, "method not allowed");
                return;
            }
            if (!expectedPath.isEmpty() && !expectedPath.equals(path)) {
                log.debug("request path {} differs from configured {} but is accepted anyway", path, expectedPath);
            }
            Map<String, String> params = readParams(exchange);
            if (!token.isEmpty() && !token.equals(params.get("token"))) {
                rejectedCount.incrementAndGet();
                respondError(exchange, 401, "invalid or missing token");
                return;
            }
            String svalue = params.get("svalue");
            if (svalue == null || svalue.isBlank()) {
                rejectedCount.incrementAndGet();
                respondError(exchange, 400, "missing svalue parameter");
                return;
            }
            Map<String, String> values = SValueParser.parseValues(svalue);
            if (values.isEmpty()) {
                rejectedCount.incrementAndGet();
                respondError(exchange, 400, "svalue does not contain any key=value pairs");
                return;
            }
            P1Reading reading = P1Reading.of(values, params.getOrDefault("idx", ""), Instant.now());
            P1Service.HandleResult result = service.handle(reading);
            Map<String, Object> body = Json.object();
            body.put("status", "OK");
            body.put("title", "Update Device");
            body.put("published", result.published());
            body.put("unchanged", result.unchanged());
            if (!result.complete()) {
                body.put("warnings", result.problems());
            }
            respond(exchange, 200, "application/json; charset=utf-8", Json.write(body));
        } catch (Exception e) {
            rejectedCount.incrementAndGet();
            log.error("failed to handle {} {} from {}", method, exchange.getRequestURI(), remote, e);
            respondError(exchange, 500, "internal error");
        } finally {
            if (logRequests) {
                log.debug("handled {} {} in {} ms", method, path, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            }
            exchange.close();
        }
    }

    private String simpleStatusPage() {
        StringBuilder html = new StringBuilder();
        service.lastReading().ifPresent(reading -> {
            html.append(reading.receivedAt()).append(" ; ");
            String powerUsage = publisher.snapshot().getOrDefault(SensorSpec.POWER_USAGE.id(), "-");
            String powerReturned = publisher.snapshot().getOrDefault(SensorSpec.POWER_RETURNED.id(), "-");
            html.append(powerUsage).append(" ; ");
            html.append("-").append(powerReturned);
        });
        return html.toString();
    }

    private Map<String, String> readParams(HttpExchange exchange) throws IOException {
        Map<String, String> params = SValueParser.parseQuery(exchange.getRequestURI().getRawQuery());
        if (!"POST".equals(exchange.getRequestMethod())) {
            return params;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("application/x-www-form-urlencoded")) {
            return params;
        }
        String body;
        try (InputStream stream = exchange.getRequestBody()) {
            body = new String(stream.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        }
        params.putAll(SValueParser.parseQuery(body));
        return params;
    }

    private Map<String, Object> status() {
        Map<String, Object> status = Json.object();
        status.put("status", publisher.gateway().connected() ? "ok" : "degraded");
        status.put("version", BuildInfo.version());
        status.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        status.put("requests", requestCount.get());
        status.put("rejected", rejectedCount.get());
        status.put("readings", service.receivedCount());
        status.put("publishes", publisher.publishCount());
        status.put("publishesSuppressed", publisher.suppressedCount());
        status.put("lastPublishAt", publisher.lastPublishAt() == null ? null : publisher.lastPublishAt().toString());
        Map<String, Object> mqtt = Json.object();
        mqtt.put("gateway", publisher.gateway().describe());
        mqtt.put("topicPrefix", publisher.discovery().topicPrefix());
        mqtt.put("discoveryPrefix", publisher.discovery().discoveryPrefix());
        mqtt.put("availabilityTopic", publisher.discovery().availabilityTopic());
        status.put("mqtt", mqtt);
        service.lastReading().ifPresent(reading -> {
            Map<String, Object> last = Json.object();
            last.put("receivedAt", reading.receivedAt().toString());
            last.put("deviceId", reading.deviceId());
            last.put("values", reading.values());
            status.put("lastReading", last);
        });
        Map<String, Object> sensors = Json.object();
        publisher.snapshot().forEach((id, value) -> sensors.put(id, value));
        status.put("sensors", sensors);
        return status;
    }

    private String statusPage() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta http-equiv=\"refresh\" content=\"10\"><title>p1mqtt</title>");
        html.append("<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:46rem}"
                + "table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:.35rem .6rem;"
                + "border-bottom:1px solid #ddd}th{font-weight:600}code{background:#f4f4f4;padding:.1rem .3rem}</style>");
        html.append("</head><body><h1>p1mqtt</h1>");
        html.append("<p>version ").append(escape(BuildInfo.version()))
                .append(", up ").append(Duration.ofSeconds(Duration.between(startedAt, Instant.now()).toSeconds()))
                .append(" s, mqtt ").append(escape(publisher.gateway().describe())).append("</p>");
        html.append("<h2>Sensors</h2><table><tr><th>entity</th><th>value</th><th>topic</th></tr>");
        publisher.specs().forEach(spec -> {
            String value = publisher.snapshot().getOrDefault(spec.id(), "-");
            html.append("<tr><td>").append(escape(spec.displayName())).append("</td><td>").append(escape(value))
                    .append(' ').append(escape(spec.unit())).append("</td><td><code>")
                    .append(escape(publisher.stateTopic(spec))).append("</code></td></tr>");
        });
        html.append("</table>");
        service.lastReading().ifPresent(reading -> {
            html.append("<h2>Last reading</h2><p>").append(reading.receivedAt())
                    .append(" from device ").append(escape(reading.deviceId())).append("</p><table>");
            reading.values().forEach((key, value) -> html.append("<tr><td>").append(escape(key))
                    .append("</td><td>").append(escape(value)).append("</td></tr>"));
            html.append("</table>");
        });
        html.append("<h2>Topics</h2><ul>");
        publisher.specs().forEach(spec ->
                html.append("<li><code>").append(escape(publisher.configTopic(spec))).append("</code></li>"));
        html.append("</ul></body></html>");
        return html.toString();
    }

    private void respondError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = Json.object();
        body.put("status", "ERROR");
        body.put("message", message);
        respond(exchange, status, "application/json; charset=utf-8", Json.write(body));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private int actualPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    private String actualBindAddress() {
        return server == null ? bindAddress : server.getAddress().getAddress().getHostAddress();
    }

    private String localAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("could not enumerate network interfaces: {}", e.getMessage());
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            return bindAddress.isEmpty() ? "127.0.0.1" : bindAddress;
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static List<String> readingMethods() {
        return List.copyOf(READING_METHODS);
    }
}
