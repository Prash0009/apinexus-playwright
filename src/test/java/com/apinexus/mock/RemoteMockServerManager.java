package com.apinexus.mock;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * RemoteMockServerManager — Registers WireMock stubs on a REMOTE WireMock
 * server via its Admin REST API instead of the embedded Java server.
 *
 * HOW IT WORKS
 * ────────────
 * WireMock exposes a built-in HTTP Admin API at /__admin/.
 * Every stub that MockServerManager registers via the Java DSL can be
 * registered remotely by POSTing an equivalent JSON document to:
 *
 *   POST  {baseUrl}/__admin/mappings          → create a stub
 *   POST  {baseUrl}/__admin/reset             → reset stubs + scenario states
 *   GET   {baseUrl}/__admin/mappings          → list all stubs (debug)
 *
 * Stub JSON format (same structure WireMock uses internally):
 *
 *   {
 *     "name": "human-readable label",
 *     "request": {
 *       "method": "GET",
 *       "url": "/api/users",
 *       "headers": { "X-API-Key": { "equalTo": "secret" } }   ← optional
 *     },
 *     "response": {
 *       "status": 200,
 *       "headers": { "Content-Type": "application/json" },
 *       "body": "{...}"                    ← or "bodyFileName" for file-backed
 *     }
 *   }
 *
 * COMPATIBLE REMOTE HOSTS
 * ───────────────────────
 *   1. WireMock Cloud      — https://app.wiremock.cloud  (free tier available)
 *   2. Self-hosted Docker  — docker run wiremock/wiremock
 *   3. Fly.io              — fly deploy (see fly.toml in project root)
 *   4. Railway / Render    — any Docker-based cloud runner
 *
 * AUTHENTICATION
 * ──────────────
 * WireMock Cloud requires an API key in the Authorization header.
 * Self-hosted WireMock has no auth by default (add --api-key flag to enable).
 * Set mock.remote.api.key in config.properties; leave blank for open servers.
 *
 * LIFECYCLE DIFFERENCE vs MockServerManager
 * ─────────────────────────────────────────
 *   start()  → no-op (the remote server is already running)
 *   stop()   → no-op (we never stop someone else's server)
 *   reset()  → POST /__admin/reset  (clears stubs between tests)
 */
public class RemoteMockServerManager implements MockServerStrategy {

    private static final Logger log = LoggerFactory.getLogger(RemoteMockServerManager.class);

    // Full base URL of the remote WireMock server, e.g.:
    //   https://abc123.wiremockapi.cloud
    //   http://my-wiremock.fly.dev
    private final String baseUrl;

    // Optional API key — sent as "Authorization: Token {apiKey}" for WireMock Cloud.
    // If null or blank, the Authorization header is omitted (for open servers).
    private final String apiKey;

    // Java 11 built-in HTTP client — used exclusively for Admin API calls.
    // We do NOT use Playwright's APIRequestContext here because that object is
    // created later in BaseApiTest.suiteSetUp(), after the mock manager is built.
    private final HttpClient httpClient;

    // Admin API base path — every WireMock server exposes this at /__admin
    private static final String ADMIN_PATH = "/__admin";

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param baseUrl The remote WireMock server URL (no trailing slash).
     *                Example: "https://abc123.wiremockapi.cloud"
     * @param apiKey  API key for WireMock Cloud, or null/empty for open servers.
     */
    public RemoteMockServerManager(String baseUrl, String apiKey) {
        // Remove trailing slash so we never get double-slashes in URLs
        this.baseUrl  = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey   = apiKey;

        // Build a shared HTTP client with a 10-second connect timeout.
        // HttpClient is thread-safe and reusable — one instance is sufficient.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("RemoteMockServerManager created for {}", this.baseUrl);
    }

    /** Convenience constructor for servers without an API key. */
    public RemoteMockServerManager(String baseUrl) {
        this(baseUrl, null);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * No-op for remote servers — the server is already running in the cloud.
     * We just log a confirmation ping to verify the Admin API is reachable.
     */
    @Override
    public void start() {
        log.info("Remote WireMock server at {} — no local start needed.", baseUrl);
        // Verify connectivity by hitting the Admin API health endpoint.
        // If this fails, every subsequent stub registration will also fail,
        // and the failure message here is much clearer than a later NPE.
        try {
            HttpResponse<String> ping = httpGet(ADMIN_PATH + "/health");
            log.info("Remote WireMock health check: HTTP {}", ping.statusCode());
        } catch (Exception e) {
            // Don't fail here — the server might not expose /health.
            // Stub registration will surface real connectivity problems.
            log.warn("Could not reach Admin health endpoint: {}", e.getMessage());
        }
    }

    /**
     * No-op — we never stop a remote server that belongs to a cloud provider.
     */
    @Override
    public void stop() {
        log.info("Remote WireMock server at {} — no stop action taken.", baseUrl);
    }

    /**
     * Resets ALL stubs and scenario states on the remote server.
     * This calls POST /__admin/reset which is equivalent to WireMockServer.resetAll().
     *
     * Call this in @AfterMethod so each test starts with a clean stub registry.
     */
    @Override
    public void resetAllStubs() {
        log.debug("Resetting all remote stubs via POST /__admin/reset");
        try {
            HttpResponse<String> response = httpPost(ADMIN_PATH + "/reset", "{}");
            if (response.statusCode() == 200) {
                log.debug("Remote stubs reset successfully");
            } else {
                log.warn("Unexpected status from /__admin/reset: {}", response.statusCode());
            }
        } catch (Exception e) {
            log.error("Failed to reset remote stubs: {}", e.getMessage());
        }
    }

    /** Returns the full base URL of the remote WireMock server. */
    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    // ── Stub registration ─────────────────────────────────────────────────
    //
    // Each method below builds a WireMock stub JSON document and POSTs it to
    // POST /__admin/mappings.  The JSON format is WireMock's public API format —
    // identical to what you would write in a mappings/ JSON file for the Docker
    // container's file-based loading.
    //

    @Override
    public void stubGetUsers() {
        // Load the response body from the test classpath (same file used locally)
        String body = loadFile("testdata/mock_responses/users_response.json");

        // Escape the body for embedding inside a JSON string value.
        // jsonEscape() handles quotes, backslashes, and control characters.
        String stub = "{"
                + "\"name\": \"GET /api/users — mock user list\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"/api/users\"},"
                + "\"response\": {"
                + "  \"status\": 200,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": " + toJsonString(body)
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: GET /api/users → 200");
    }

    @Override
    public void stubCreateUser(String responseBody) {
        String stub = "{"
                + "\"name\": \"POST /api/users — create user\","
                + "\"request\": {"
                + "  \"method\": \"POST\","
                + "  \"url\": \"/api/users\","
                + "  \"headers\": {\"Content-Type\": {\"contains\": \"application/json\"}}"
                + "},"
                + "\"response\": {"
                + "  \"status\": 201,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": " + toJsonString(responseBody)
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: POST /api/users → 201");
    }

    @Override
    public void stubNotFound(String path) {
        String body = loadFile("testdata/mock_responses/error_response.json");

        String stub = "{"
                + "\"name\": \"GET " + path + " — 404 Not Found\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {"
                + "  \"status\": 404,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": " + toJsonString(body)
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: GET {} → 404", path);
    }

    @Override
    public void stubInternalServerError(String path) {
        String stub = "{"
                + "\"name\": \"GET " + path + " — 500 Internal Server Error\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {"
                + "  \"status\": 500,"
                + "  \"headers\": {\"Content-Type\": \"text/plain\"},"
                + "  \"body\": \"Internal Server Error: Something went very wrong.\""
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: GET {} → 500", path);
    }

    @Override
    public void stubSlowResponse(String path, int delayMs) {
        // fixedDelayMilliseconds tells the remote WireMock to pause before responding.
        // This is the same delay WireMock injects locally via .withFixedDelay(N).
        String stub = "{"
                + "\"name\": \"GET " + path + " — slow response (" + delayMs + "ms)\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {"
                + "  \"status\": 200,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": \"{\\\"message\\\": \\\"I was slow\\\"}\","
                + "  \"fixedDelayMilliseconds\": " + delayMs
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: GET {} → 200 after {}ms", path, delayMs);
    }

    @Override
    public void stubNetworkFault(String path) {
        // WireMock's fault types work on remote servers too — the server closes
        // the connection abnormally instead of sending an HTTP response.
        // CONNECTION_RESET_BY_PEER is the most realistic network failure.
        String stub = "{"
                + "\"name\": \"GET " + path + " — CONNECTION_RESET_BY_PEER fault\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {"
                + "  \"fault\": \"CONNECTION_RESET_BY_PEER\""
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: GET {} → CONNECTION_RESET_BY_PEER", path);
    }

    @Override
    public void stubBearerAuthProtected(String path, String validToken, String successBody) {
        // Stub 1: Correct token → 200
        // WireMock's header matcher: { "equalTo": "Bearer <token>" }
        String stub200 = "{"
                + "\"name\": \"GET " + path + " — valid Bearer → 200\","
                + "\"priority\": 1,"          // priority 1 wins over the catch-all stub below
                + "\"request\": {"
                + "  \"method\": \"GET\","
                + "  \"url\": \"" + path + "\","
                + "  \"headers\": {\"Authorization\": {\"equalTo\": \"Bearer " + validToken + "\"}}"
                + "},"
                + "\"response\": {"
                + "  \"status\": 200,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": " + toJsonString(successBody)
                + "}"
                + "}";

        // Stub 2: No / wrong token → 401 (lower priority, catches everything else)
        String stub401 = "{"
                + "\"name\": \"GET " + path + " — missing Bearer → 401\","
                + "\"priority\": 5,"          // priority 5 fires only when stub 1 does not match
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {"
                + "  \"status\": 401,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": \"{\\\"error\\\":\\\"Unauthorized\\\",\\\"message\\\":\\\"Bearer token required\\\"}\""
                + "}"
                + "}";

        registerStub(stub200);
        registerStub(stub401);
        log.debug("Remote stubs registered: GET {} → 200 (with auth) / 401 (without)", path);
    }

    @Override
    public void stubStatefulOrder(String path, String scenarioName) {
        // WireMock's scenario API is fully supported via the Admin REST endpoint.
        // "Started" is the reserved initial state name in WireMock.

        // State 1: Started → return PENDING, transition to PROCESSING
        String stub1 = "{"
                + "\"name\": \"Order PENDING\","
                + "\"scenarioName\": \"" + scenarioName + "\","
                + "\"requiredScenarioState\": \"Started\","
                + "\"newScenarioState\": \"PROCESSING\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {\"status\": 200, \"body\": \"{\\\"status\\\":\\\"PENDING\\\"}\"}"
                + "}";

        // State 2: PROCESSING → return PROCESSING, transition to COMPLETE
        String stub2 = "{"
                + "\"name\": \"Order PROCESSING\","
                + "\"scenarioName\": \"" + scenarioName + "\","
                + "\"requiredScenarioState\": \"PROCESSING\","
                + "\"newScenarioState\": \"COMPLETE\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {\"status\": 200, \"body\": \"{\\\"status\\\":\\\"PROCESSING\\\"}\"}"
                + "}";

        // State 3: COMPLETE → return COMPLETE (terminal)
        String stub3 = "{"
                + "\"name\": \"Order COMPLETE\","
                + "\"scenarioName\": \"" + scenarioName + "\","
                + "\"requiredScenarioState\": \"COMPLETE\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {\"status\": 200, \"body\": \"{\\\"status\\\":\\\"COMPLETE\\\"}\"}"
                + "}";

        registerStub(stub1);
        registerStub(stub2);
        registerStub(stub3);
        log.debug("Remote stateful stubs registered: GET {} scenario='{}'", path, scenarioName);
    }

    @Override
    public void stubRateLimited(String path) {
        String stub = "{"
                + "\"name\": \"GET " + path + " — 429 Rate Limited\","
                + "\"request\": {\"method\": \"GET\", \"url\": \"" + path + "\"},"
                + "\"response\": {"
                + "  \"status\": 429,"
                + "  \"headers\": {"
                + "    \"Content-Type\": \"application/json\","
                + "    \"Retry-After\": \"60\""
                + "  },"
                + "  \"body\": \"{\\\"error\\\":\\\"Too Many Requests\\\",\\\"retryAfter\\\":60}\""
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: GET {} → 429", path);
    }

    @Override
    public void stubFileUpload(String path) {
        String stub = "{"
                + "\"name\": \"POST " + path + " — multipart upload\","
                + "\"request\": {"
                + "  \"method\": \"POST\","
                + "  \"url\": \"" + path + "\","
                + "  \"headers\": {\"Content-Type\": {\"contains\": \"multipart/form-data\"}}"
                + "},"
                + "\"response\": {"
                + "  \"status\": 201,"
                + "  \"headers\": {\"Content-Type\": \"application/json\"},"
                + "  \"body\": \"{\\\"message\\\":\\\"File uploaded successfully\\\","
                + "\\\"fileId\\\":\\\"abc-123\\\",\\\"size\\\":1024}\""
                + "}"
                + "}";

        registerStub(stub);
        log.debug("Remote stub registered: POST {} (multipart) → 201", path);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * POSTs a stub JSON document to POST /__admin/mappings on the remote server.
     *
     * @param stubJson  A complete WireMock mapping JSON string
     */
    private void registerStub(String stubJson) {
        try {
            HttpResponse<String> response = httpPost(ADMIN_PATH + "/mappings", stubJson);

            if (response.statusCode() == 201) {
                log.debug("Stub registered (HTTP 201)");
            } else {
                // 422 usually means malformed stub JSON — log the body to help debug
                log.error("Failed to register stub (HTTP {}): {}", response.statusCode(), response.body());
                throw new RuntimeException(
                        "WireMock Admin API returned " + response.statusCode()
                        + " registering stub. Body: " + response.body());
            }
        } catch (RuntimeException re) {
            throw re;   // re-throw runtime exceptions without wrapping
        } catch (Exception e) {
            throw new RuntimeException("Network error posting stub to " + baseUrl, e);
        }
    }

    /**
     * Sends an HTTP POST with a JSON body to {baseUrl}{path}.
     * Uses Java 11's built-in HttpClient — no extra dependency needed.
     */
    private HttpResponse<String> httpPost(String path, String jsonBody)
            throws IOException, InterruptedException {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        addApiKeyIfPresent(builder);

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends an HTTP GET to {baseUrl}{path}.
     */
    private HttpResponse<String> httpGet(String path)
            throws IOException, InterruptedException {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();

        addApiKeyIfPresent(builder);

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Adds the Authorization header when an API key is configured.
     * WireMock Cloud expects: Authorization: Token {apiKey}
     * Self-hosted WireMock with --api-key expects: Authorization: Token {apiKey}
     * (same format for both)
     */
    private void addApiKeyIfPresent(HttpRequest.Builder builder) {
        if (apiKey != null && !apiKey.isBlank()) {
            // WireMock Cloud uses "Token" (not "Bearer") as the auth scheme
            builder.header("Authorization", "Token " + apiKey);
        }
    }

    /**
     * Converts a JSON string into a JSON string literal (double-quoted, escaped).
     * Used when embedding a JSON document as the value of a "body" field inside
     * another JSON document.
     *
     * Example:  {"a":1}  →  "{\"a\":1}"
     */
    private String toJsonString(String json) {
        if (json == null) return "\"\"";
        // Replace backslashes first (before quote-escaping to avoid double-escaping)
        String escaped = json
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    /**
     * Reads a classpath resource file into a String.
     * Identical to the helper in MockServerManager.
     */
    private String loadFile(String classpathPath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
            if (is == null) {
                throw new RuntimeException("File not found on classpath: " + classpathPath);
            }
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load classpath file: " + classpathPath, e);
        }
    }
}
