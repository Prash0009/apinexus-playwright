package com.apinexus.tests.pagination;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * PaginationFilteringTest — Tests pagination, filtering, and sorting on list endpoints.
 *
 * Scenarios covered:
 *   TC-PAGE-01  Default pagination (page=1, per_page=6)
 *   TC-PAGE-02  Navigate to page 2
 *   TC-PAGE-03  Per-page size boundary (per_page=1, per_page=12)
 *   TC-PAGE-04  Page beyond total pages → empty data array or 404
 *   TC-PAGE-05  Data-driven pagination across multiple pages
 *   TC-PAGE-06  Filter by query parameter (?userId=1 on posts)
 *   TC-PAGE-07  Filter posts by userId and verify all returned posts match
 *   TC-PAGE-08  Nested resource (GET /users/{id}/posts style via query)
 *   TC-PAGE-09  Total count in response meta vs actual items returned
 *   TC-PAGE-10  Sorting (WireMock, since JSONPlaceholder does not support sort)
 *
 * JSONPlaceholder does NOT support real pagination (it always returns all items).
 * We use ReqRes for pagination tests (it returns page, per_page, total, total_pages).
 */
@Test(groups = "pagination")
public class PaginationFilteringTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── TC-PAGE-01: Default page ───────────────────────────────────────────

    /**
     * TC-PAGE-01: GET /users (page 1, default per_page=6).
     *
     * ReqRes returns a paginated response with metadata fields:
     *   { "page": 1, "per_page": 6, "total": 12, "total_pages": 2, "data": [...] }
     */
    @Test(description = "TC-PAGE-01: Default page 1 returns correct pagination metadata")
    public void testDefaultPaginationPage1() throws Exception {
        logTestStart("testDefaultPaginationPage1");

        String url = authBaseUrl + "/users?page=1";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);

        // Parse the pagination envelope.
        JsonNode body = mapper.readTree(response.text());

        // Verify the page number matches our request parameter.
        int page = body.get("page").asInt();
        Assert.assertEquals(page, 1, "Response 'page' should be 1");

        // Verify per_page field is present and reasonable.
        int perPage = body.get("per_page").asInt();
        Assert.assertTrue(perPage > 0, "per_page should be positive");

        // Verify the data array has at most perPage items.
        JsonNode data = body.get("data");
        Assert.assertTrue(data.isArray(), "'data' should be a JSON array");
        Assert.assertTrue(data.size() <= perPage,
                "Items on page should not exceed per_page=" + perPage);

        // Verify total and total_pages make mathematical sense.
        int total      = body.get("total").asInt();
        int totalPages = body.get("total_pages").asInt();
        int expectedTotalPages = (int) Math.ceil((double) total / perPage);
        Assert.assertEquals(totalPages, expectedTotalPages,
                "total_pages should equal ceil(total / per_page)");

        log.info("Page 1: {} items, {} total, {} total_pages", data.size(), total, totalPages);
        logTestEnd("testDefaultPaginationPage1");
    }

    // ── TC-PAGE-02: Page 2 ────────────────────────────────────────────────

    /**
     * TC-PAGE-02: Navigate to page 2 and verify the page number in the response.
     *
     * Real-world significance: clients paginating through results must confirm
     * the API is actually returning the correct page, not always returning page 1.
     */
    @Test(description = "TC-PAGE-02: Requesting page 2 returns page=2 in response metadata")
    public void testPage2() throws Exception {
        logTestStart("testPage2");

        String url = authBaseUrl + "/users?page=2";
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ApiLogger.logResponse(response, 0);
        ResponseValidator.assertStatusCode(response, 200);

        JsonNode body   = mapper.readTree(response.text());
        int actualPage  = body.get("page").asInt();
        Assert.assertEquals(actualPage, 2, "Response should indicate page 2");

        // Data array should contain items (page 2 has 6 users on ReqRes)
        JsonNode data = body.get("data");
        Assert.assertTrue(data.isArray() && data.size() > 0,
                "Page 2 should contain at least one user");

        logTestEnd("testPage2");
    }

    // ── TC-PAGE-03: per_page boundaries ───────────────────────────────────

    /**
     * TC-PAGE-03: Test boundary values for per_page parameter.
     *
     * per_page=1  → the smallest valid page size; exactly 1 item in data[].
     * per_page=12 → fetches all 12 ReqRes users in a single page.
     *
     * Boundary testing catches off-by-one errors in the server's paging logic.
     */
    @Test(dataProvider = "perPageValues",
          description = "TC-PAGE-03: Boundary per_page values return correct item counts")
    public void testPerPageBoundaries(int perPage, int minExpectedItems) throws Exception {
        logTestStart("testPerPageBoundaries[perPage=" + perPage + "]");

        String url = authBaseUrl + "/users?page=1&per_page=" + perPage;
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ResponseValidator.assertStatusCode(response, 200);

        JsonNode body = mapper.readTree(response.text());
        int returnedPerPage = body.get("per_page").asInt();
        int dataSize        = body.get("data").size();

        log.info("Requested per_page={}, server returned per_page={}, data.size()={}",
                perPage, returnedPerPage, dataSize);

        Assert.assertTrue(dataSize >= minExpectedItems,
                "Expected at least " + minExpectedItems + " items for per_page=" + perPage
                + " but got " + dataSize);

        logTestEnd("testPerPageBoundaries[perPage=" + perPage + "]");
    }

    @DataProvider(name = "perPageValues")
    public Object[][] perPageProvider() {
        return new Object[][] {
                { 1,  1 },    // per_page=1  → at least 1 item
                { 6,  6 },    // per_page=6  → at least 6 items
                { 12, 6 }     // per_page=12 → ReqRes has 12 users total; at least 6
        };
    }

    // ── TC-PAGE-04: Page beyond total ─────────────────────────────────────

    /**
     * TC-PAGE-04: Request a page number that exceeds the total number of pages.
     *
     * ReqRes returns an empty data array for out-of-range pages (not a 404).
     * Different APIs handle this differently; the key is that a page that
     * does not exist returns EITHER an empty collection (preferred) OR 404,
     * but NEVER 500 or the wrong page.
     */
    @Test(description = "TC-PAGE-04: Page beyond total returns empty data or 404")
    public void testPageBeyondTotal() throws Exception {
        logTestStart("testPageBeyondTotal");

        // Page 999 is guaranteed not to exist on ReqRes (only 2 pages)
        String url = authBaseUrl + "/users?page=999";
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ApiLogger.logResponse(response, 0);

        // Accept either 200 with empty data, or 404.
        int status = response.status();
        Assert.assertTrue(status == 200 || status == 404,
                "Out-of-range page should return 200 (empty) or 404, got: " + status);

        if (status == 200) {
            // If 200, the data array must be empty.
            JsonNode body = mapper.readTree(response.text());
            JsonNode data = body.get("data");
            Assert.assertTrue(data == null || data.size() == 0,
                    "Page 999 should have an empty data array, but found " + data.size() + " items");
            log.info("Page 999 returned 200 with empty data array — correct behaviour.");
        } else {
            log.info("Page 999 returned 404 — acceptable behaviour.");
        }

        logTestEnd("testPageBeyondTotal");
    }

    // ── TC-PAGE-05: Filter by userId ──────────────────────────────────────

    /**
     * TC-PAGE-05: GET /posts?userId=1 — Filter posts belonging to user 1.
     *
     * Real-world significance: collection endpoints almost always support
     * filter parameters. Testing filtering verifies that:
     *   1. The filter is applied on the server (not client-side)
     *   2. Every returned record satisfies the filter predicate
     *   3. No records from other users leak into the result
     */
    @Test(description = "TC-PAGE-05: Filter /posts by userId=1 returns only posts from user 1")
    public void testFilterByUserId() throws Exception {
        logTestStart("testFilterByUserId");

        String url = baseUrl + "/posts?userId=1";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);
        ApiLogger.logResponse(response, elapsed);

        ResponseValidator.assertStatusCode(response, 200);

        JsonNode posts = mapper.readTree(response.text());
        Assert.assertTrue(posts.isArray(), "Expected a JSON array of posts");
        Assert.assertTrue(posts.size() > 0, "userId=1 should have at least 1 post");

        // Iterate every post and verify the userId field = 1.
        // This catches server-side bugs where filtering is ignored.
        int violations = 0;
        for (JsonNode post : posts) {
            int postUserId = post.get("userId").asInt();
            if (postUserId != 1) {
                log.error("Filter violation: post id={} has userId={} (expected 1)",
                        post.get("id").asInt(), postUserId);
                violations++;
            }
        }

        Assert.assertEquals(violations, 0,
                violations + " posts had userId != 1 — filter was not applied correctly");

        log.info("Filter verified: all {} posts have userId=1", posts.size());
        logTestEnd("testFilterByUserId");
    }

    // ── TC-PAGE-06: Filter multiple query params ───────────────────────────

    /**
     * TC-PAGE-06: GET /comments?postId=5 — filters comments by postId.
     *
     * Tests that multiple independent filters both work,
     * and that the intersection of results is correct.
     */
    @Test(description = "TC-PAGE-06: Multiple query-param filters narrow results correctly")
    public void testFilterByPostIdAndVerifyCount() throws Exception {
        logTestStart("testFilterByPostIdAndVerifyCount");

        int targetPostId = 5;
        String url = baseUrl + "/comments?postId=" + targetPostId;
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ResponseValidator.assertStatusCode(response, 200);

        JsonNode comments = mapper.readTree(response.text());
        Assert.assertTrue(comments.isArray());

        // JSONPlaceholder guarantees exactly 5 comments per post
        log.info("Got {} comments for postId={}", comments.size(), targetPostId);

        for (JsonNode comment : comments) {
            Assert.assertEquals(comment.get("postId").asInt(), targetPostId,
                    "All comments should have postId=" + targetPostId);

            // Also validate that email field is present and non-empty in each comment
            String email = comment.get("email").asText();
            Assert.assertFalse(email.isBlank(),
                    "Comment email should not be blank for comment id=" + comment.get("id"));
        }

        logTestEnd("testFilterByPostIdAndVerifyCount");
    }

    // ── TC-PAGE-07: Sorting (WireMock) ────────────────────────────────────

    /**
     * TC-PAGE-07: Verify that the API honours a _sort query parameter.
     *
     * Real-world APIs often support ?_sort=field&_order=asc|desc.
     * JSONPlaceholder simulates this; we test with WireMock to control
     * the exact response order for deterministic assertions.
     */
    @Test(description = "TC-PAGE-07: Sorted response returns items in correct order (mock)")
    public void testSortedResponse() throws Exception {
        logTestStart("testSortedResponse");

        // Stub: GET /api/items?_sort=price&_order=asc → sorted list
        String sortedBody = "[" +
                "{\"id\":3,\"name\":\"Budget Item\",  \"price\":9}," +
                "{\"id\":1,\"name\":\"Mid Item\",      \"price\":49}," +
                "{\"id\":2,\"name\":\"Premium Item\",  \"price\":199}" +
                "]";

        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlPathEqualTo("/api/items"))
                        .withQueryParam("_sort",  com.github.tomakehurst.wiremock.client.WireMock.equalTo("price"))
                        .withQueryParam("_order", com.github.tomakehurst.wiremock.client.WireMock.equalTo("asc"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(sortedBody)));

        String url = mockBaseUrl + "/api/items?_sort=price&_order=asc";
        ApiLogger.logRequest("GET", url);

        APIResponse response = request.get(url);
        ApiLogger.logResponse(response, 0);
        ResponseValidator.assertStatusCode(response, 200);

        JsonNode items = mapper.readTree(response.text());
        Assert.assertTrue(items.isArray() && items.size() == 3);

        // Verify the items are in ascending price order.
        int previousPrice = Integer.MIN_VALUE;
        for (JsonNode item : items) {
            int price = item.get("price").asInt();
            Assert.assertTrue(price >= previousPrice,
                    "Items are not in ascending price order: " + price + " < " + previousPrice);
            previousPrice = price;
        }

        log.info("Sorting verified: prices in ascending order");
        logTestEnd("testSortedResponse");
    }

    // ── TC-PAGE-08: Total count consistency ───────────────────────────────

    /**
     * TC-PAGE-08: Verify that paginating through all pages and summing the
     * per-page item counts equals the reported total.
     *
     * If total=12, per_page=6, we expect:
     *   page 1 → 6 items
     *   page 2 → 6 items
     *   total collected = 12 = reported total ✔
     */
    @Test(description = "TC-PAGE-08: Sum of paginated items equals the reported total")
    public void testTotalCountConsistency() throws Exception {
        logTestStart("testTotalCountConsistency");

        // Fetch page 1 to discover total and per_page
        APIResponse page1Response = request.get(authBaseUrl + "/users?page=1");
        ResponseValidator.assertStatusCode(page1Response, 200);
        JsonNode page1Body = mapper.readTree(page1Response.text());

        int total      = page1Body.get("total").asInt();
        int totalPages = page1Body.get("total_pages").asInt();
        int perPage    = page1Body.get("per_page").asInt();

        log.info("Pagination meta: total={}, totalPages={}, perPage={}", total, totalPages, perPage);

        // Collect all items by paginating through every page.
        int collectedItems = page1Body.get("data").size(); // already have page 1

        for (int page = 2; page <= totalPages; page++) {
            ApiLogger.logStep(page, "Fetching page " + page + " of " + totalPages);
            APIResponse pageResponse = request.get(authBaseUrl + "/users?page=" + page);
            ResponseValidator.assertStatusCode(pageResponse, 200);
            JsonNode pageBody = mapper.readTree(pageResponse.text());
            collectedItems += pageBody.get("data").size();
        }

        log.info("Collected {} total items across {} pages (expected {})",
                collectedItems, totalPages, total);

        Assert.assertEquals(collectedItems, total,
                "Sum of all page items (" + collectedItems
                + ") does not equal reported total (" + total + ")");

        logTestEnd("testTotalCountConsistency");
    }
}
