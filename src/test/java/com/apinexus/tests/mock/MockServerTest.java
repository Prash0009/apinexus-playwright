package com.apinexus.tests.mock;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * MockServerTest — Comprehensive tests for WireMock-based request/response mocking.
 *
 * WHY MOCK SERVERS IN API TESTING?
 * ─────────────────────────────────
 * 1. ISOLATION: Tests don't depend on live external services.
 *    A live API can be down, slow, or have rate limits.
 *
 * 2. CONTROL: We can simulate any scenario — including ones that are
 *    impossible or impractical to trigger on a live system:
 *      • 500 errors, network timeouts, connection resets
 *      • Rate limiting (429)
 *      • Slow responses to test client timeout handling
 *
 * 3. REPRODUCIBILITY: The same stub always returns the same response,
 *    so tests are deterministic (no "flaky" failures due to data changes).
 *
 * 4. STATEFUL FLOWS: Model multi-step workflows (pending → processing → done)
 *    using WireMock Scenarios.
 *
 * Scenarios covered:
 *   TC-MOCK-01  GET user list (file-backed response body)
 *   TC-MOCK-02  POST create user → 201 with Location header
 *   TC-MOCK-03  Stateful polling (PENDING → PROCESSING → COMPLETE)
 *   TC-MOCK-04  Slow response → client delay measurement
 *   TC-MOCK-05  Conditional response based on request body content
 *   TC-MOCK-06  Response with multiple custom headers
 *   TC-MOCK-07  WireMock request verification (assert it was called)
 *   TC-MOCK-08  CORS pre-flight OPTIONS request simulation
 */
@Test(groups = "mock")
public class MockServerTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Reset all WireMock stubs after each test method so stubs from one
    // test do not accidentally match requests from the next test.
    @AfterMethod(alwaysRun = true)
    public void resetStubs() {
        mockServer.resetAllStubs();
    }

    // ── TC-MOCK-01: GET user list ──────────────────────────────────────────

    /**
     * TC-MOCK-01: Stub GET /api/users → returns a pre-built JSON response
     * loaded from testdata/mock_responses/users_response.json.
     *
     * This demonstrates using file-based response bodies, which is the cleanest
     * approach when the response is large or complex.
     */
    @Test(description = "TC-MOCK-01: GET /api/users returns mock user list")
    public void testGetMockUsers() throws Exception {
        logTestStart("testGetMockUsers");

        // Register the stub — WireMock will serve the JSON file for GET /api/users
        mockServer.stubGetUsers();

        String url = mockBaseUrl + "/api/users";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertContentType(response, "application/json");

        // Parse and verify the structure of the mock response
        JsonNode body = mapper.readTree(response.text());
        Assert.assertEquals(body.get("page").asInt(), 1, "page should be 1");
        Assert.assertEquals(body.get("per_page").asInt(), 3, "per_page should be 3");
        Assert.assertEquals(body.get("total").asInt(), 3, "total should be 3");

        JsonNode data = body.get("data");
        Assert.assertTrue(data.isArray() && data.size() == 3, "data should have 3 users");

        // Verify the first user has the expected structure
        JsonNode firstUser = data.get(0);
        Assert.assertEquals(firstUser.get("id").asInt(), 101);
        Assert.assertEquals(firstUser.get("name").asText(), "Alice Nexus");

        logTestEnd("testGetMockUsers");
    }

    // ── TC-MOCK-02: POST create user ──────────────────────────────────────

    /**
     * TC-MOCK-02: Stub POST /api/users → 201 Created with a generated user ID
     * and a Location header pointing to the new resource.
     *
     * The Location header is an HTTP standard for indicating the URL of the
     * newly created resource. Testing for it verifies the API follows REST conventions.
     */
    @Test(description = "TC-MOCK-02: POST /api/users creates user and returns Location header")
    public void testCreateMockUser() throws Exception {
        logTestStart("testCreateMockUser");

        // Build the response body that the stub will return
        String createdUser = "{\"id\": 201, \"name\": \"New User\", "
                + "\"email\": \"newuser@apinexus.io\", \"role\": \"user\", "
                + "\"createdAt\": \"2024-01-15T10:30:00Z\"}";

        mockServer.stubCreateUser(createdUser);

        // Also add a Location header to the stub (not in stubCreateUser — we do it inline)
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/users/with-location"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withHeader("Location", mockBaseUrl + "/api/users/201")
                                        .withBody(createdUser)));

        Map<String, Object> newUser = new HashMap<>();
        newUser.put("name",  "New User");
        newUser.put("email", "newuser@apinexus.io");
        String requestBody = mapper.writeValueAsString(newUser);

        String url = mockBaseUrl + "/api/users/with-location";
        ApiLogger.logRequest("POST", url, Map.of("Content-Type", "application/json"), requestBody);

        long start = startTimer();
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(requestBody));
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 201);
        ResponseValidator.assertHeaderPresent(response, "location");
        ResponseValidator.assertJsonField(response, "id", "201");

        String location = response.headers().get("location");
        log.info("Location header: {}", location);
        Assert.assertTrue(location.contains("/api/users/201"),
                "Location should point to the new user's URL");

        logTestEnd("testCreateMockUser");
    }

    // ── TC-MOCK-03: Stateful polling flow ─────────────────────────────────

    /**
     * TC-MOCK-03: Simulate an order-processing flow where the order status
     * transitions from PENDING → PROCESSING → COMPLETE across three calls.
     *
     * This models async polling: the client polls until the resource reaches
     * a terminal state. WireMock Scenarios implement the state machine.
     */
    @Test(description = "TC-MOCK-03: Stateful stub transitions PENDING→PROCESSING→COMPLETE")
    public void testStatefulPollingFlow() throws Exception {
        logTestStart("testStatefulPollingFlow");

        String scenarioName = "order-processing-scenario";
        String path         = "/api/orders/42";

        // Register the three-state scenario stub
        mockServer.stubStatefulOrder(path, scenarioName);

        String url = mockBaseUrl + path;

        // ── Call 1: state = STARTED → returns PENDING ─────────────────────
        ApiLogger.logStep(1, "Poll 1 — expecting PENDING");
        APIResponse resp1 = request.get(url);
        Assert.assertEquals(resp1.status(), 200);
        JsonNode body1 = mapper.readTree(resp1.text());
        Assert.assertEquals(body1.get("status").asText(), "PENDING");
        log.info("Poll 1 result: {}", body1.get("status").asText());

        // ── Call 2: state = PROCESSING → returns PROCESSING ───────────────
        ApiLogger.logStep(2, "Poll 2 — expecting PROCESSING");
        APIResponse resp2 = request.get(url);
        Assert.assertEquals(resp2.status(), 200);
        JsonNode body2 = mapper.readTree(resp2.text());
        Assert.assertEquals(body2.get("status").asText(), "PROCESSING");
        log.info("Poll 2 result: {}", body2.get("status").asText());

        // ── Call 3: state = COMPLETE → terminal state ─────────────────────
        ApiLogger.logStep(3, "Poll 3 — expecting COMPLETE");
        APIResponse resp3 = request.get(url);
        Assert.assertEquals(resp3.status(), 200);
        JsonNode body3 = mapper.readTree(resp3.text());
        Assert.assertEquals(body3.get("status").asText(), "COMPLETE");
        log.info("Poll 3 result: {} — order complete!", body3.get("status").asText());

        logTestEnd("testStatefulPollingFlow");
    }

    // ── TC-MOCK-04: Slow response ──────────────────────────────────────────

    /**
     * TC-MOCK-04: WireMock introduces a 500ms delay.
     * We verify:
     *   1. The response is eventually received (not timed out by our config)
     *   2. The measured round-trip time is >= 500ms (the delay was applied)
     *
     * This validates that our timeout configuration is correct and that the
     * client does not bail out too early on legitimately slow endpoints.
     */
    @Test(description = "TC-MOCK-04: Slow endpoint (500ms delay) is received within SLA")
    public void testSlowEndpointResponse() {
        logTestStart("testSlowEndpointResponse");

        int delayMs = 500; // 500ms server-side delay
        mockServer.stubSlowResponse("/api/slow", delayMs);

        String url = mockBaseUrl + "/api/slow";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url); // will block for >= 500ms
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);

        // The measured time must be at least the injected delay.
        Assert.assertTrue(elapsed >= delayMs,
                "Elapsed time " + elapsed + "ms should be >= delay " + delayMs + "ms");

        // The response must still be within our overall SLA (read.timeout = 10000ms).
        ResponseValidator.assertResponseTime(elapsed, config.getMaxResponseTimeMs() + delayMs);

        log.info("Slow endpoint responded in {}ms (delay was {}ms)", elapsed, delayMs);
        logTestEnd("testSlowEndpointResponse");
    }

    // ── TC-MOCK-05: Conditional response on request body ──────────────────

    /**
     * TC-MOCK-05: Different request bodies return different responses.
     *
     * This models content-based routing: the server inspects the request body
     * to decide which response to send. Useful for testing dispatch logic.
     */
    @Test(description = "TC-MOCK-05: Different request bodies produce different responses")
    public void testConditionalResponseOnRequestBody() throws Exception {
        logTestStart("testConditionalResponseOnRequestBody");

        // Stub for "admin" role requests → 200 with extended data
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/query"))
                        .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                                .containing("\"role\":\"admin\""))
                        .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                                .aResponse()
                                .withStatus(200)
                                .withBody("{\"result\":\"admin_data\",\"records\":1000}")));

        // Stub for "user" role requests → 200 with limited data
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/query"))
                        .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                                .containing("\"role\":\"user\""))
                        .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                                .aResponse()
                                .withStatus(200)
                                .withBody("{\"result\":\"user_data\",\"records\":10}")));

        String url = mockBaseUrl + "/api/query";

        // Request 1: admin role → expects admin_data
        ApiLogger.logStep(1, "POST with role=admin");
        APIResponse adminResp = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData("{\"role\":\"admin\",\"filter\":\"all\"}"));
        ResponseValidator.assertStatusCode(adminResp, 200);
        ResponseValidator.assertBodyContains(adminResp, "admin_data");
        Assert.assertEquals(mapper.readTree(adminResp.text()).get("records").asInt(), 1000);

        // Request 2: user role → expects user_data
        ApiLogger.logStep(2, "POST with role=user");
        APIResponse userResp = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData("{\"role\":\"user\",\"filter\":\"own\"}"));
        ResponseValidator.assertStatusCode(userResp, 200);
        ResponseValidator.assertBodyContains(userResp, "user_data");
        Assert.assertEquals(mapper.readTree(userResp.text()).get("records").asInt(), 10);

        logTestEnd("testConditionalResponseOnRequestBody");
    }

    // ── TC-MOCK-06: Custom response headers ───────────────────────────────

    /**
     * TC-MOCK-06: Verify an endpoint returns multiple custom headers.
     *
     * Production APIs commonly return metadata headers:
     *   - X-Request-ID:    correlation ID for distributed tracing
     *   - X-RateLimit-*:   rate limit info
     *   - Cache-Control:   caching directives
     *   - ETag:            resource version for conditional requests
     */
    @Test(description = "TC-MOCK-06: Response includes all required custom headers")
    public void testResponseWithCustomHeaders() {
        logTestStart("testResponseWithCustomHeaders");

        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/resource"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type",      "application/json")
                                        .withHeader("X-Request-ID",      "req-uuid-9876")
                                        .withHeader("X-RateLimit-Limit", "1000")
                                        .withHeader("X-RateLimit-Remaining", "999")
                                        .withHeader("Cache-Control",     "no-cache, no-store")
                                        .withHeader("ETag",              "\"v1.0.0\"")
                                        .withBody("{\"data\":\"ok\"}")));

        String url = mockBaseUrl + "/api/resource";
        APIResponse response = request.get(url);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertHeaderPresent(response, "x-request-id");
        ResponseValidator.assertHeaderPresent(response, "x-ratelimit-limit");
        ResponseValidator.assertHeaderPresent(response, "cache-control");
        ResponseValidator.assertHeaderPresent(response, "etag");

        Assert.assertEquals(response.headers().get("x-request-id"), "req-uuid-9876");
        Assert.assertEquals(response.headers().get("x-ratelimit-limit"), "1000");

        logTestEnd("testResponseWithCustomHeaders");
    }

    // ── TC-MOCK-07: WireMock request verification ──────────────────────────

    /**
     * TC-MOCK-07: Use WireMock's verification API to assert how many times
     * a stub was called and with what parameters.
     *
     * This goes beyond checking the response: it asserts that the client
     * MADE the correct request in the first place.
     */
    @Test(description = "TC-MOCK-07: WireMock verifies exact number of calls made")
    public void testWireMockRequestVerification() {
        logTestStart("testWireMockRequestVerification");

        // Register a stub that we will call exactly twice
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/verify-me"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withBody("{\"ok\":true}")));

        String url = mockBaseUrl + "/api/verify-me";

        // Call the endpoint twice
        request.get(url);
        request.get(url);

        // WireMock's verify() checks that the stub was called the expected number of times.
        // WireMock.verify(count, requestPattern) throws VerificationException if the count
        // doesn't match — TestNG catches it as a test failure.
        com.github.tomakehurst.wiremock.client.WireMock.verify(
                2,   // expected call count
                com.github.tomakehurst.wiremock.client.WireMock
                        .getRequestedFor(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .urlEqualTo("/api/verify-me")));

        log.info("WireMock verified: /api/verify-me was called exactly 2 times");
        logTestEnd("testWireMockRequestVerification");
    }

    // ── TC-MOCK-08: CORS pre-flight OPTIONS ───────────────────────────────

    /**
     * TC-MOCK-08: Simulate a CORS pre-flight request (OPTIONS method).
     *
     * Browsers send an OPTIONS request before a cross-origin POST/PUT to check
     * whether the server allows the real request (CORS pre-flight handshake).
     * The server must respond with the appropriate Access-Control-* headers.
     *
     * Testing this ensures the API will work when called from a browser app
     * running on a different domain.
     */
    @Test(description = "TC-MOCK-08: CORS pre-flight OPTIONS returns correct CORS headers")
    public void testCorsPreFlightRequest() {
        logTestStart("testCorsPreFlightRequest");

        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .options(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/cors-endpoint"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(204) // 204 No Content is standard for pre-flight
                                        .withHeader("Access-Control-Allow-Origin",  "*")
                                        .withHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                                        .withHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                                        .withHeader("Access-Control-Max-Age",       "3600")));

        String url = mockBaseUrl + "/api/cors-endpoint";
        ApiLogger.logRequest("OPTIONS", url);

        // Playwright does not have a named options() method, so we use method()
        APIResponse response = request.fetch(url,
                RequestOptions.create().setMethod("OPTIONS"));
        ApiLogger.logResponse(response, 0);

        ResponseValidator.assertStatusCode(response, 204);
        ResponseValidator.assertHeaderPresent(response, "access-control-allow-origin");
        ResponseValidator.assertHeaderPresent(response, "access-control-allow-methods");

        String allowMethods = response.headers().get("access-control-allow-methods");
        Assert.assertTrue(allowMethods.contains("POST"),
                "CORS should allow POST, got: " + allowMethods);

        log.info("CORS pre-flight verified. Allow-Methods: {}", allowMethods);
        logTestEnd("testCorsPreFlightRequest");
    }
}
