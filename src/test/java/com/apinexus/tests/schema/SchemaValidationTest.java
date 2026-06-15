package com.apinexus.tests.schema;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * SchemaValidationTest — Verifies that API responses conform to their
 * agreed-upon JSON Schema contracts.
 *
 * WHY JSON SCHEMA VALIDATION?
 * Status code and field-value assertions tell you whether the right data
 * came back for a specific test input. Schema validation tells you whether
 * the STRUCTURE of the response (types, required fields, format constraints)
 * matches the API contract — catching regressions like:
 *   - A field changing from integer to string
 *   - A required field going missing
 *   - A new required field being added that breaks old clients
 *
 * All schema files live in src/test/resources/schemas/ and are validated
 * using the networknt JSON Schema validator (draft-07).
 *
 * Scenarios covered:
 *   TC-SCHEMA-01  GET /users/2  — full user schema validation
 *   TC-SCHEMA-02  GET /posts/1  — full post schema validation
 *   TC-SCHEMA-03  POST /users   — created-user response schema validation
 *   TC-SCHEMA-04  Intentionally wrong schema — expect failure (negative test)
 */
@Test(groups = "schema")
public class SchemaValidationTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── TC-SCHEMA-01: User schema ──────────────────────────────────────────

    /**
     * TC-SCHEMA-01: GET /users/2 — Validate that the response matches the
     * user JSON schema defined in schemas/user_schema.json.
     *
     * The schema file specifies:
     *   - data.id         must be an integer
     *   - data.email      must be a valid email format
     *   - data.first_name must be a non-empty string
     *   - data.avatar     must be a valid URI
     *   - support.url     must be a valid URI
     */
    @Test(description = "TC-SCHEMA-01: ReqRes single-user response matches user_schema.json")
    public void testUserResponseSchema() {
        logTestStart("testUserResponseSchema");

        // Use the auth base URL (reqres.in) for the users endpoint
        String url = authBaseUrl + "/users/2";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);

        // assertJsonSchema() loads the schema from the classpath, parses the
        // response body as JSON, and runs the networknt validator against it.
        // Any violation causes the test to fail with a descriptive error message.
        ResponseValidator.assertJsonSchema(response, "schemas/user_schema.json");

        log.info("User schema validation passed for GET /users/2");
        logTestEnd("testUserResponseSchema");
    }

    // ── TC-SCHEMA-02: Post schema ──────────────────────────────────────────

    /**
     * TC-SCHEMA-02: GET /posts/1 — Validate the response against post_schema.json.
     *
     * The schema expects:
     *   - userId: integer >= 1
     *   - id:     integer >= 1
     *   - title:  non-empty string
     *   - body:   non-empty string
     *   - No additional properties (additionalProperties: false)
     */
    @Test(description = "TC-SCHEMA-02: JSONPlaceholder post response matches post_schema.json")
    public void testPostResponseSchema() {
        logTestStart("testPostResponseSchema");

        String url = baseUrl + "/posts/1";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertJsonSchema(response, "schemas/post_schema.json");

        log.info("Post schema validation passed for GET /posts/1");
        logTestEnd("testPostResponseSchema");
    }

    // ── TC-SCHEMA-03: Schema validation across multiple posts ─────────────

    /**
     * TC-SCHEMA-03: Fetch 5 different posts and validate each one's schema.
     *
     * Rationale: Schema violations might only affect certain records
     * (e.g. posts with null bodies, or posts belonging to deleted users).
     * Running schema validation across a small set of records increases coverage.
     */
    @Test(description = "TC-SCHEMA-03: Schema validated across multiple post IDs")
    public void testSchemaValidationMultiplePosts() {
        logTestStart("testSchemaValidationMultiplePosts");

        // Array of post IDs to validate
        int[] postIds = {1, 10, 25, 50, 100};

        for (int id : postIds) {
            ApiLogger.logStep(id, "Validating schema for GET /posts/" + id);

            String url = baseUrl + "/posts/" + id;
            APIResponse response = request.get(url);

            // Each post response must match the same schema.
            // If any post violates the schema, the test fails immediately.
            ResponseValidator.assertStatusCode(response, 200);
            ResponseValidator.assertJsonSchema(response, "schemas/post_schema.json");

            log.info("Schema OK for post id={}", id);
        }

        logTestEnd("testSchemaValidationMultiplePosts");
    }

    // ── TC-SCHEMA-04: Negative schema test ────────────────────────────────

    /**
     * TC-SCHEMA-04: Validate a WireMock mock response against a schema that
     * it intentionally does NOT satisfy — verify the validator catches the error.
     *
     * This is a meta-test: we're testing that our schema validator WORKS.
     * If this test were to pass unexpectedly, it would mean the validator is
     * silently accepting invalid responses, which would give false confidence.
     */
    @Test(description = "TC-SCHEMA-04: Schema validator correctly rejects an invalid response")
    public void testSchemaNegativeValidation() {
        logTestStart("testSchemaNegativeValidation");

        // Register a WireMock stub that returns a BROKEN user response —
        // missing required fields ("data", "support") that user_schema.json demands.
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/broken-user"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        // This JSON is missing the "data" and "support"
                                        // wrapper objects required by user_schema.json.
                                        .withBody("{\"id\": 1, \"name\": \"Broken Structure\"}")));

        String url = mockBaseUrl + "/api/broken-user";
        APIResponse response = request.get(url);
        ResponseValidator.assertStatusCode(response, 200);

        // We expect the schema validation to FAIL for this broken response.
        // We catch the AssertionError that assertJsonSchema() throws and treat it
        // as the expected (correct) outcome.
        boolean schemaViolationDetected = false;
        try {
            ResponseValidator.assertJsonSchema(response, "schemas/user_schema.json");
            // If we reach here, the validator did NOT detect the schema violation —
            // that is the failure condition for this negative test.
        } catch (AssertionError e) {
            // This is what we WANT: the validator correctly rejected the broken response.
            schemaViolationDetected = true;
            log.info("Schema validator correctly rejected invalid response. Error: {}", e.getMessage());
        }

        Assert.assertTrue(schemaViolationDetected,
                "Schema validator should have rejected the broken response but it passed unexpectedly");

        logTestEnd("testSchemaNegativeValidation");
    }

    // ── TC-SCHEMA-05: Response headers schema / validation ────────────────

    /**
     * TC-SCHEMA-05: Verify presence of critical response headers.
     *
     * Beyond the body, production APIs should return certain headers:
     *   - Content-Type: so clients know how to parse the body
     *   - X-RateLimit-Limit: informs clients of rate limits (if applicable)
     *
     * This test checks that headers the contract specifies are always present.
     */
    @Test(description = "TC-SCHEMA-05: Required response headers are present")
    public void testRequiredResponseHeaders() {
        logTestStart("testRequiredResponseHeaders");

        String url = baseUrl + "/posts/1";
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ApiLogger.logResponse(response, 0);

        // content-type must always be present for any JSON API.
        ResponseValidator.assertHeaderPresent(response, "content-type");
        // content-type must indicate JSON.
        ResponseValidator.assertContentType(response, "application/json");

        // Log all headers for documentation / debugging.
        log.info("All response headers:");
        response.headers().forEach((k, v) -> log.info("  {} = {}", k, v));

        logTestEnd("testRequiredResponseHeaders");
    }
}
