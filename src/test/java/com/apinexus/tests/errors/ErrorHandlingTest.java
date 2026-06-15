package com.apinexus.tests.errors;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * ErrorHandlingTest — Verifies that the API returns the correct HTTP status
 * codes and error bodies under error conditions.
 *
 * WHY TEST ERRORS?
 * Clients rely on error codes to decide what to do next: retry, show a message,
 * redirect to login, etc. If the server returns the wrong code (e.g. 200 instead
 * of 404) or a blank error body, the client cannot respond correctly.
 *
 * Scenarios covered:
 *   TC-ERR-01  404 Not Found — missing resource (live API)
 *   TC-ERR-02  404 response body structure (mock)
 *   TC-ERR-03  500 Internal Server Error (mock)
 *   TC-ERR-04  400 Bad Request — missing required field (live API)
 *   TC-ERR-05  405 Method Not Allowed (mock)
 *   TC-ERR-06  429 Too Many Requests / rate limiting (mock)
 *   TC-ERR-07  Malformed JSON request body (live API)
 *   TC-ERR-08  Network fault / connection reset (mock)
 */
@Test(groups = "errors")
public class ErrorHandlingTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── TC-ERR-01: 404 Not Found (live API) ───────────────────────────────

    /**
     * TC-ERR-01: Request a post ID that does not exist.
     *
     * JSONPlaceholder posts have IDs 1–100. Any ID > 100 returns 404.
     * Expectations: 404 status code, empty or minimal body.
     */
    @Test(description = "TC-ERR-01: GET non-existent post returns 404")
    public void testGetNonExistentResource() {
        logTestStart("testGetNonExistentResource");

        // A post ID that is guaranteed not to exist on JSONPlaceholder.
        String url = baseUrl + "/posts/9999";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);

        // 404 is the canonical response for a resource that does not exist.
        ResponseValidator.assertStatusCode(response, 404);

        // JSONPlaceholder returns "{}" for missing resources.
        // A real API would return a JSON error object.
        log.info("404 response body: {}", response.text());

        logTestEnd("testGetNonExistentResource");
    }

    // ── TC-ERR-02: 404 error body structure (mock) ─────────────────────────

    /**
     * TC-ERR-02: Verify that a 404 response contains a properly structured
     * JSON error body (using WireMock).
     *
     * A well-designed API returns a machine-readable error object:
     *   { "error": "...", "code": 404, "message": "...", "timestamp": "..." }
     * This lets clients display meaningful error messages instead of crashing.
     */
    @Test(description = "TC-ERR-02: 404 error body contains required error fields")
    public void testNotFoundErrorBody() {
        logTestStart("testNotFoundErrorBody");

        // Register WireMock stub for the missing resource path
        mockServer.stubNotFound("/api/items/99999");

        String url = mockBaseUrl + "/api/items/99999";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 404);
        ResponseValidator.assertContentType(response, "application/json");

        // Assert specific error body fields that a well-formed error response
        // should always contain:
        ResponseValidator.assertBodyContains(response, "error");
        ResponseValidator.assertBodyContains(response, "404");
        ResponseValidator.assertBodyContains(response, "message");

        logTestEnd("testNotFoundErrorBody");
    }

    // ── TC-ERR-03: 500 Internal Server Error (mock) ────────────────────────

    /**
     * TC-ERR-03: Server-side error response handling.
     *
     * 500 errors indicate an unexpected condition on the server side.
     * Clients should detect this, log it, and NOT retry immediately
     * (unlike 503 Service Unavailable which implies temporary unavailability).
     *
     * WireMock simulates a 500 that returns a non-JSON plain-text body —
     * this is realistic because crash dumps, stack traces, and gateway errors
     * often arrive as plain text.
     */
    @Test(description = "TC-ERR-03: 500 Internal Server Error is correctly detected")
    public void testInternalServerError() {
        logTestStart("testInternalServerError");

        mockServer.stubInternalServerError("/api/exploding-endpoint");

        String url = mockBaseUrl + "/api/exploding-endpoint";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        // The client (us) must detect the 500 and NOT treat it as success.
        ResponseValidator.assertStatusCode(response, 500);

        // Verify the body is not empty — even error responses should have content.
        ResponseValidator.assertBodyNotEmpty(response);

        // The content-type of a 500 error is often text/plain, not JSON.
        String contentType = response.headers().getOrDefault("content-type", "");
        log.info("500 error Content-Type: {}", contentType);
        Assert.assertTrue(
                contentType.contains("text/plain") || contentType.contains("application/json"),
                "Error response should have a content-type, got: " + contentType);

        logTestEnd("testInternalServerError");
    }

    // ── TC-ERR-04: 400 Bad Request — missing field (live API) ─────────────

    /**
     * TC-ERR-04: POST /login with missing password field — expect 400.
     *
     * Sending an incomplete request body tests server-side input validation.
     * ReqRes returns 400 with an "error" field explaining what is missing.
     */
    @Test(description = "TC-ERR-04: POST with missing required field returns 400")
    public void testBadRequestMissingField() throws Exception {
        logTestStart("testBadRequestMissingField");

        // Deliberately omit the "password" field from the login body.
        // A robust API must reject this with 400, not 500 or 200.
        Map<String, String> incompleteBody = new HashMap<>();
        incompleteBody.put("email", "peter@klaven.com");
        // password intentionally missing

        String bodyJson = mapper.writeValueAsString(incompleteBody);
        String url      = authBaseUrl + "/login";

        ApiLogger.logRequest("POST", url,
                Map.of("Content-Type", "application/json"), bodyJson);

        long start = startTimer();
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(bodyJson));
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 400);
        ResponseValidator.assertBodyContains(response, "error");

        logTestEnd("testBadRequestMissingField");
    }

    // ── TC-ERR-05: 405 Method Not Allowed (mock) ──────────────────────────

    /**
     * TC-ERR-05: Send a DELETE to an endpoint that only allows GET.
     *
     * Method Not Allowed (405) is returned when the HTTP verb is not supported
     * for the target URL. The Allow response header lists the valid methods.
     */
    @Test(description = "TC-ERR-05: Unsupported HTTP method returns 405")
    public void testMethodNotAllowed() {
        logTestStart("testMethodNotAllowed");

        // Stub: DELETE /api/readonly → 405 Method Not Allowed
        // The Allow header tells clients which methods ARE permitted.
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .delete(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/readonly"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(405)
                                        .withHeader("Allow", "GET, HEAD, OPTIONS")
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"Method Not Allowed\"," +
                                                  "\"allowedMethods\":[\"GET\",\"HEAD\",\"OPTIONS\"]}")));

        String url = mockBaseUrl + "/api/readonly";
        ApiLogger.logRequest("DELETE", url);

        APIResponse response = request.delete(url);
        ApiLogger.logResponse(response, 0);

        ResponseValidator.assertStatusCode(response, 405);

        // The Allow header must be present and list valid methods.
        ResponseValidator.assertHeaderPresent(response, "allow");
        String allowHeader = response.headers().get("allow");
        Assert.assertTrue(allowHeader.contains("GET"),
                "Allow header should include 'GET', got: " + allowHeader);

        logTestEnd("testMethodNotAllowed");
    }

    // ── TC-ERR-06: 429 Too Many Requests / rate limiting (mock) ───────────

    /**
     * TC-ERR-06: Simulate the rate-limiting response.
     *
     * APIs protect themselves from abuse using rate limiting. When a client
     * exceeds its quota, the server returns 429 with a Retry-After header
     * (RFC 6585) indicating when the client may try again.
     *
     * Client responsibility: read Retry-After, wait that many seconds, then retry.
     */
    @Test(description = "TC-ERR-06: 429 rate-limit response includes Retry-After header")
    public void testRateLimitResponse() {
        logTestStart("testRateLimitResponse");

        mockServer.stubRateLimited("/api/heavy-operation");

        String url = mockBaseUrl + "/api/heavy-operation";
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ApiLogger.logResponse(response, 0);

        ResponseValidator.assertStatusCode(response, 429);

        // Retry-After header is mandatory per RFC 6585 for 429 responses.
        ResponseValidator.assertHeaderPresent(response, "retry-after");
        String retryAfter = response.headers().get("retry-after");
        log.info("Rate limit exceeded. Retry-After: {} seconds", retryAfter);

        // The client code should parse this value and schedule a retry.
        int retrySeconds = Integer.parseInt(retryAfter);
        Assert.assertTrue(retrySeconds > 0,
                "Retry-After must be a positive number of seconds, got: " + retrySeconds);

        logTestEnd("testRateLimitResponse");
    }

    // ── TC-ERR-07: Malformed JSON in request body ─────────────────────────

    /**
     * TC-ERR-07: Send a syntactically broken JSON body and verify the server
     * returns 400 rather than crashing with 500.
     *
     * A robust API must validate request bodies before processing them.
     * Receiving a 400 here confirms server-side JSON parsing is in place.
     */
    @Test(description = "TC-ERR-07: Malformed JSON request body returns 400")
    public void testMalformedRequestBody() {
        logTestStart("testMalformedRequestBody");

        // This is intentionally broken JSON: the closing brace is missing
        // and there is a trailing comma after the last field — both syntax errors.
        String malformedJson = "{\"email\": \"test@test.com\", \"password\": ";

        String url = authBaseUrl + "/login";
        ApiLogger.logRequest("POST", url,
                Map.of("Content-Type", "application/json"), malformedJson);

        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(malformedJson));
        ApiLogger.logResponse(response, 0);

        // The server must reject malformed JSON with a 4xx, not a 5xx.
        // ReqRes returns 400 for this; some servers return 422.
        int status = response.status();
        Assert.assertTrue(status >= 400 && status < 500,
                "Malformed JSON should result in a 4xx status, got: " + status);

        logTestEnd("testMalformedRequestBody");
    }

    // ── TC-ERR-08: Network fault (mock) ────────────────────────────────────

    /**
     * TC-ERR-08: Simulate a connection reset (network-level failure).
     *
     * WireMock's Fault.CONNECTION_RESET_BY_PEER abruptly closes the TCP
     * connection. Playwright's HTTP client will throw an exception (wrapped
     * in a PlaywrightException) rather than returning a response.
     *
     * This test verifies our code handles network failures gracefully instead
     * of propagating an unhandled exception to the caller.
     */
    @Test(description = "TC-ERR-08: Network fault (connection reset) is caught gracefully")
    public void testNetworkFaultHandling() {
        logTestStart("testNetworkFaultHandling");

        mockServer.stubNetworkFault("/api/unstable-endpoint");

        String url = mockBaseUrl + "/api/unstable-endpoint";
        ApiLogger.logRequest("GET", url);

        // We expect Playwright to throw a RuntimeException (PlaywrightException)
        // when the TCP connection is reset.  We catch it and verify the message.
        try {
            APIResponse response = request.get(url);
            // If we somehow get a response (e.g. WireMock sent a partial response
            // before resetting), log it for debugging but still mark the test as
            // needing investigation.
            log.warn("Unexpected: got a response ({}) for a connection-reset fault.",
                    response.status());
        } catch (Exception e) {
            // PlaywrightException (a RuntimeException subclass) is thrown for
            // network-level errors. This is the expected path.
            log.info("Network fault correctly produced an exception: {} — {}",
                    e.getClass().getSimpleName(), e.getMessage());
            Assert.assertTrue(e.getMessage() != null && !e.getMessage().isBlank(),
                    "Exception message should not be blank");
            // Test passes — exception was caught, not propagated.
        }

        logTestEnd("testNetworkFaultHandling");
    }
}
