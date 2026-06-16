package com.apinexus.tests.base;

import com.apinexus.config.ConfigManager;
import com.apinexus.mock.MockServerManager;
import com.apinexus.mock.MockServerStrategy;
import com.apinexus.mock.RemoteMockServerManager;
import com.apinexus.utils.ApiLogger;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

/**
 * BaseApiTest — Parent class for every test class in ApiNexus.
 *
 * RESPONSIBILITY:
 *   1. Create/destroy the Playwright engine and APIRequestContext (expensive objects)
 *   2. Start/stop the WireMock mock server
 *   3. Expose ConfigManager and MockServerManager to subclasses
 *
 * LIFECYCLE (@BeforeSuite / @AfterSuite):
 *   TestNG calls @BeforeSuite once before ALL tests in the suite.
 *   TestNG calls @AfterSuite  once after  ALL tests in the suite.
 *   This means we pay the cost of Playwright initialisation only once,
 *   regardless of how many test classes extend BaseApiTest.
 *
 * HOW TO USE:
 *   public class MyCrudTest extends BaseApiTest {
 *       @Test
 *       public void myTest() {
 *           APIResponse response = request.get(baseUrl + "/posts/1");
 *           ...
 *       }
 *   }
 */
public abstract class BaseApiTest {

    protected static final Logger log = LoggerFactory.getLogger(BaseApiTest.class);

    // ── Playwright objects ────────────────────────────────────────────────

    /**
     * Playwright — the main entry point for the Playwright library.
     * Creating it initialises the playwright browser driver process.
     * We use it purely for its HTTP client; no browser is opened.
     */
    protected static Playwright playwright;

    /**
     * APIRequestContext — Playwright's HTTP client.
     * Similar to Java's HttpClient or RestAssured's RequestSpecification.
     * It wraps all HTTP mechanics: connection pooling, cookie jar, headers.
     */
    protected static APIRequestContext request;

    // ── Configuration & mock server ───────────────────────────────────────

    /** Singleton configuration loader — reads config.properties. */
    protected static final ConfigManager config = ConfigManager.getInstance();

    /**
     * Manages mock server lifecycle and stub registration.
     * Type is MockServerStrategy so the same field works for both local
     * (MockServerManager) and remote (RemoteMockServerManager) implementations.
     */
    protected static MockServerStrategy mockServer;

    /** Convenience field: base URL of the JSONPlaceholder live API. */
    protected static String baseUrl;

    /**
     * Convenience field: base URL of the ReqRes live auth API.
     * No test class currently calls this — reqres.in began requiring an
     * x-api-key header on every request, so AuthenticationTest,
     * SchemaValidationTest, PaginationFilteringTest, and
     * ErrorHandlingTest#testBadRequestMissingField/testMalformedRequestBody
     * now simulate the same contract via mockServer.stubReqResLogin() /
     * stubReqResSingleUser() / stubReqResUsersPage() instead. Kept here in
     * case a future API key is configured for opt-in live testing.
     */
    protected static String authBaseUrl;

    /** Convenience field: base URL of the WireMock mock server. */
    protected static String mockBaseUrl;

    // ── Suite setup ───────────────────────────────────────────────────────

    /**
     * @BeforeSuite — executed once before the first test in the entire suite.
     *
     * Initialisation order matters:
     *   1. Read config  (needed for URLs and port numbers)
     *   2. Start WireMock  (starts the TCP listener)
     *   3. Create Playwright + APIRequestContext  (sets up the HTTP client)
     */
    @BeforeSuite(alwaysRun = true)
    public void suiteSetUp() {
        log.info("════════ ApiNexus Suite Setup Starting ════════");

        // ── Step 1: Read configuration ─────────────────────────────────────
        baseUrl     = config.getBaseUrl();
        authBaseUrl = config.getAuthBaseUrl();
        log.info("Base URL         : {}", baseUrl);
        log.info("Auth Base URL    : {}", authBaseUrl);

        // ── Step 2: Create the mock server (local or remote) ──────────────
        // mock.mode=local  → embedded WireMock JVM server (default)
        // mock.mode=remote → register stubs via WireMock Admin REST API
        String mockMode = config.getMockMode();
        log.info("Mock Mode        : {}", mockMode);

        if ("remote".equals(mockMode)) {
            // Remote mode: point at a cloud-hosted WireMock server.
            // The URL and optional API key come from config.properties.
            String remoteUrl = config.getMockRemoteUrl();
            String apiKey    = config.getMockRemoteApiKey();
            mockServer = new RemoteMockServerManager(remoteUrl, apiKey);
            log.info("Remote WireMock  : {}", remoteUrl);
        } else {
            // Local mode (default): start an embedded WireMock on the configured port.
            int mockPort = config.getMockServerPort();
            mockServer   = new MockServerManager(mockPort);
        }

        mockServer.start();
        mockBaseUrl = mockServer.getBaseUrl();
        log.info("Mock Server URL  : {}", mockBaseUrl);

        // ── Step 3: Initialise Playwright ─────────────────────────────────
        // Playwright.create() downloads browser binaries on first run
        // (into ~/.cache/ms-playwright). Subsequent runs reuse the cache.
        playwright = Playwright.create();
        log.info("Playwright engine initialised");

        // ── Step 4: Create APIRequestContext ──────────────────────────────
        // newContext() builds a request context with:
        //   - baseURL: prepended to relative paths in get("/posts") calls
        //   - ignoreHTTPSErrors: skip SSL cert validation (useful for self-signed certs)
        //   - timeout: maximum milliseconds to wait for any single request
        request = playwright.request().newContext(
                new APIRequest.NewContextOptions()
                        .setBaseURL(baseUrl)
                        // setIgnoreHTTPSErrors(true) would go here for self-signed cert envs
                        .setTimeout(config.getLong("http.read.timeout"))
        );
        log.info("APIRequestContext created with baseURL={}", baseUrl);
        log.info("════════ ApiNexus Suite Setup Complete ════════");
    }

    // ── Suite teardown ────────────────────────────────────────────────────

    /**
     * @AfterSuite — executed once after the last test in the entire suite.
     *
     * Close in reverse order of creation to avoid "use after close" errors:
     *   1. Close APIRequestContext (flushes in-flight requests)
     *   2. Close Playwright (terminates the driver process)
     *   3. Stop WireMock (releases the TCP port)
     */
    @AfterSuite(alwaysRun = true)
    public void suiteTearDown() {
        log.info("════════ ApiNexus Suite Teardown Starting ════════");

        // Closing the context flushes cookies, aborts pending requests,
        // and frees connection-pool resources.
        if (request != null) {
            request.dispose();
            log.info("APIRequestContext disposed");
        }

        // Playwright.close() terminates the background driver process.
        if (playwright != null) {
            playwright.close();
            log.info("Playwright engine closed");
        }

        // Stop WireMock after Playwright so there is no window where a
        // late-flying request hits a stopped server.
        if (mockServer != null) {
            mockServer.stop();
        }

        log.info("════════ ApiNexus Suite Teardown Complete ════════");
    }

    // ── Shared helpers for subclasses ─────────────────────────────────────

    /**
     * Measures the elapsed time of an HTTP call by recording timestamps
     * around the call. Returns elapsed milliseconds.
     *
     * Usage in tests:
     *   long start = startTimer();
     *   APIResponse r = request.get(url);
     *   long elapsed = elapsedMs(start);
     */
    protected long startTimer() {
        return System.currentTimeMillis();
    }

    protected long elapsedMs(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    /**
     * Logs the beginning of a test method by name.
     * Call this as the very first line of every @Test method for clean logs.
     */
    protected void logTestStart(String testName) {
        ApiLogger.logTestStart(testName);
    }

    protected void logTestEnd(String testName) {
        ApiLogger.logTestEnd(testName);
    }
}
