package com.apinexus.mock;

/**
 * MockServerStrategy — Interface that both the LOCAL and REMOTE mock server
 * managers must implement.
 *
 * WHY AN INTERFACE?
 * ─────────────────
 * Without this interface, BaseApiTest would have to pick the concrete class
 * (MockServerManager vs RemoteMockServerManager) at compile-time.  With the
 * interface, BaseApiTest holds a reference of type MockServerStrategy, and a
 * one-line config change (mock.mode=local → mock.mode=remote) switches the
 * actual implementation at runtime.  No test class changes at all.
 *
 * The interface mirrors the public stub-registration methods on
 * MockServerManager.  Any stub method added there must also be added here and
 * implemented in RemoteMockServerManager.
 *
 *  Implementation map
 *  ──────────────────
 *  MockServerStrategy
 *    ├── MockServerManager          (local embedded WireMock JVM server)
 *    └── RemoteMockServerManager    (remote WireMock via /__admin REST API)
 */
public interface MockServerStrategy {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Starts the mock server.
     * For the LOCAL implementation this binds a TCP port.
     * For the REMOTE implementation this is a no-op (the server is already up).
     */
    void start();

    /**
     * Stops the mock server.
     * For the LOCAL implementation this releases the TCP port.
     * For the REMOTE implementation this is a no-op.
     */
    void stop();

    /**
     * Removes all registered stubs and resets all scenario states.
     * Must be called in @AfterMethod to prevent stub pollution across tests.
     */
    void resetAllStubs();

    /**
     * Returns the full base URL of the mock server (e.g. http://localhost:8089
     * or https://abc.wiremockapi.cloud).
     */
    String getBaseUrl();

    // ── Stub registration ─────────────────────────────────────────────────

    /** GET /api/users → 200 with the users_response.json body */
    void stubGetUsers();

    /** POST /api/users → 201 with the provided JSON body */
    void stubCreateUser(String responseBody);

    /** GET {path} → 404 with the error_response.json body */
    void stubNotFound(String path);

    /** GET {path} → 500 with a plain-text error body */
    void stubInternalServerError(String path);

    /** GET {path} → 200 after delayMs milliseconds */
    void stubSlowResponse(String path, int delayMs);

    /**
     * GET {path} → TCP connection reset (no HTTP response).
     * NOTE: Remote WireMock Cloud also supports fault injection via the Admin API.
     */
    void stubNetworkFault(String path);

    /**
     * GET {path}:
     *   • with  Authorization: Bearer {validToken} → 200 + successBody
     *   • without that header                       → 401
     */
    void stubBearerAuthProtected(String path, String validToken, String successBody);

    /**
     * Registers a 3-state WireMock Scenario on GET {path}:
     *   STARTED → PENDING → PROCESSING → COMPLETE
     */
    void stubStatefulOrder(String path, String scenarioName);

    /** GET {path} → 429 Too Many Requests + Retry-After: 60 */
    void stubRateLimited(String path);

    /** POST {path} (multipart) → 201 with file-metadata body */
    void stubFileUpload(String path);
}
