package com.apinexus.tests.auth;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AuthenticationTest — Tests every authentication pattern used in real-world APIs:
 *
 *   ┌──────────────────────────────────────────────────────────────────────────┐
 *   │ TC  │ Scenario                              │ Expected                  │
 *   ├──────────────────────────────────────────────────────────────────────────┤
 *   │  01 │ Login with valid credentials          │ 200 + token               │
 *   │  02 │ Login with invalid credentials        │ 400                       │
 *   │  03 │ Bearer token in Authorization header  │ 200 (via WireMock)        │
 *   │  04 │ Missing Bearer token                  │ 401 (via WireMock)        │
 *   │  05 │ Basic Authentication (base64)         │ 200 (via WireMock)        │
 *   │  06 │ API Key in request header             │ 200 (via WireMock)        │
 *   │  07 │ API Key in query parameter            │ 200 (via WireMock)        │
 *   │  08 │ Token obtained from login → reused    │ 200 (chained auth flow)   │
 *   └──────────────────────────────────────────────────────────────────────────┘
 *
 * All scenarios run entirely against the embedded WireMock server.
 *
 * NOTE: This suite originally exercised reqres.in's live /login endpoint.
 * reqres.in has since started requiring an x-api-key header on every
 * request (a breaking change to their previously free, key-less API), so
 * login is now simulated via mockServer.stubReqResLogin() instead — same
 * request/response contract, but fully offline and immune to third-party
 * policy changes.
 */
@Test(groups = "auth")
public class AuthenticationTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Credentials used to exercise the mocked login endpoint.
    private static final String VALID_EMAIL    = "eve.holt@reqres.in";
    private static final String VALID_PASSWORD = "cityslicka";
    private static final String WRONG_EMAIL    = "invalid@no.domain";
    private static final String WRONG_PASSWORD = "wrongpassword";
    private static final String MOCK_TOKEN      = "mock-login-token-QpwL5tpe83";

    // Mock API key value — must match what MockServerManager stubs against
    private static final String API_KEY        = "nexus-api-key-secret-9876";
    private static final String BEARER_TOKEN   = "mock-bearer-token-abc123";

    // Reset stubs after each test so login/auth registrations from one test
    // never leak into another (matches the pattern used in MockServerTest).
    @AfterMethod(alwaysRun = true)
    public void resetStubs() {
        mockServer.resetAllStubs();
    }

    // ── TC-AUTH-01: Valid login ────────────────────────────────────────────

    /**
     * TC-AUTH-01: POST /login with valid credentials returns a token.
     *
     * Real-world significance: before most protected API calls, a client must
     * first authenticate to obtain a session token (JWT, opaque token, etc.).
     * This test verifies the happy path of that first step.
     */
    @Test(description = "TC-AUTH-01: Valid login credentials return a Bearer token")
    public void testValidLogin() throws Exception {
        logTestStart("testValidLogin");

        // Register the mock login endpoint: this exact email/password pair
        // succeeds; anything else (TC-AUTH-02) falls through to 400.
        mockServer.stubReqResLogin("/api/login", VALID_EMAIL, VALID_PASSWORD, MOCK_TOKEN);

        // Build the login request body as a JSON string.
        Map<String, String> loginBody = new HashMap<>();
        loginBody.put("email",    VALID_EMAIL);
        loginBody.put("password", VALID_PASSWORD);
        String bodyJson = mapper.writeValueAsString(loginBody);

        String url = mockBaseUrl + "/api/login";
        ApiLogger.logRequest("POST", url,
                Map.of("Content-Type", "application/json"), bodyJson);

        long start = startTimer();
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(bodyJson));
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertContentType(response, "application/json");

        // Parse the body and verify a "token" field is present.
        JsonNode body = mapper.readTree(response.text());
        Assert.assertTrue(body.has("token"), "Response must contain a 'token' field");
        String token = body.get("token").asText();
        Assert.assertFalse(token.isBlank(), "Token value must not be blank");
        log.info("Received token (first 10 chars): {}...", token.substring(0, Math.min(10, token.length())));

        logTestEnd("testValidLogin");
    }

    // ── TC-AUTH-02: Invalid login ─────────────────────────────────────────

    /**
     * TC-AUTH-02: POST /login with wrong credentials must return 400 Bad Request.
     *
     * ReqRes returns 400 with a JSON error body when credentials are wrong.
     * Verifying error responses is just as important as verifying success,
     * because a security flaw might cause the API to incorrectly return 200.
     */
    @Test(description = "TC-AUTH-02: Invalid credentials return 400 and an error body")
    public void testInvalidLogin() throws Exception {
        logTestStart("testInvalidLogin");

        // Same login stub as TC-AUTH-01: only VALID_EMAIL/VALID_PASSWORD
        // succeed, so this wrong pair falls through to the 400 catch-all.
        mockServer.stubReqResLogin("/api/login", VALID_EMAIL, VALID_PASSWORD, MOCK_TOKEN);

        Map<String, String> badLogin = new HashMap<>();
        badLogin.put("email",    WRONG_EMAIL);
        badLogin.put("password", WRONG_PASSWORD);
        String bodyJson = mapper.writeValueAsString(badLogin);

        String url = mockBaseUrl + "/api/login";
        ApiLogger.logRequest("POST", url,
                Map.of("Content-Type", "application/json"), bodyJson);

        long start = startTimer();
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(bodyJson));
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        // 400 Bad Request: credentials were rejected.
        ResponseValidator.assertStatusCode(response, 400);

        // The error body must contain an "error" field explaining the rejection.
        ResponseValidator.assertBodyContains(response, "error");

        logTestEnd("testInvalidLogin");
    }

    // ── TC-AUTH-03: Bearer token (valid) ─────────────────────────────────

    /**
     * TC-AUTH-03: Request with a valid Bearer token in the Authorization header.
     *
     * This uses WireMock to simulate a protected endpoint.
     * The Authorization header follows the RFC 6750 format:
     *   Authorization: Bearer <token>
     */
    @Test(description = "TC-AUTH-03: Valid Bearer token returns 200")
    public void testValidBearerToken() {
        logTestStart("testValidBearerToken");

        // Register a WireMock stub that checks the Authorization header.
        // Requests WITH the correct token → 200; without it → 401.
        mockServer.stubBearerAuthProtected(
                "/api/protected-resource",
                BEARER_TOKEN,
                "{\"id\": 1, \"data\": \"secret content\"}");

        String url = mockBaseUrl + "/api/protected-resource";
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + BEARER_TOKEN   // RFC 6750 format
        );
        ApiLogger.logRequest("GET", url, headers, null);

        long start = startTimer();
        APIResponse response = request.get(url,
                RequestOptions.create()
                        .setHeader("Authorization", "Bearer " + BEARER_TOKEN));
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertBodyContains(response, "secret content");

        logTestEnd("testValidBearerToken");
    }

    // ── TC-AUTH-04: Missing Bearer token ─────────────────────────────────

    /**
     * TC-AUTH-04: Request WITHOUT an Authorization header must return 401.
     *
     * Security test: ensures the API does not accidentally serve protected
     * resources to unauthenticated callers.
     */
    @Test(description = "TC-AUTH-04: Missing Bearer token returns 401 Unauthorized")
    public void testMissingBearerToken() {
        logTestStart("testMissingBearerToken");

        // The stub from TC-AUTH-03 falls through to the 401 response
        // when no Authorization header is present.
        mockServer.stubBearerAuthProtected(
                "/api/secured",
                BEARER_TOKEN,
                "{\"data\":\"ok\"}");

        String url = mockBaseUrl + "/api/secured";
        ApiLogger.logRequest("GET", url);   // no headers — intentionally unauthenticated

        long start = startTimer();
        // No Authorization header in this request
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 401);
        ResponseValidator.assertBodyContains(response, "Unauthorized");

        logTestEnd("testMissingBearerToken");
    }

    // ── TC-AUTH-05: Basic Authentication ─────────────────────────────────

    /**
     * TC-AUTH-05: HTTP Basic Authentication (RFC 7617).
     *
     * Basic auth encodes "username:password" in Base64 and sends it as:
     *   Authorization: Basic <base64("username:password")>
     *
     * This is common for simple admin APIs and internal tooling.
     * We simulate it against a WireMock stub.
     */
    @Test(description = "TC-AUTH-05: Basic authentication header is accepted")
    public void testBasicAuthentication() {
        logTestStart("testBasicAuthentication");

        String username = "admin";
        String password = "secret123";

        // Base64 encode "username:password" as per RFC 7617.
        // The colon separates username from password — neither may contain a colon.
        String credentials   = username + ":" + password;
        String base64Encoded = Base64.getEncoder().encodeToString(
                credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String basicAuthHeader = "Basic " + base64Encoded;

        // Register a WireMock stub that expects the exact Basic auth header.
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/admin"))
                        .withHeader("Authorization",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .equalTo(basicAuthHeader))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"role\":\"admin\",\"access\":\"granted\"}")));

        String url = mockBaseUrl + "/api/admin";
        Map<String, String> headers = Map.of("Authorization", "Basic ****[see log]");
        ApiLogger.logRequest("GET", url, headers, null);

        long start = startTimer();
        APIResponse response = request.get(url,
                RequestOptions.create()
                        .setHeader("Authorization", basicAuthHeader));
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertBodyContains(response, "admin");
        ResponseValidator.assertBodyContains(response, "granted");

        logTestEnd("testBasicAuthentication");
    }

    // ── TC-AUTH-06: API Key in header ─────────────────────────────────────

    /**
     * TC-AUTH-06: API Key sent in a custom request header (X-API-Key).
     *
     * Many public APIs use a custom header (X-API-Key, X-Auth-Token, etc.)
     * instead of the standard Authorization header. The mechanics are the same:
     * a secret value is included in the request header.
     */
    @Test(description = "TC-AUTH-06: API key in X-API-Key header grants access")
    public void testApiKeyInHeader() {
        logTestStart("testApiKeyInHeader");

        // Stub: GET /api/data with header X-API-Key = API_KEY → 200
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/data"))
                        .withHeader("X-API-Key",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .equalTo(API_KEY))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"data\":\"sensitive result\",\"quota\":1000}")));

        String url = mockBaseUrl + "/api/data";
        ApiLogger.logRequest("GET", url, Map.of("X-API-Key", "****[masked]"), null);

        long start = startTimer();
        APIResponse response = request.get(url,
                RequestOptions.create()
                        .setHeader("X-API-Key", API_KEY));
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertBodyContains(response, "sensitive result");

        logTestEnd("testApiKeyInHeader");
    }

    // ── TC-AUTH-07: API Key in query parameter ────────────────────────────

    /**
     * TC-AUTH-07: API Key passed as a URL query parameter (?apikey=...).
     *
     * Some older APIs and third-party integrations pass the API key as a
     * query parameter. This is less secure than a header (the key appears
     * in access logs and browser history) but still common in legacy systems.
     */
    @Test(description = "TC-AUTH-07: API key as query parameter grants access")
    public void testApiKeyInQueryParam() {
        logTestStart("testApiKeyInQueryParam");

        // Stub: GET /api/reports?apikey=<API_KEY> → 200
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlPathEqualTo("/api/reports"))  // match path only
                        .withQueryParam("apikey",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .equalTo(API_KEY))        // match specific query param
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"reports\":[{\"id\":1,\"name\":\"Q1\"}]}")));

        // Embed the API key directly in the URL query string.
        String url = mockBaseUrl + "/api/reports?apikey=" + API_KEY;
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertBodyContains(response, "reports");

        logTestEnd("testApiKeyInQueryParam");
    }

    // ── TC-AUTH-08: Token obtained via login → used in subsequent call ────

    /**
     * TC-AUTH-08: Login → obtain token → use token in a protected request.
     *
     * This is the most realistic auth flow: a client authenticates first,
     * extracts the token from the login response, then uses it in all
     * subsequent requests. This is the pattern used in web apps, mobile apps,
     * and service-to-service communication.
     */
    @Test(description = "TC-AUTH-08: Token from login is reused in a protected call")
    public void testLoginThenAccessProtectedResource() throws Exception {
        logTestStart("testLoginThenAccessProtectedResource");

        // ── Step 1: Login to obtain a token ──────────────────────────────
        ApiLogger.logStep(1, "POST /login to obtain token");

        mockServer.stubReqResLogin("/api/login", VALID_EMAIL, VALID_PASSWORD, MOCK_TOKEN);

        Map<String, String> loginBody = new HashMap<>();
        loginBody.put("email",    VALID_EMAIL);
        loginBody.put("password", VALID_PASSWORD);
        String loginJson = mapper.writeValueAsString(loginBody);

        APIResponse loginResponse = request.post(mockBaseUrl + "/api/login",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(loginJson));

        ResponseValidator.assertStatusCode(loginResponse, 200);

        // Extract the token from the login response body.
        // mapper.readTree() parses JSON; .get("token") navigates to that key.
        String token = mapper.readTree(loginResponse.text())
                .get("token").asText();
        Assert.assertFalse(token.isBlank(), "Login must return a non-blank token");
        log.info("Step 1 complete. Token obtained: {}...",
                token.substring(0, Math.min(6, token.length())));

        // ── Step 2: Use the token to access a protected resource ──────────
        ApiLogger.logStep(2, "GET /api/users/2 with the obtained token");

        // Register a WireMock stub that validates this specific token.
        mockServer.stubBearerAuthProtected(
                "/api/secure-users",
                token,
                "{\"users\":[{\"id\":1,\"name\":\"Protected Data\"}]}");

        APIResponse protectedResponse = request.get(mockBaseUrl + "/api/secure-users",
                RequestOptions.create()
                        .setHeader("Authorization", "Bearer " + token));

        ApiLogger.logResponse(protectedResponse, 0);
        ResponseValidator.assertStatusCode(protectedResponse, 200);
        ResponseValidator.assertBodyContains(protectedResponse, "Protected Data");

        log.info("Step 2 complete. Protected resource accessed successfully.");
        logTestEnd("testLoginThenAccessProtectedResource");
    }
}
