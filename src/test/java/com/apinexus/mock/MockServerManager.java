package com.apinexus.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * MockServerManager — Starts, configures, and stops an embedded WireMock server.
 *
 * WireMock intercepts HTTP calls to a local port and returns pre-configured
 * stub responses. This lets us test:
 *   - Happy-path responses without hitting a live external API
 *   - Error conditions (4xx, 5xx) that are hard to trigger on live APIs
 *   - Network faults (connection reset, empty response)
 *   - Latency / slow responses
 *   - Stateful multi-step flows (first call returns A, second call returns B)
 *
 * LIFECYCLE (called from BaseApiTest):
 *   start()  →  configure stubs  →  run tests  →  stop()
 *
 * The WireMock base URL is: http://localhost:{port}
 * where {port} is read from config.properties (mock.server.port).
 */
public class MockServerManager implements MockServerStrategy {

    private static final Logger log = LoggerFactory.getLogger(MockServerManager.class);

    // The embedded WireMock HTTP server instance.
    private final WireMockServer wireMockServer;

    // The port on which WireMock listens.
    private final int port;

    /**
     * Constructor — creates (but does NOT yet start) the WireMock server.
     *
     * @param port The port WireMock will bind to when start() is called.
     *             Must be a free port on the test machine.
     */
    public MockServerManager(int port) {
        this.port = port;

        // WireMockConfiguration holds all server settings.
        // .port(port)               — bind to the given port
        // .usingFilesUnderClasspath — tell WireMock where to find static stub files
        //   (we don't use file-based stubs; we register stubs programmatically).
        WireMockConfiguration config = WireMockConfiguration
                .wireMockConfig()
                .port(port);

        // WireMockServer is the embeddable server — no separate process needed.
        this.wireMockServer = new WireMockServer(config);
        log.info("MockServerManager created. Will listen on port {}", port);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Starts the WireMock HTTP server and registers it as the default static
     * WireMock client so that WireMock.stubFor() / verify() calls work.
     */
    public void start() {
        wireMockServer.start();
        // configureFor() makes all static WireMock.* calls target THIS server,
        // which is necessary when multiple WireMock instances run simultaneously.
        WireMock.configureFor("localhost", port);
        log.info("WireMock server STARTED on http://localhost:{}", port);
    }

    /**
     * Stops the WireMock server and releases the TCP port.
     * Always call this in an @AfterClass or @AfterSuite method.
     */
    public void stop() {
        if (wireMockServer.isRunning()) {
            wireMockServer.stop();
            log.info("WireMock server STOPPED");
        }
    }

    /**
     * Removes ALL registered stubs so that each test starts clean.
     * Call this in @BeforeMethod to prevent stub pollution across tests.
     */
    public void resetAllStubs() {
        wireMockServer.resetAll();
        log.debug("All WireMock stubs reset");
    }

    /** Returns the full base URL of the mock server. */
    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    // ── Stub registration helpers ─────────────────────────────────────────

    /**
     * Registers a stub that returns a list of users when GET /api/users is called.
     * The response body is loaded from the test classpath.
     */
    public void stubGetUsers() {
        String body = loadFile("testdata/mock_responses/users_response.json");

        // stubFor() registers the URL → response mapping.
        // get(urlEqualTo(...)) matches GET requests with an exact URL.
        // willReturn(aResponse()...) defines the response.
        stubFor(get(urlEqualTo("/api/users"))
                .willReturn(aResponse()
                        .withStatus(200)                              // HTTP 200 OK
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));                            // JSON from file

        log.debug("Stub registered: GET /api/users → 200");
    }

    /**
     * Registers a stub for creating a user via POST /api/users.
     * The stub returns 201 Created with a generated ID.
     *
     * @param responseBody JSON string to return as the created-user response
     */
    public void stubCreateUser(String responseBody) {
        // post(urlEqualTo(...)) matches POST requests.
        // withRequestBody(containing(...)) narrows the match to requests whose
        // body contains the given substring — so we can have different stubs
        // for different request bodies.
        stubFor(post(urlEqualTo("/api/users"))
                .withHeader("Content-Type", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        log.debug("Stub registered: POST /api/users → 201");
    }

    /**
     * Registers a stub that simulates a 404 Not Found error.
     * Used in ErrorHandlingTest to verify the client handles missing resources.
     */
    public void stubNotFound(String path) {
        String body = loadFile("testdata/mock_responses/error_response.json");

        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        log.debug("Stub registered: GET {} → 404", path);
    }

    /**
     * Registers a stub that simulates a 500 Internal Server Error.
     * The body is a plain text error message (not JSON), which tests how the
     * client handles non-JSON error responses.
     */
    public void stubInternalServerError(String path) {
        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Internal Server Error: Something went very wrong.")));

        log.debug("Stub registered: GET {} → 500", path);
    }

    /**
     * Registers a stub that simulates a slow response.
     * The WireMock server will wait {@code delayMs} milliseconds before
     * sending the response — useful for testing timeout handling.
     *
     * @param path    URL path to match
     * @param delayMs Number of milliseconds to delay before responding
     */
    public void stubSlowResponse(String path, int delayMs) {
        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"I was slow\"}")
                        // withFixedDelay() pauses the WireMock thread for this many ms
                        // before sending the response — simulates a slow backend.
                        .withFixedDelay(delayMs)));

        log.debug("Stub registered: GET {} → 200 after {}ms delay", path, delayMs);
    }

    /**
     * Registers a stub that simulates a network fault (connection reset).
     * Fault.CONNECTION_RESET_BY_PEER tells WireMock to abruptly close the
     * TCP connection without sending any HTTP response — the client will
     * receive an IOException or SocketException.
     */
    public void stubNetworkFault(String path) {
        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        // CONNECTION_RESET_BY_PEER is the most realistic network fault:
                        // it mimics what happens when the server crashes mid-request.
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        log.debug("Stub registered: GET {} → CONNECTION_RESET_BY_PEER", path);
    }

    /**
     * Registers a stub that simulates an API requiring Bearer token authentication.
     *
     * Two stubs are registered:
     *   1. Requests WITH the correct token → 200 OK
     *   2. Requests WITHOUT the token → 401 Unauthorized
     *
     * @param path        URL path to protect
     * @param validToken  The expected Bearer token value
     * @param successBody Response body for authenticated requests
     */
    public void stubBearerAuthProtected(String path,
                                        String validToken,
                                        String successBody) {
        // Stub 1: Authenticated request → 200
        // matchingJsonPath / containing are request matchers; here we use
        // withHeader to match the Authorization header exactly.
        //
        // atPriority(1) is required here: WireMock does NOT automatically
        // prefer a more specific stub over a more general one. Without an
        // explicit priority, both stubs default to the same priority level
        // and WireMock's tie-break is "most recently registered stub wins" —
        // which would make stub 2 (the catch-all below) win even for
        // requests that correctly include the Bearer token, since it is
        // registered after stub 1. Explicit priorities make the match
        // deterministic regardless of registration order.
        stubFor(get(urlEqualTo(path))
                .atPriority(1)
                .withHeader("Authorization", equalTo("Bearer " + validToken))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successBody)));

        // Stub 2: No / wrong auth token → 401 (catch-all, lower priority
        // number = higher precedence in WireMock, so 5 here means "fires
        // only when the priority-1 stub above does not match").
        stubFor(get(urlEqualTo(path))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Unauthorized\",\"message\":\"Bearer token required\"}")));

        log.debug("Stub registered: GET {} → 200 (with auth) / 401 (without auth)", path);
    }

    /**
     * Registers a STATEFUL stub using WireMock Scenarios.
     *
     * Scenarios model a resource that changes state between requests:
     *   - GET /api/order  → "PENDING"   (first call, scenario state = "Started")
     *   - GET /api/order  → "PROCESSING" (second call, state transitions)
     *   - GET /api/order  → "COMPLETE"  (third call)
     *
     * This is useful for testing polling flows where the client repeatedly
     * calls the same endpoint until it reaches a terminal state.
     *
     * @param path         URL path for the stateful resource
     * @param scenarioName Unique name for this scenario (for WireMock's state machine)
     */
    public void stubStatefulOrder(String path, String scenarioName) {
        // State 1: Initial state (Scenario.STARTED is WireMock's built-in name
        // for the first state every scenario begins in).
        stubFor(get(urlEqualTo(path))
                .inScenario(scenarioName)
                .whenScenarioStateIs(Scenario.STARTED)            // matches first call
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"PENDING\"}"))
                .willSetStateTo("PROCESSING"));                    // transition after response

        // State 2: After state "PROCESSING"
        stubFor(get(urlEqualTo(path))
                .inScenario(scenarioName)
                .whenScenarioStateIs("PROCESSING")                // matches second call
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"PROCESSING\"}"))
                .willSetStateTo("COMPLETE"));                      // transition to final state

        // State 3: Terminal state
        stubFor(get(urlEqualTo(path))
                .inScenario(scenarioName)
                .whenScenarioStateIs("COMPLETE")                  // matches third call
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"COMPLETE\"}")));

        log.debug("Stateful stub registered: GET {} with scenario '{}'", path, scenarioName);
    }

    /**
     * Registers a stub that simulates HTTP 429 Too Many Requests (rate limiting).
     * Returns the Retry-After header so clients know when to retry.
     */
    public void stubRateLimited(String path) {
        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        // Retry-After: 60 means "try again after 60 seconds"
                        .withHeader("Retry-After", "60")
                        .withBody("{\"error\":\"Too Many Requests\",\"retryAfter\":60}")));

        log.debug("Stub registered: GET {} → 429 Too Many Requests", path);
    }

    /**
     * Registers a stub that simulates a multipart file upload endpoint.
     * Returns 201 Created with details about the uploaded file.
     *
     * @param path URL path for the upload endpoint
     */
    public void stubFileUpload(String path) {
        // multipartRequestBody() matches any multipart/form-data request.
        // We accept any file so the test can vary the payload.
        stubFor(post(urlEqualTo(path))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"File uploaded successfully\"," +
                                  "\"fileId\":\"abc-123\",\"size\":1024}")));

        log.debug("Stub registered: POST {} (multipart) → 201", path);
    }

    /**
     * Registers a ReqRes-style login endpoint (see MockServerStrategy for the contract).
     * Two stubs are registered:
     *   1. A high-priority stub matching a body that contains BOTH the valid
     *      email and the valid password → 200 with a token.
     *   2. A low-priority catch-all matching any other POST to the same path
     *      (wrong credentials, or a missing field entirely) → 400.
     * WireMock evaluates higher-priority (lower number) stubs first, so stub 1
     * "wins" only when both conditions are met; everything else falls through
     * to stub 2 — exactly mirroring reqres.in's real login behaviour.
     */
    @Override
    public void stubReqResLogin(String path, String validEmail, String validPassword, String token) {
        stubFor(post(urlEqualTo(path))
                .atPriority(1)
                .withRequestBody(containing("\"email\":\"" + validEmail + "\""))
                .withRequestBody(containing("\"password\":\"" + validPassword + "\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"" + token + "\"}")));

        stubFor(post(urlEqualTo(path))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"user not found or missing required field\"}")));

        log.debug("Stub registered: POST {} → 200 (valid creds) / 400 (anything else)", path);
    }

    /**
     * Registers a ReqRes-style single-user endpoint matching schemas/user_schema.json:
     *   { "data": {"id":..,"email":..,"first_name":..,"last_name":..,"avatar":..},
     *     "support": {"url":..,"text":..} }
     */
    @Override
    public void stubReqResSingleUser(String path, int userId, String email,
                                     String firstName, String lastName, String avatarUrl) {
        String body = "{"
                + "\"data\":{"
                + "\"id\":" + userId + ","
                + "\"email\":\"" + email + "\","
                + "\"first_name\":\"" + firstName + "\","
                + "\"last_name\":\"" + lastName + "\","
                + "\"avatar\":\"" + avatarUrl + "\""
                + "},"
                + "\"support\":{"
                + "\"url\":\"https://reqres.in/#support-heading\","
                + "\"text\":\"To keep ReqRes free, contributions towards server costs are appreciated!\""
                + "}"
                + "}";

        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        log.debug("Stub registered: GET {} → 200 (single user envelope)", path);
    }

    /**
     * Registers a ReqRes-style paginated users list (see MockServerStrategy
     * for the full contract). The "data" array is generated programmatically
     * so callers don't need to hand-write JSON for up to 12 fake users.
     */
    @Override
    public void stubReqResUsersPage(String path, int page, Integer perPage,
                                    int total, int totalPages, int itemCount) {
        StringBuilder dataArray = new StringBuilder("[");
        for (int i = 0; i < itemCount; i++) {
            int id = (page - 1) * (perPage != null ? perPage : itemCount) + i + 1;
            if (i > 0) dataArray.append(",");
            dataArray.append("{")
                     .append("\"id\":").append(id).append(",")
                     .append("\"email\":\"user").append(id).append("@apinexus.io\",")
                     .append("\"first_name\":\"User\",")
                     .append("\"last_name\":\"Number").append(id).append("\",")
                     .append("\"avatar\":\"https://reqres.in/img/faces/").append(id).append("-image.jpg\"")
                     .append("}");
        }
        dataArray.append("]");

        String body = "{"
                + "\"page\":" + page + ","
                + "\"per_page\":" + (perPage != null ? perPage : 6) + ","
                + "\"total\":" + total + ","
                + "\"total_pages\":" + totalPages + ","
                + "\"data\":" + dataArray
                + "}";

        // urlPathEqualTo() matches only the path; query params are matched
        // separately so we can optionally omit the per_page constraint.
        com.github.tomakehurst.wiremock.client.MappingBuilder mapping =
                get(urlPathEqualTo(path)).withQueryParam("page", equalTo(String.valueOf(page)));

        if (perPage != null) {
            mapping = mapping.withQueryParam("per_page", equalTo(String.valueOf(perPage)));
        }

        stubFor(mapping.willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));

        log.debug("Stub registered: GET {} page={} per_page={} → 200 ({} items)",
                path, page, perPage, itemCount);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Reads a classpath resource into a String.
     * Used to load JSON response body files from src/test/resources/.
     */
    private String loadFile(String classpathPath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
            if (is == null) {
                throw new RuntimeException("File not found on classpath: " + classpathPath);
            }
            // IOUtils.toString() from Apache Commons IO reads the stream into a String.
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load classpath file: " + classpathPath, e);
        }
    }
}
