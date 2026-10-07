package com.fleettwin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleettwin.ml.MlService;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole backend against the real things it talks to: TimescaleDB (so every Flyway migration and
 * every SQL statement runs), Redis, Mosquitto and MinIO, all started in Docker by Testcontainers.
 * The ML service is a small stand-in that is "down" (503) until a test switches it on, because the
 * backend has to work without it and with it.
 *
 * The tests run in order and build on each other, the way a day of use does: telemetry arrives,
 * raises an alert, becomes a recommendation, is worked on, and ends up in a report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIT {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "integration-admin-password";
    private static final String PASSWORD = "integration-user-password";

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.30.2-pg16").asCompatibleSubstituteFor("postgres"));
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-alpine")
            .withCommand("redis-server", "--requirepass", "redis-test").withExposedPorts(6379);
    static final GenericContainer<?> MQTT = new GenericContainer<>("eclipse-mosquitto:2.0.22")
            .withCommand("sh", "-c", "printf 'listener 1883\\nallow_anonymous true\\n' > /tmp/test.conf && exec mosquitto -c /tmp/test.conf")
            .withExposedPorts(1883);
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "cgr.dev/chainguard/minio@sha256:4cf4831a2bbcf13ddca09c1cbcc9faff716dd3c4247e0babc32864b8ee8e0034")
            .withCommand("server", "/data").withEnv("MINIO_ROOT_USER", "minio-test")
            .withEnv("MINIO_ROOT_PASSWORD", "minio-test-password").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    /** Stand-in for the ML service: fixed answers in its wire format, or 503 for everything while "down". */
    static final HttpServer ML;
    static volatile boolean mlUp;

    static {
        // Started once for the class and removed by Testcontainers when the JVM exits.
        DB.start();
        REDIS.start();
        MQTT.start();
        MINIO.start();
        try {
            ML = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ML.createContext("/", exchange -> {
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
            String reply = !mlUp ? null : switch (exchange.getRequestURI().getPath()) {
                case "/anomaly" -> "{\"is_anomaly\":true,\"score\":0.95,\"reasons\":[\"engine_temp_last=121.00\"],\"model_version\":\"stub\"}";
                case "/health-score" -> "{\"health_score\":46.5,\"deductions\":{}}";
                // only the brakes have a model, and vehicle 1's are nearly gone
                case "/rul" -> "brakes".equals(request.get("component").asText()) && request.get("vehicle_id").asInt() == 1
                        ? "{\"rul_days\":2.0,\"lower_bound\":1.0,\"upper_bound\":3.5,\"confidence\":0.8,\"model_version\":\"stub\"}" : null;
                // every stop goes to the first vehicle offered, in the order given
                case "/optimise-routes" -> {
                    StringBuilder stops = new StringBuilder();
                    for (JsonNode stop : request.get("stops")) {
                        stops.append(stops.isEmpty() ? "" : ",").append("{\"id\":").append(stop.get("id")).append(",\"arrival_s\":900}");
                    }
                    yield "{\"routes\":[{\"vehicle_id\":" + request.get("vehicles").get(0).get("id") + ",\"stops\":[" + stops
                            + "],\"distance_m\":12400,\"duration_s\":1800,\"geometry\":[[12.97,77.59],[12.95,77.6]]}],"
                            + "\"unassigned\":[],\"distance_source\":\"straight-line\",\"note\":\"stub\"}";
                }
                default -> null;
            };
            byte[] body = (reply == null ? "{\"detail\":\"not available\"}" : reply).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply != null ? 200 : mlUp ? 404 : 503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ML.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "redis-test");
        registry.add("fleet.mqtt.url", ApiIT::mqttUrl);
        registry.add("fleet.mqtt.username", () -> "test");
        registry.add("fleet.mqtt.password", () -> "test");
        registry.add("fleet.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("fleet.minio.access-key", () -> "minio-test");
        registry.add("fleet.minio.secret-key", () -> "minio-test-password");
        registry.add("fleet.security.jwt-secret", () -> "integration-test-secret-0123456789-abcdefghij");
        registry.add("fleet.security.admin-username", () -> ADMIN);
        registry.add("fleet.security.admin-password", () -> ADMIN_PASSWORD);
        registry.add("fleet.ml.url", () -> "http://127.0.0.1:" + ML.getAddress().getPort());
        // the tests call the scheduled jobs themselves, so that nothing changes behind their back
        registry.add("fleet.ml.interval-ms", () -> "3600000");
        registry.add("fleet.ml.rul-interval-ms", () -> "3600000");
        registry.add("fleet.twin.offline-after", () -> "1h");
    }

    private static String mqttUrl() {
        return "tcp://" + MQTT.getHost() + ":" + MQTT.getMappedPort(1883);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MlService mlService;
    @LocalServerPort
    private int port;

    // ---------------------------------------------------------------- helpers

    private record Reply(int status, JsonNode body, MockHttpServletResponse raw) {

        /** The reason given with an error status (what a real client reads as "message" in the error body). */
        String message() {
            return raw.getErrorMessage();
        }
    }

    private Reply call(String token, HttpMethod method, String path, Object body) throws Exception {
        var req = request(method, path);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MockHttpServletResponse response = mvc.perform(req).andReturn().getResponse();
        String type = response.getContentType();
        boolean isJson = type != null && type.contains("json") && response.getContentAsByteArray().length > 0;
        return new Reply(response.getStatus(), isJson ? json.readTree(response.getContentAsString(StandardCharsets.UTF_8)) : null, response);
    }

    private Reply get(String token, String path) throws Exception {
        return call(token, HttpMethod.GET, path, null);
    }

    private JsonNode login(String username, String password) throws Exception {
        Reply reply = call(null, HttpMethod.POST, "/api/auth/login", Map.of("username", username, "password", password));
        assertThat(reply.status()).as("login as %s", username).isEqualTo(200);
        return reply.body();
    }

    private String token(String username) throws Exception {
        return login(username, ADMIN.equals(username) ? ADMIN_PASSWORD : PASSWORD).get("accessToken").asText();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @Order(1)
    void firstAdminIsSeededAndTokensCanBeRefreshedAndRevoked() throws Exception {
        assertThat(call(null, HttpMethod.POST, "/api/auth/login", Map.of("username", ADMIN, "password", "wrong")).status()).isEqualTo(401);
        assertThat(get(null, "/api/vehicles").status()).isEqualTo(401);

        JsonNode session = login(ADMIN, ADMIN_PASSWORD);
        assertThat(session.get("role").asText()).isEqualTo("ADMIN");
        String access = session.get("accessToken").asText();
        assertThat(get(access, "/api/auth/me").body().get("username").asText()).isEqualTo(ADMIN);
        assertThat(get(access, "/api/auth/me").body().has("passwordHash")).isFalse();

        Map<String, String> refresh = Map.of("refreshToken", session.get("refreshToken").asText());
        assertThat(call(null, HttpMethod.POST, "/api/auth/refresh", refresh).status()).isEqualTo(200);
        assertThat(call(null, HttpMethod.POST, "/api/auth/refresh", Map.of("refreshToken", access)).status())
                .as("an access token is not a refresh token").isEqualTo(401);
        assertThat(call(access, HttpMethod.POST, "/api/auth/logout", null).status()).isEqualTo(204);
        assertThat(call(null, HttpMethod.POST, "/api/auth/refresh", refresh).status()).as("refresh after logout").isEqualTo(401);
    }

    @Test
    @Order(2)
    void adminManagesUsersAndCannotLockEveryoneOut() throws Exception {
        String admin = token(ADMIN);
        for (String[] user : new String[][] {{"maya", "FLEET_MANAGER"}, {"tina", "TECHNICIAN"}, {"vik", "VIEWER"}}) {
            Reply created = call(admin, HttpMethod.POST, "/api/users", Map.of("username", user[0], "password", PASSWORD, "role", user[1]));
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.body().get("role").asText()).isEqualTo(user[1]);
        }
        assertThat(call(admin, HttpMethod.POST, "/api/users", Map.of("username", "maya", "password", PASSWORD, "role", "VIEWER")).status()).isEqualTo(409);
        Reply weak = call(admin, HttpMethod.POST, "/api/users", Map.of("username", "weak", "password", "short", "role", "VIEWER"));
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.message()).contains("at least 10 characters");
        assertThat(call(admin, HttpMethod.POST, "/api/users", Map.of("username", "a b", "password", PASSWORD, "role", "VIEWER")).status()).isEqualTo(400);
        assertThat(call(token("maya"), HttpMethod.GET, "/api/users", null).status()).isEqualTo(403);

        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, ADMIN);
        assertThat(call(admin, HttpMethod.PATCH, "/api/users/" + adminId, Map.of("enabled", false)).status())
                .as("the last active admin cannot be disabled").isEqualTo(409);

        // disabling a user ends their sessions and stops them logging in; enabling brings them back
        long vikId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'vik'", Long.class);
        String vikRefresh = login("vik", PASSWORD).get("refreshToken").asText();
        assertThat(call(admin, HttpMethod.PATCH, "/api/users/" + vikId, Map.of("enabled", false)).status()).isEqualTo(200);
        assertThat(call(null, HttpMethod.POST, "/api/auth/refresh", Map.of("refreshToken", vikRefresh)).status()).isEqualTo(401);
        assertThat(call(null, HttpMethod.POST, "/api/auth/login", Map.of("username", "vik", "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(call(admin, HttpMethod.PATCH, "/api/users/" + vikId, Map.of("enabled", true)).status()).isEqualTo(200);
        assertThat(get(admin, "/api/users").body()).hasSize(4);
        assertThat(get(admin, "/api/admin/settings").body().get("routes").get("minRulDays").asDouble()).isEqualTo(3.0);
    }

    @Test
    @Order(3)
    void telemetryOverMqttUpdatesTheTwinAndRaisesOneAlert() throws Exception {
        Instant now = Instant.now();
        try (MqttClient client = new MqttClient(mqttUrl(), "integration-test-vehicle", new MemoryPersistence())) {
            client.connect();
            String healthy = "{\"ts\":\"%s\",\"lat\":12.97,\"lng\":77.59,\"speed\":40,\"engine_temp\":90,\"battery_voltage\":13.9,\"fuel_level\":70,\"brake_pad_wear\":20}";
            String overheating = "{\"ts\":\"%s\",\"lat\":12.98,\"lng\":77.59,\"speed\":45,\"engine_temp\":121,\"battery_voltage\":13.9,\"fuel_level\":70,\"brake_pad_wear\":20,\"injected_fault\":\"overheating\"}";
            client.publish("fleet/1/telemetry", new MqttMessage(healthy.formatted(now.minusSeconds(4)).getBytes()));
            client.publish("fleet/1/telemetry", new MqttMessage(overheating.formatted(now.minusSeconds(2)).getBytes()));
            client.publish("fleet/1/telemetry", new MqttMessage(overheating.formatted(now).getBytes()));
            client.publish("fleet/1/telemetry", new MqttMessage("not json".getBytes()));
            client.publish("fleet/999/telemetry", new MqttMessage(healthy.formatted(now).getBytes()));   // unknown vehicle
            client.disconnect();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry WHERE vehicle_id = 1", Integer.class)).isEqualTo(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry", Integer.class)).as("bad messages are dropped").isEqualTo(3);

        String tina = token("tina");
        JsonNode twin = get(tina, "/api/vehicles/1/twin").body();
        assertThat(twin.get("state").asText()).isEqualTo("MOVING");
        assertThat(twin.get("lat").asDouble()).isEqualTo(12.98);
        assertThat(twin.get("heading").asDouble()).as("moving north").isEqualTo(0.0);
        assertThat(twin.get("components").get("engine").asText()).isEqualTo("CRITICAL");
        assertThat(twin.get("components").get("battery").asText()).isEqualTo("OK");
        assertThat(twin.get("sensors").get("engineTemp").asDouble()).isEqualTo(121.0);
        assertThat(get(tina, "/api/vehicles").body()).hasSize(5);
        assertThat(get(tina, "/api/vehicles/999/twin").status()).isEqualTo(404);

        JsonNode alerts = get(tina, "/api/alerts?vehicleId=1&severity=CRITICAL").body();
        assertThat(alerts).as("two overheating readings, one alert").hasSize(1);
        assertThat(alerts.get(0).get("component").asText()).isEqualTo("engine");
        assertThat(alerts.get(0).get("source").asText()).isEqualTo("RULE");

        JsonNode history = get(tina, "/api/vehicles/1/telemetry?interval=1m").body();
        assertThat(history.size()).isBetween(1, 2);
        assertThat(get(tina, "/api/vehicles/1/telemetry?interval=nonsense").status()).isEqualTo(400);
    }

    @Test
    @Order(4)
    void acknowledgingAnAlertIsForWritersAndIsAudited() throws Exception {
        long alertId = jdbc.queryForObject("SELECT id FROM alerts WHERE vehicle_id = 1 AND severity = 'CRITICAL'", Long.class);
        assertThat(call(token("vik"), HttpMethod.POST, "/api/alerts/" + alertId + "/acknowledge", null).status()).isEqualTo(403);
        Reply done = call(token("tina"), HttpMethod.POST, "/api/alerts/" + alertId + "/acknowledge", null);
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().get("acknowledged").asBoolean()).isTrue();
        assertThat(get(token("vik"), "/api/alerts?vehicleId=1").body()).as("default filter is open alerts").isEmpty();
        assertThat(get(token("vik"), "/api/alerts?status=acknowledged").body()).hasSize(1);
        assertThat(get(token("vik"), "/api/alerts?status=bogus").status()).isEqualTo(400);

        JsonNode audit = get(token(ADMIN), "/api/audit-log?limit=1").body();
        assertThat(audit.get(0).get("username").asText()).isEqualTo("tina");
        assertThat(audit.get(0).get("action").asText()).isEqualTo("ACKNOWLEDGE");
        assertThat(audit.get(0).get("entityId").asLong()).isEqualTo(alertId);
    }

    @Test
    @Order(5)
    void criticalPartBecomesARecommendationAndCompletingItRecordsTheWork() throws Exception {
        String tina = token("tina");
        Reply recompute = call(tina, HttpMethod.POST, "/api/recommendations/recompute", null);
        assertThat(recompute.status()).isEqualTo(200);
        assertThat(recompute.body().get("created").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode engine = get(tina, "/api/recommendations?vehicleId=1&status=OPEN").body().get(0);
        assertThat(engine.get("component").asText()).isEqualTo("engine");
        assertThat(engine.get("priority").asText()).isEqualTo("URGENT");
        assertThat(engine.get("reason").asText()).contains("Current status is CRITICAL");
        assertThat(get(tina, "/api/vehicles/1/twin").body().get("openRecommendations").asInt()).isEqualTo(1);
        long id = engine.get("id").asLong();

        assertThat(call(token("vik"), HttpMethod.PATCH, "/api/recommendations/" + id, Map.of("status", "DONE")).status()).isEqualTo(403);
        assertThat(call(tina, HttpMethod.PATCH, "/api/recommendations/" + id, Map.of("status", "SCHEDULED")).body().get("status").asText()).isEqualTo("SCHEDULED");
        Reply done = call(tina, HttpMethod.PATCH, "/api/recommendations/" + id, Map.of("status", "DONE"));
        assertThat(done.body().get("status").asText()).isEqualTo("DONE");
        long recordId = done.body().get("maintenanceRecordId").asLong();
        assertThat(call(tina, HttpMethod.PATCH, "/api/recommendations/" + id, Map.of("status", "DISMISSED")).status())
                .as("a finished recommendation cannot change again").isEqualTo(409);
        assertThat(call(tina, HttpMethod.PATCH, "/api/recommendations/" + id, Map.of()).status()).isEqualTo(400);

        // The evidence was the status alone, with no wear prediction, so the advice was to inspect:
        // the work is recorded but no part is marked as renewed.
        JsonNode record = get(tina, "/api/maintenance-records/" + recordId).body();
        assertThat(record.get("cause").asText()).isEqualTo("RECOMMENDATION");
        assertThat(record.get("type").asText()).isEqualTo("Inspect engine");
        assertThat(record.get("vehicleId").asInt()).isEqualTo(1);
        assertThat(get(tina, "/api/vehicles/1/twin").body().get("openRecommendations").asInt()).isZero();
        assertThat(get(token(ADMIN), "/api/audit-log?limit=1").body().get(0).get("action").asText()).isEqualTo("COMPLETE");
    }

    @Test
    @Order(6)
    void maintenanceRecordsCanBeCreatedChangedAndDeleted() throws Exception {
        String tina = token("tina");
        Map<String, Object> oilChange = Map.of("vehicleId", 2, "type", "Oil change", "performedAt", "2026-10-01T08:00:00Z", "cost", 4200);
        assertThat(call(token("vik"), HttpMethod.POST, "/api/maintenance-records", oilChange).status()).isEqualTo(403);
        Reply created = call(tina, HttpMethod.POST, "/api/maintenance-records", oilChange);
        assertThat(created.status()).isEqualTo(201);
        long id = created.body().get("id").asLong();

        assertThat(call(tina, HttpMethod.POST, "/api/maintenance-records", Map.of("vehicleId", 2, "performedAt", "2026-10-01T08:00:00Z")).status())
                .as("type is required").isEqualTo(400);
        assertThat(call(tina, HttpMethod.POST, "/api/maintenance-records", Map.of("vehicleId", 999, "type", "x", "performedAt", "2026-10-01T08:00:00Z")).status())
                .as("unknown vehicle").isEqualTo(400);

        Reply updated = call(tina, HttpMethod.PUT, "/api/maintenance-records/" + id,
                Map.of("vehicleId", 2, "type", "Oil and filter change", "performedAt", "2026-10-01T08:00:00Z", "cost", 5100));
        assertThat(updated.body().get("type").asText()).isEqualTo("Oil and filter change");
        assertThat(get(tina, "/api/maintenance-records?vehicleId=2").body()).hasSize(1);
        assertThat(get(tina, "/api/vehicles/2/twin").body().get("maintenanceCount").asInt()).isEqualTo(1);

        assertThat(call(tina, HttpMethod.DELETE, "/api/maintenance-records/" + id, null).status()).isEqualTo(204);
        assertThat(get(tina, "/api/maintenance-records/" + id).status()).isEqualTo(404);
    }

    @Test
    @Order(7)
    void storedTelemetryIsAnalysedIntoTripsEventsScoresFuelAndUtilisation() throws Exception {
        // Vehicle 2 drives for 30 minutes at 60 km/h two hours ago, brakes hard once, and burns 15 litres.
        Instant start = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i <= 60; i++) {
            double km = i * 0.5;
            jdbc.update("""
                    INSERT INTO telemetry (vehicle_id, ts, lat, lng, speed, fuel_level, accel_min, accel_max, odometer_km)
                    VALUES (2, ?, ?, 76.64, 60, ?, ?, 0.5, ?)""",
                    Timestamp.from(start.plusSeconds(30L * i)), 12.30 + km / 110.57, 80 - i * (5.0 / 60), i == 30 ? -5.0 : -0.5, 50_000 + km);
        }
        Reply reanalysed = call(token(ADMIN), HttpMethod.POST, "/api/admin/reanalyse", null);
        assertThat(reanalysed.status()).isEqualTo(200);
        assertThat(reanalysed.body().get("trips").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(call(token("maya"), HttpMethod.POST, "/api/admin/reanalyse", null).status()).isEqualTo(403);

        String tina = token("tina");
        JsonNode trips = get(tina, "/api/vehicles/2/trips?period=24h").body();
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).get("distanceKm").asDouble()).isBetween(29.0, 31.0);
        assertThat(trips.get(0).get("fuelUsedL").asDouble()).isBetween(14.0, 16.0);

        JsonNode events = get(tina, "/api/vehicles/2/events?period=24h&type=HARSH_BRAKING").body();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("severity").asText()).isIn("MEDIUM", "HIGH");

        JsonNode score = get(tina, "/api/vehicles/2/driver-score?period=7d").body();
        assertThat(score.get("score").asDouble()).isBetween(50.0, 99.9);
        assertThat(score.get("eventCounts").get("HARSH_BRAKING").asInt()).isEqualTo(1);
        assertThat(get(tina, "/api/vehicles/2/driver-score?period=forever").status()).isEqualTo(400);

        JsonNode fuel = get(tina, "/api/vehicles/2/fuel?period=7d").body();
        assertThat(fuel.get("kmPerLitre").asDouble()).isBetween(1.8, 2.2);
        assertThat(get(tina, "/api/vehicles/2/routes?period=7d").body().get(0).get("trips").asInt()).isEqualTo(1);

        // fleet-wide analysis is for managers and viewers, not technicians
        assertThat(get(tina, "/api/fleet/fuel-summary").status()).isEqualTo(403);
        String maya = token("maya");
        JsonNode summary = get(maya, "/api/fleet/fuel-summary?period=7d").body();
        assertThat(summary.get("vehicles")).hasSize(5);
        // vehicle 2's 30 km plus the kilometre vehicle 1 covered between its MQTT readings
        assertThat(summary.get("totals").get("distanceKm").asDouble()).isBetween(29.0, 33.0);
        JsonNode utilisation = get(maya, "/api/fleet/utilisation?period=24h").body();
        assertThat(utilisation.get("vehicles").get(1).get("activeHours").asDouble()).isEqualTo(0.5);
        assertThat(utilisation.get("vehicles").get(0).get("usage").asText()).isEqualTo("UNDER_USED");
    }

    @Test
    @Order(8)
    void routePlanningExplainsExclusionsAndSaysSoWhenTheMlServiceIsDown() throws Exception {
        String maya = token("maya");
        Map<String, Object> stop = Map.of("name", "Depot run", "lat", 12.95, "lng", 77.60);
        assertThat(call(token("tina"), HttpMethod.POST, "/api/routes/optimise", Map.of("stops", new Object[] {stop})).status()).isEqualTo(403);
        assertThat(call(maya, HttpMethod.POST, "/api/routes/optimise", Map.of("stops", new Object[0])).status()).isEqualTo(400);
        assertThat(call(maya, HttpMethod.POST, "/api/routes/optimise", Map.of("stops", new Object[] {Map.of("lat", 95, "lng", 77.6)})).status())
                .as("latitude out of range").isEqualTo(400);

        // No depot: vehicles 2 to 5 have never reported a position, so only vehicle 1 could go. Raise an
        // alert on it and nobody is left, which is decided before the solver is needed.
        jdbc.update("INSERT INTO alerts (vehicle_id, component, severity, message, source) VALUES (1, 'brakes', 'CRITICAL', 'test', 'RULE')");
        Reply nobody = call(maya, HttpMethod.POST, "/api/routes/optimise", Map.of("stops", new Object[] {stop}));
        assertThat(nobody.status()).isEqualTo(200);
        assertThat(nobody.body().get("routes")).isEmpty();
        assertThat(nobody.body().get("excluded")).hasSize(5);
        assertThat(nobody.body().get("excluded").get(0).get("reasons").get(0).asText()).isEqualTo("1 open critical alert");
        assertThat(nobody.body().get("excluded").get(1).get("reasons").get(0).asText()).startsWith("Position unknown");

        // With a depot four vehicles are fit, the solver is needed, and it is down.
        Reply noSolver = call(maya, HttpMethod.POST, "/api/routes/optimise",
                Map.of("stops", new Object[] {stop}, "depot", Map.of("lat", 12.97, "lng", 77.59)));
        assertThat(noSolver.status()).isEqualTo(503);
        assertThat(noSolver.message()).contains("ML service");
    }

    @Test
    @Order(9)
    void reportsAreGeneratedStoredInMinioListedAndDownloadable() throws Exception {
        String maya = token("maya");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String weekAgo = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString();
        for (String type : new String[] {"FLEET_HEALTH", "MAINTENANCE", "DRIVER_BEHAVIOUR", "FUEL"}) {
            for (String format : new String[] {"PDF", "XLSX"}) {
                Reply created = call(maya, HttpMethod.POST, "/api/reports", Map.of("type", type, "format", format, "from", weekAgo, "to", today));
                assertThat(created.status()).as("%s %s", type, format).isEqualTo(201);
                Reply file = get(maya, "/api/reports/" + created.body().get("id").asLong() + "/download");
                byte[] bytes = file.raw().getContentAsByteArray();
                assertThat(bytes.length).isEqualTo(created.body().get("sizeBytes").asInt());
                assertThat(new String(bytes, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo(format.equals("PDF") ? "%PDF" : "PK\u0003\u0004");
                assertThat(file.raw().getHeader("Content-Disposition")).contains(type.toLowerCase()).contains(format.toLowerCase());
            }
        }
        assertThat(call(maya, HttpMethod.POST, "/api/reports", Map.of("type", "FUEL", "format", "PDF", "from", today, "to", weekAgo)).status())
                .as("from after to").isEqualTo(400);
        assertThat(call(token("vik"), HttpMethod.POST, "/api/reports", Map.of("type", "FUEL", "format", "PDF", "from", weekAgo, "to", today)).status()).isEqualTo(403);
        JsonNode list = get(token("vik"), "/api/reports").body();
        assertThat(list).hasSize(8);
        assertThat(list.get(0).get("createdBy").asText()).isEqualTo("maya");
        assertThat(get(maya, "/api/reports/9999/download").status()).isEqualTo(404);
    }

    @Test
    @Order(10)
    void theSameRulesHoldOverRealHttp() throws Exception {
        // Everything above went through MockMvc; this goes through the embedded server and its filter chain.
        HttpClient http = HttpClient.newHttpClient();
        URI vehicles = URI.create("http://localhost:" + port + "/api/vehicles");
        assertThat(http.send(HttpRequest.newBuilder(vehicles).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(vehicles).header("Authorization", "Bearer " + token("vik")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ok.body())).hasSize(5);
        HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users"))
                .header("Authorization", "Bearer " + token("vik")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
    }

    @Test
    @Order(11)
    void withTheMlServiceUpTwinsGetHealthAnomaliesAndRemainingLife() throws Exception {
        String tina = token("tina");
        JsonNode before = get(tina, "/api/vehicles/1/twin").body();
        assertThat(before.has("healthScore")).as("no ML verdict while the service was down").isFalse();

        // "down" must be survivable: these are what the scheduler calls
        mlService.scoreFleet();
        mlService.predictRul();

        mlUp = true;
        mlService.scoreFleet();
        mlService.predictRul();
        JsonNode twin = get(tina, "/api/vehicles/1/twin").body();
        assertThat(twin.get("healthScore").asDouble()).isEqualTo(46.5);
        assertThat(twin.get("anomaly").asBoolean()).isTrue();
        assertThat(twin.get("anomalyReasons").get(0).asText()).startsWith("engine_temp");
        assertThat(twin.get("rul").get("brakes").get("days").asDouble()).isEqualTo(2.0);
        assertThat(twin.get("rul").has("engine")).as("no model for the engine: no prediction, no error").isFalse();

        JsonNode mlAlerts = get(tina, "/api/alerts?vehicleId=1&source=ML").body();
        assertThat(mlAlerts).hasSize(1);
        assertThat(mlAlerts.get(0).get("component").asText()).isEqualTo("engine");
        assertThat(mlAlerts.get(0).get("severity").asText()).isEqualTo("CRITICAL");
        mlService.scoreFleet();
        assertThat(get(tina, "/api/alerts?vehicleId=1&source=ML").body()).as("still one open ML alert").hasSize(1);
    }

    @Test
    @Order(12)
    void wearPredictionRecommendsAReplacementAndCompletingItResetsThePart() throws Exception {
        String tina = token("tina");
        call(tina, HttpMethod.POST, "/api/recommendations/recompute", null);
        JsonNode brakes = null;
        for (JsonNode r : get(tina, "/api/recommendations?vehicleId=1&status=OPEN").body()) {
            if (r.get("component").asText().equals("brakes")) {
                brakes = r;
            }
        }
        assertThat(brakes).isNotNull();
        assertThat(brakes.get("action").asText()).isEqualTo("Replace brake pads");
        assertThat(brakes.get("priority").asText()).isEqualTo("URGENT");
        assertThat(brakes.get("reason").asText()).startsWith("Predicted to fail in about 2 days");

        Reply done = call(tina, HttpMethod.PATCH, "/api/recommendations/" + brakes.get("id").asLong(), Map.of("status", "DONE"));
        assertThat(done.status()).isEqualTo(200);
        JsonNode twin = get(tina, "/api/vehicles/1/twin").body();
        assertThat(twin.get("sensors").get("brakePadWear").asDouble()).as("new pads").isEqualTo(0.0);
        assertThat(twin.get("rul").has("brakes")).as("the old prediction is dropped").isFalse();
        JsonNode record = get(tina, "/api/maintenance-records/" + done.body().get("maintenanceRecordId").asLong()).body();
        assertThat(record.get("component").asText()).isEqualTo("brakes");
    }

    @Test
    @Order(13)
    void routesComeBackWithTimesAndFuelWhenTheSolverAnswers() throws Exception {
        Object[] stops = {Map.of("name", "First", "lat", 12.95, "lng", 77.60), Map.of("name", "Second", "lat", 12.99, "lng", 77.62)};
        Reply planned = call(token("maya"), HttpMethod.POST, "/api/routes/optimise",
                Map.of("stops", stops, "depot", Map.of("lat", 12.97, "lng", 77.59), "departAt", "2026-10-08T03:00:00Z"));
        assertThat(planned.status()).isEqualTo(200);
        JsonNode route = planned.body().get("routes").get(0);
        assertThat(route.get("registration").asText()).as("vehicle 1 is unfit, so the first one offered is vehicle 2").isEqualTo("KA02CD5678");
        assertThat(route.get("stops")).hasSize(2);
        assertThat(route.get("stops").get(1).get("name").asText()).isEqualTo("Second");
        assertThat(route.get("stops").get(0).get("arrival").asText()).isEqualTo("2026-10-08T03:15:00Z");
        assertThat(route.get("distanceKm").asDouble()).isEqualTo(12.4);
        assertThat(route.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(route.get("fuelLitres").asDouble()).as("12.4 km at vehicle 2's own 2 km per litre").isBetween(5.5, 7.0);
        assertThat(planned.body().get("excluded").get(0).get("registration").asText()).isEqualTo("KA01AB1234");
        assertThat(planned.body().get("distanceSource").asText()).isEqualTo("straight-line");
    }

    @Test
    @Order(14)
    void webSocketRefusesAStompConnectWithoutAValidToken() throws Exception {
        assertThat(stompConnect(null)).startsWith("ERROR").contains("bearer token is required");
        assertThat(stompConnect("not.a.token")).startsWith("ERROR").contains("Invalid or expired token");
        assertThat(stompConnect(login("vik", PASSWORD).get("refreshToken").asText())).as("a refresh token is not enough").startsWith("ERROR");
        assertThat(stompConnect(token("vik"))).startsWith("CONNECTED");
    }

    /** Opens /ws, sends one STOMP CONNECT frame and returns the server's first frame. */
    private String stompConnect(String token) throws Exception {
        CompletableFuture<String> firstFrame = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:4200")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        firstFrame.complete(data.toString());
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\n" + (token == null ? "" : "Authorization:Bearer " + token + "\n") + "\n\u0000", true);
        try {
            return firstFrame.get(5, TimeUnit.SECONDS);
        } finally {
            socket.abort();
        }
    }
}
