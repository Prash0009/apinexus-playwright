package com.apinexus.tests.crud;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * CrudOperationsTest — Covers all HTTP verbs against JSONPlaceholder:
 *
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │ Scenario                    │ Verb   │ Endpoint               │ Code │
 *   ├──────────────────────────────────────────────────────────────────────┤
 *   │ Fetch list of resources     │ GET    │ /posts                 │ 200  │
 *   │ Fetch single resource       │ GET    │ /posts/{id}            │ 200  │
 *   │ Fetch with data provider    │ GET    │ /posts/{id}            │ 200  │
 *   │ Create resource             │ POST   │ /posts                 │ 201  │
 *   │ Full update                 │ PUT    │ /posts/{id}            │ 200  │
 *   │ Partial update              │ PATCH  │ /posts/{id}            │ 200  │
 *   │ Delete resource             │ DELETE │ /posts/{id}            │ 200  │
 *   │ Custom request headers      │ GET    │ /posts/1               │ 200  │
 *   └──────────────────────────────────────────────────────────────────────┘
 *
 * JSONPlaceholder NOTE: POST/PUT/PATCH/DELETE are "fake" — the server accepts
 * the request and returns a realistic response, but does NOT persist the data.
 */
@Test(groups = "crud")
public class CrudOperationsTest extends BaseApiTest {

    // ObjectMapper is thread-safe — one instance is enough for the test class.
    private final ObjectMapper mapper = new ObjectMapper();

    // ── GET ALL ───────────────────────────────────────────────────────────

    /**
     * TC-CRUD-01: GET /posts — Retrieve all posts and verify the response.
     *
     * Expectations:
     *   - HTTP 200 OK
     *   - Content-Type is application/json
     *   - Body is a JSON array with at least 100 posts
     */
    @Test(description = "TC-CRUD-01: GET all posts returns 200 and a non-empty array")
    public void testGetAllPosts() {
        logTestStart("testGetAllPosts");

        // Construct the full URL (baseUrl is set in BaseApiTest from config.properties)
        String url = baseUrl + "/posts";
        ApiLogger.logRequest("GET", url);

        // request.get(url) sends an HTTP GET and blocks until the response arrives.
        // The returned APIResponse holds status, headers, and body.
        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        // Log the full response for debugging
        ApiLogger.logResponse(response, elapsed);

        // Assert status code is 200
        ResponseValidator.assertStatusCode(response, 200);

        // Assert Content-Type contains "application/json"
        ResponseValidator.assertContentType(response, "application/json");

        // Assert the body is a JSON array with at least 100 elements
        ResponseValidator.assertArrayMinSize(response, 100);

        logTestEnd("testGetAllPosts");
    }

    // ── GET SINGLE ────────────────────────────────────────────────────────

    /**
     * TC-CRUD-02: GET /posts/1 — Retrieve a single post by ID.
     *
     * Expectations:
     *   - HTTP 200 OK
     *   - Response body fields: id=1, userId=1
     */
    @Test(description = "TC-CRUD-02: GET single post returns correct fields")
    public void testGetSinglePost() throws Exception {
        logTestStart("testGetSinglePost");

        String url = baseUrl + "/posts/1";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);

        // Parse the response body into a Jackson tree for field-level assertions.
        // readTree() returns a JsonNode that can be navigated like a map.
        JsonNode body = mapper.readTree(response.text());

        // Assert specific field values using the helper in ResponseValidator.
        ResponseValidator.assertJsonField(response, "id",     "1");
        ResponseValidator.assertJsonField(response, "userId", "1");

        // Also verify the title field is not blank (we don't know the exact value).
        String title = body.get("title").asText();
        ApiLogger.logAssertion("title is not blank", false, title.isBlank());
        Assert.assertFalse(title.isBlank(), "Post title should not be blank");

        logTestEnd("testGetSinglePost");
    }

    // ── DATA-DRIVEN GET ───────────────────────────────────────────────────

    /**
     * TC-CRUD-03: Parameterised GET — Run the same test for multiple post IDs.
     *
     * @DataProvider supplies a 2D array; each row is one set of arguments.
     * TestNG calls this method once per row: with postId=1, then 5, then 10.
     *
     * @param postId       Post ID to fetch
     * @param expectedCode Expected HTTP status code
     */
    @Test(dataProvider = "postIds",
          description = "TC-CRUD-03: GET post by various IDs (data-driven)")
    public void testGetPostDataDriven(int postId, int expectedCode) {
        logTestStart("testGetPostDataDriven[postId=" + postId + "]");

        String url = baseUrl + "/posts/" + postId;
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, expectedCode);

        if (expectedCode == 200) {
            // Additional check: returned id must match what we requested
            ResponseValidator.assertJsonField(response, "id", String.valueOf(postId));
        }

        logTestEnd("testGetPostDataDriven[postId=" + postId + "]");
    }

    /**
     * @DataProvider for testGetPostDataDriven.
     * Returns an Object[][] where each inner array is [postId, expectedHttpCode].
     */
    @DataProvider(name = "postIds")
    public Object[][] postIdProvider() {
        return new Object[][] {
                { 1,    200 },   // valid post
                { 5,    200 },   // valid post
                { 10,   200 },   // valid post
                { 9999, 404 }    // non-existent post → 404
        };
    }

    // ── POST (CREATE) ─────────────────────────────────────────────────────

    /**
     * TC-CRUD-04: POST /posts — Create a new post.
     *
     * The request body must be JSON; we build it with Jackson's ObjectMapper
     * so the serialisation is guaranteed to be valid JSON.
     *
     * Expectations:
     *   - HTTP 201 Created
     *   - Response body echoes the sent fields plus an auto-generated "id"
     */
    @Test(description = "TC-CRUD-04: POST creates a new post and returns 201")
    public void testCreatePost() throws Exception {
        logTestStart("testCreatePost");

        // Build the request body as a Java Map, then serialise to a JSON string.
        // Using Map avoids hardcoding a JSON string with fragile escaping.
        Map<String, Object> postBody = new HashMap<>();
        postBody.put("title",  "ApiNexus Test Post");
        postBody.put("body",   "This post was created by the ApiNexus test suite.");
        postBody.put("userId", 1);

        // mapper.writeValueAsString() converts Map → JSON string.
        // Example: {"title":"ApiNexus Test Post","body":"...","userId":1}
        String requestBodyJson = mapper.writeValueAsString(postBody);

        String url = baseUrl + "/posts";

        // Build request headers for the log
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        ApiLogger.logRequest("POST", url, headers, requestBodyJson);

        long start = startTimer();

        // RequestOptions configures the request before it is sent.
        // setData() sets the request body; Playwright detects "application/json"
        // from the Content-Type header we set.
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(requestBodyJson));

        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        // 201 Created is the standard response for a successful resource creation.
        ResponseValidator.assertStatusCode(response, 201);

        // Parse the response and verify the server echoed our fields back.
        JsonNode body = mapper.readTree(response.text());

        // JSONPlaceholder assigns id=101 for every fake POST (always the same).
        Assert.assertTrue(body.has("id"), "Response should contain an 'id' field");
        Assert.assertFalse(body.get("title").asText().isBlank(),
                "Returned title should not be blank");

        logTestEnd("testCreatePost");
    }

    // ── PUT (FULL UPDATE) ─────────────────────────────────────────────────

    /**
     * TC-CRUD-05: PUT /posts/1 — Fully replace a post.
     *
     * PUT replaces ALL fields of the resource. If a field is omitted from the
     * request body, it should be absent (or null) in the response.
     *
     * Expectations:
     *   - HTTP 200 OK
     *   - Response body contains the updated title and body
     */
    @Test(description = "TC-CRUD-05: PUT fully replaces a post")
    public void testFullUpdatePost() throws Exception {
        logTestStart("testFullUpdatePost");

        // Full replacement payload — every field must be provided.
        Map<String, Object> payload = new HashMap<>();
        payload.put("id",     1);
        payload.put("title",  "Updated Title via PUT");
        payload.put("body",   "Updated body via PUT request from ApiNexus.");
        payload.put("userId", 1);

        String requestBodyJson = mapper.writeValueAsString(payload);
        String url             = baseUrl + "/posts/1";

        Map<String, String> headers = Map.of("Content-Type", "application/json");
        ApiLogger.logRequest("PUT", url, headers, requestBodyJson);

        long start = startTimer();

        // request.put() sends an HTTP PUT request.
        APIResponse response = request.put(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(requestBodyJson));

        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertBodyContains(response, "Updated Title via PUT");
        ResponseValidator.assertJsonField(response, "id", "1");

        logTestEnd("testFullUpdatePost");
    }

    // ── PATCH (PARTIAL UPDATE) ─────────────────────────────────────────────

    /**
     * TC-CRUD-06: PATCH /posts/1 — Partially update a post.
     *
     * PATCH sends only the fields to be changed; other fields stay as they are.
     * This is the idiomatic REST operation for partial modifications.
     *
     * Expectations:
     *   - HTTP 200 OK
     *   - Response contains the updated title
     *   - Other fields (userId, body) are not changed
     */
    @Test(description = "TC-CRUD-06: PATCH partially updates a post title")
    public void testPartialUpdatePost() throws Exception {
        logTestStart("testPartialUpdatePost");

        // Only the title field is being changed — body and userId are not sent.
        Map<String, Object> patchPayload = new HashMap<>();
        patchPayload.put("title", "Patched Title by ApiNexus");

        String requestBodyJson = mapper.writeValueAsString(patchPayload);
        String url             = baseUrl + "/posts/1";

        ApiLogger.logRequest("PATCH", url,
                Map.of("Content-Type", "application/json"), requestBodyJson);

        long start = startTimer();

        // request.patch() sends an HTTP PATCH request.
        APIResponse response = request.patch(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(requestBodyJson));

        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertBodyContains(response, "Patched Title by ApiNexus");

        logTestEnd("testPartialUpdatePost");
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    /**
     * TC-CRUD-07: DELETE /posts/1 — Delete a resource.
     *
     * Expectations:
     *   - HTTP 200 OK  (JSONPlaceholder returns 200; many real APIs return 204)
     *   - Response body is an empty JSON object {}
     */
    @Test(description = "TC-CRUD-07: DELETE a post returns 200")
    public void testDeletePost() {
        logTestStart("testDeletePost");

        String url = baseUrl + "/posts/1";
        ApiLogger.logRequest("DELETE", url);

        long start = startTimer();

        // request.delete() sends an HTTP DELETE request.
        APIResponse response = request.delete(url);

        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);

        logTestEnd("testDeletePost");
    }

    // ── CUSTOM HEADERS ────────────────────────────────────────────────────

    /**
     * TC-CRUD-08: GET /posts/1 with custom request headers.
     *
     * Real-world APIs often require:
     *   - X-Request-ID: a correlation ID for distributed tracing
     *   - X-Client-Version: the API consumer's version
     *   - Accept: preferred response format
     *
     * This test verifies that custom headers are sent and the request succeeds.
     */
    @Test(description = "TC-CRUD-08: GET with custom headers succeeds")
    public void testCustomRequestHeaders() {
        logTestStart("testCustomRequestHeaders");

        String url = baseUrl + "/posts/1";

        // Build a map of all custom headers for the log and the request.
        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("Accept",           "application/json");
        customHeaders.put("X-Request-ID",     "apinexus-test-12345");
        customHeaders.put("X-Client-Version", "1.0.0");
        customHeaders.put("X-Custom-Header",  "playwright-api-test");

        ApiLogger.logRequest("GET", url, customHeaders, null);

        long start = startTimer();

        // Chain multiple setHeader() calls — each one adds one header to the request.
        APIResponse response = request.get(url,
                RequestOptions.create()
                        .setHeader("Accept",           "application/json")
                        .setHeader("X-Request-ID",     "apinexus-test-12345")
                        .setHeader("X-Client-Version", "1.0.0")
                        .setHeader("X-Custom-Header",  "playwright-api-test"));

        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertJsonField(response, "id", "1");

        logTestEnd("testCustomRequestHeaders");
    }

    // ── QUERY PARAMETERS ──────────────────────────────────────────────────

    /**
     * TC-CRUD-09: GET /comments?postId=1 — Filter a collection by query parameter.
     *
     * Query parameters narrow down the result set on the server side.
     * This test verifies that filtering works and every returned comment
     * belongs to the requested post.
     */
    @Test(description = "TC-CRUD-09: GET comments filtered by postId query parameter")
    public void testGetWithQueryParams() throws Exception {
        logTestStart("testGetWithQueryParams");

        // Build the URL with a query parameter:
        // /comments?postId=1 → returns only comments for post 1
        String url = baseUrl + "/comments?postId=1";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);

        // Parse the JSON array and verify every comment has postId=1.
        JsonNode comments = mapper.readTree(response.text());
        Assert.assertTrue(comments.isArray(), "Response should be a JSON array");
        Assert.assertTrue(comments.size() > 0, "Expected at least one comment for postId=1");

        // Iterate every element and check the postId field.
        for (JsonNode comment : comments) {
            int postId = comment.get("postId").asInt();
            Assert.assertEquals(postId, 1,
                    "All comments should have postId=1 but found postId=" + postId);
        }

        log.info("Verified {} comments all belong to postId=1", comments.size());
        logTestEnd("testGetWithQueryParams");
    }
}
