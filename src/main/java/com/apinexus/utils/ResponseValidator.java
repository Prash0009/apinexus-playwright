package com.apinexus.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * ResponseValidator — A reusable assertion helper that wraps common checks
 * on Playwright APIResponse objects.
 *
 * WHY NOT USE TestNG Assert DIRECTLY IN TESTS?
 * Repeating the same assertion patterns in every test leads to:
 *   - Code duplication
 *   - Inconsistent log messages
 *   - Hard-to-maintain tests
 * By centralising these checks here, every test class gets the same
 * logging, the same error messages, and the same assertion logic.
 */
public class ResponseValidator {

    private static final Logger log = LoggerFactory.getLogger(ResponseValidator.class);

    // Jackson ObjectMapper is thread-safe and expensive to create, so we
    // keep a single instance as a class-level field.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Private constructor — static utility class, not meant to be instantiated.
    private ResponseValidator() {}

    // ── Status code validation ─────────────────────────────────────────────

    /**
     * Asserts that the response status code exactly matches the expected value.
     * Logs a clear message before asserting so the log file shows the check
     * even when the assertion passes.
     *
     * @param response       The Playwright APIResponse to check
     * @param expectedStatus The HTTP status code we expect (e.g. 200, 201, 404)
     */
    public static void assertStatusCode(APIResponse response, int expectedStatus) {
        int actual = response.status();
        log.info("Asserting status code: expected={}, actual={}", expectedStatus, actual);
        // TestNG Assert.assertEquals throws AssertionError on mismatch,
        // which TestNG catches and reports as a test failure.
        Assert.assertEquals(actual, expectedStatus,
                "HTTP status code mismatch. Expected " + expectedStatus + " but got " + actual);
    }

    // ── Content-Type validation ────────────────────────────────────────────

    /**
     * Asserts that the response Content-Type header contains the expected media type.
     * Uses "contains" rather than "equals" because the header often includes
     * a charset suffix (e.g. "application/json; charset=utf-8").
     *
     * @param response            The APIResponse to inspect
     * @param expectedContentType MIME type substring, e.g. "application/json"
     */
    public static void assertContentType(APIResponse response, String expectedContentType) {
        // headers() returns a map of lowercase header names → values.
        String actual = response.headers().getOrDefault("content-type", "");
        log.info("Asserting Content-Type: expected to contain '{}', actual='{}'",
                expectedContentType, actual);
        Assert.assertTrue(actual.contains(expectedContentType),
                "Content-Type mismatch. Expected to contain '" + expectedContentType
                + "' but got '" + actual + "'");
    }

    // ── Body / field validation ────────────────────────────────────────────

    /**
     * Parses the response body as a JSON object and asserts that a given
     * dot-path field equals the expected value.
     *
     * Examples:
     *   assertJsonField(response, "id",       "1")
     *   assertJsonField(response, "data/id",  "2")   // nested via JsonPointer
     *
     * @param response       APIResponse whose body is valid JSON
     * @param fieldPath      JSON Pointer path (use "/" as separator for nesting)
     * @param expectedValue  The expected string representation of the field value
     */
    public static void assertJsonField(APIResponse response,
                                       String fieldPath, String expectedValue) {
        try {
            // readTree() parses the JSON string into an immutable tree of JsonNode objects.
            JsonNode root = MAPPER.readTree(response.text());

            // JsonPointer uses "/" as the path separator for nested fields.
            // E.g. "/data/id" navigates to root → "data" → "id".
            String pointer = fieldPath.startsWith("/") ? fieldPath : "/" + fieldPath;
            JsonNode node = root.at(pointer);

            // at() returns a "MissingNode" (not null) when the path is absent,
            // so isMissingNode() is the right null-check.
            Assert.assertFalse(node.isMissingNode(),
                    "JSON field '" + fieldPath + "' does not exist in response body");

            String actual = node.asText(); // asText() converts any JSON scalar to String
            log.info("Asserting JSON field '{}': expected='{}', actual='{}'",
                    fieldPath, expectedValue, actual);

            Assert.assertEquals(actual, expectedValue,
                    "JSON field '" + fieldPath + "' value mismatch");

        } catch (IOException e) {
            // readTree() throws IOException when the body is not valid JSON
            Assert.fail("Failed to parse response body as JSON: " + e.getMessage());
        }
    }

    /**
     * Asserts that the response body contains the given substring.
     * Useful for quick checks when parsing the full JSON tree is overkill.
     *
     * @param response The APIResponse to inspect
     * @param text     Substring that must appear somewhere in the body
     */
    public static void assertBodyContains(APIResponse response, String text) {
        String body = response.text();
        log.info("Asserting body contains: '{}'", text);
        Assert.assertTrue(body.contains(text),
                "Response body does not contain '" + text + "'. Body: " + body);
    }

    /**
     * Asserts that the response body is NOT empty.
     */
    public static void assertBodyNotEmpty(APIResponse response) {
        String body = response.text();
        log.info("Asserting response body is not empty");
        Assert.assertNotNull(body, "Response body is null");
        Assert.assertFalse(body.isBlank(), "Response body is empty or whitespace");
    }

    // ── JSON Array validation ──────────────────────────────────────────────

    /**
     * Parses the response as a JSON array and asserts it contains at least
     * minSize elements.  Used in list/collection endpoint tests.
     *
     * @param response The APIResponse whose body is a JSON array
     * @param minSize  The minimum number of elements expected
     */
    public static void assertArrayMinSize(APIResponse response, int minSize) {
        try {
            JsonNode root = MAPPER.readTree(response.text());
            Assert.assertTrue(root.isArray(),
                    "Response body is not a JSON array. Body: " + response.text());
            int size = root.size();
            log.info("Asserting JSON array size >= {}, actual size = {}", minSize, size);
            Assert.assertTrue(size >= minSize,
                    "Expected JSON array to have at least " + minSize
                    + " elements but found " + size);
        } catch (IOException e) {
            Assert.fail("Failed to parse response body as JSON array: " + e.getMessage());
        }
    }

    // ── Response-time validation ───────────────────────────────────────────

    /**
     * Asserts that the measured response time is within the SLA defined in
     * config.properties (performance.max.response.time.ms).
     *
     * @param elapsedMs     Actual round-trip time in milliseconds
     * @param maxAllowedMs  Maximum allowed time in milliseconds
     */
    public static void assertResponseTime(long elapsedMs, long maxAllowedMs) {
        log.info("Asserting response time: {} ms <= {} ms", elapsedMs, maxAllowedMs);
        Assert.assertTrue(elapsedMs <= maxAllowedMs,
                "Response time " + elapsedMs + " ms exceeded SLA of " + maxAllowedMs + " ms");
    }

    // ── Header validation ──────────────────────────────────────────────────

    /**
     * Asserts that a specific response header is present (value may be anything).
     *
     * @param response   APIResponse to inspect
     * @param headerName Header name to look for (case-insensitive check)
     */
    public static void assertHeaderPresent(APIResponse response, String headerName) {
        // Playwright normalises header names to lowercase.
        boolean present = response.headers().containsKey(headerName.toLowerCase());
        log.info("Asserting header '{}' is present: {}", headerName, present);
        Assert.assertTrue(present,
                "Expected header '" + headerName + "' to be present in the response");
    }

    // ── JSON Schema validation ─────────────────────────────────────────────

    /**
     * Validates the response body against a JSON Schema file loaded from
     * the test classpath (src/test/resources/schemas/).
     *
     * The JSON Schema validator checks:
     *   - Required fields are present
     *   - Field types match (integer, string, etc.)
     *   - String formats (email, uri) are valid
     *   - No extra fields when additionalProperties=false
     *
     * @param response        APIResponse whose body is validated
     * @param schemaClasspath Classpath path to the schema, e.g. "schemas/post_schema.json"
     */
    public static void assertJsonSchema(APIResponse response, String schemaClasspath) {
        log.info("Validating response against JSON Schema: {}", schemaClasspath);
        try {
            // Load the schema file as a stream from the test classpath.
            InputStream schemaStream =
                    ResponseValidator.class.getClassLoader().getResourceAsStream(schemaClasspath);

            if (schemaStream == null) {
                Assert.fail("Schema file not found on classpath: " + schemaClasspath);
                return;
            }

            // JsonSchemaFactory builds a validator for the given draft version.
            // We use Draft-07 because it is the most widely supported and our
            // schema files declare "$schema": "http://json-schema.org/draft-07/schema#".
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            JsonSchema schema = factory.getSchema(schemaStream);

            // Parse the response body into a Jackson JsonNode tree.
            JsonNode responseNode = MAPPER.readTree(response.text());

            // validate() returns a Set of ValidationMessage, one per violation.
            // An empty set means the document is valid.
            Set<ValidationMessage> errors = schema.validate(responseNode);

            if (!errors.isEmpty()) {
                // Build a readable summary of all schema violations.
                StringBuilder sb = new StringBuilder("JSON Schema validation FAILED:\n");
                errors.forEach(e -> sb.append("  - ").append(e.getMessage()).append("\n"));
                log.error(sb.toString());
                Assert.fail(sb.toString());
            } else {
                log.info("JSON Schema validation PASSED");
            }

        } catch (IOException e) {
            Assert.fail("Error reading schema or response body: " + e.getMessage());
        }
    }
}
