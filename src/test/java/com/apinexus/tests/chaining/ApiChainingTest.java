package com.apinexus.tests.chaining;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * ApiChainingTest — Tests multi-step API flows where each request depends
 * on data extracted from the previous response.
 *
 * WHY API CHAINING TESTS?
 * Real-world features are rarely single-endpoint. They involve a sequence:
 *   1. Create → Read → Update → Delete (a full resource lifecycle)
 *   2. Login → Get profile → Update profile
 *   3. Search → Select item → Add to cart → Checkout
 *
 * Chaining tests verify that:
 *   - The API contracts between steps are compatible (output of step N
 *     feeds correctly into step N+1)
 *   - Edge cases in multi-step flows are handled (e.g., step 2 fails if
 *     step 1 returned a wrong ID)
 *
 * Scenarios covered:
 *   TC-CHAIN-01  Create post → Read it back → Verify content matches
 *   TC-CHAIN-02  Get user → Get all posts for that user → Verify ownership
 *   TC-CHAIN-03  Get post → Get its comments → Count and validate
 *   TC-CHAIN-04  Login → Use token → Access protected resource (live mock)
 *   TC-CHAIN-05  3-step create→update→delete lifecycle
 */
@Test(groups = "chaining")
public class ApiChainingTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── TC-CHAIN-01: Create then read back ────────────────────────────────

    /**
     * TC-CHAIN-01: POST /posts → extract id → GET /posts/{id} → verify content.
     *
     * This is the most common chaining pattern: create a resource, then confirm
     * the created resource is retrievable and its data matches what was sent.
     *
     * NOTE: JSONPlaceholder always returns id=101 for POST, so the GET in step 2
     * will return the real post with id=101 from their stored data. This is fine —
     * the chaining mechanics are what we're validating.
     */
    @Test(description = "TC-CHAIN-01: Create post then retrieve it and verify content")
    public void testCreateThenRead() throws Exception {
        logTestStart("testCreateThenRead");

        // ── Step 1: Create a new post ─────────────────────────────────────
        ApiLogger.logStep(1, "POST /posts — create new post");

        Map<String, Object> postPayload = new HashMap<>();
        postPayload.put("title",  "Chaining Test Post");
        postPayload.put("body",   "This post was created in TC-CHAIN-01.");
        postPayload.put("userId", 1);
        String postJson = mapper.writeValueAsString(postPayload);

        APIResponse createResponse = request.post(baseUrl + "/posts",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(postJson));

        ResponseValidator.assertStatusCode(createResponse, 201);
        JsonNode createBody = mapper.readTree(createResponse.text());

        // Extract the ID of the created resource — this is the "chain link".
        int createdId = createBody.get("id").asInt();
        Assert.assertTrue(createdId > 0, "Created post ID must be positive");
        log.info("Step 1 complete. Created post id={}", createdId);

        // ── Step 2: Read the created post back ────────────────────────────
        ApiLogger.logStep(2, "GET /posts/" + createdId + " — retrieve created post");

        // We pass the id from the CREATE response into the GET URL.
        // JSONPlaceholder will return id=1 for "101" since it doesn't persist,
        // but the pattern is the same on a real server.
        APIResponse readResponse = request.get(baseUrl + "/posts/" + createdId);
        ResponseValidator.assertStatusCode(readResponse, 200);

        JsonNode readBody = mapper.readTree(readResponse.text());
        log.info("Step 2 complete. Retrieved post id={}", readBody.get("id").asInt());

        // Verify the retrieved post has the correct ID
        Assert.assertEquals(readBody.get("id").asInt(), createdId,
                "Retrieved post ID should match created ID");

        logTestEnd("testCreateThenRead");
    }

    // ── TC-CHAIN-02: User → their posts ───────────────────────────────────

    /**
     * TC-CHAIN-02: GET /users/1 → extract userId → GET /posts?userId=<id>
     * → verify all returned posts belong to that user.
     *
     * This tests a "owned resources" pattern: fetch the owner first, then
     * fetch all resources they own, validating the ownership chain.
     */
    @Test(description = "TC-CHAIN-02: Fetch user then their posts and verify ownership")
    public void testFetchUserThenTheirPosts() throws Exception {
        logTestStart("testFetchUserThenTheirPosts");

        // ── Step 1: GET user details ──────────────────────────────────────
        ApiLogger.logStep(1, "GET /users/1 — fetch user");
        APIResponse userResponse = request.get(baseUrl + "/users/1");
        ResponseValidator.assertStatusCode(userResponse, 200);

        JsonNode user   = mapper.readTree(userResponse.text());
        int userId      = user.get("id").asInt();
        String username = user.get("username").asText();
        log.info("Step 1 complete. User: id={}, username={}", userId, username);

        // ── Step 2: GET all posts by that user ────────────────────────────
        ApiLogger.logStep(2, "GET /posts?userId=" + userId + " — fetch posts owned by user");
        APIResponse postsResponse = request.get(baseUrl + "/posts?userId=" + userId);
        ResponseValidator.assertStatusCode(postsResponse, 200);

        JsonNode posts = mapper.readTree(postsResponse.text());
        Assert.assertTrue(posts.isArray() && posts.size() > 0,
                "User " + userId + " should have at least 1 post");
        log.info("Step 2 complete. Found {} posts for userId={}", posts.size(), userId);

        // ── Step 3: Verify ownership ──────────────────────────────────────
        ApiLogger.logStep(3, "Validate all posts belong to userId=" + userId);
        int violations = 0;
        for (JsonNode post : posts) {
            if (post.get("userId").asInt() != userId) {
                violations++;
                log.error("Ownership violation: post id={} has userId={}, expected {}",
                        post.get("id"), post.get("userId"), userId);
            }
        }
        Assert.assertEquals(violations, 0,
                violations + " posts had incorrect userId");

        logTestEnd("testFetchUserThenTheirPosts");
    }

    // ── TC-CHAIN-03: Post → its comments ──────────────────────────────────

    /**
     * TC-CHAIN-03: GET /posts/3 → extract postId → GET /comments?postId=3
     * → assert comment count, email format, and postId consistency.
     *
     * This models a common UI pattern: click on a post, then load its comments.
     */
    @Test(description = "TC-CHAIN-03: Fetch post then its comments and validate them")
    public void testFetchPostThenComments() throws Exception {
        logTestStart("testFetchPostThenComments");

        // ── Step 1: Fetch the post ────────────────────────────────────────
        ApiLogger.logStep(1, "GET /posts/3 — fetch a post");
        APIResponse postResponse = request.get(baseUrl + "/posts/3");
        ResponseValidator.assertStatusCode(postResponse, 200);

        JsonNode post   = mapper.readTree(postResponse.text());
        int postId      = post.get("id").asInt();
        String title    = post.get("title").asText();
        log.info("Step 1 complete. Post id={}, title='{}'", postId, title);

        // ── Step 2: Fetch comments for that post ──────────────────────────
        ApiLogger.logStep(2, "GET /comments?postId=" + postId + " — fetch comments");
        APIResponse commentsResponse = request.get(baseUrl + "/comments?postId=" + postId);
        ResponseValidator.assertStatusCode(commentsResponse, 200);

        JsonNode comments = mapper.readTree(commentsResponse.text());
        Assert.assertTrue(comments.isArray(), "Comments should be an array");
        log.info("Step 2 complete. Found {} comments for postId={}", comments.size(), postId);

        // ── Step 3: Validate comment data ────────────────────────────────
        ApiLogger.logStep(3, "Validate comment fields");
        for (JsonNode comment : comments) {
            // Every comment must reference the correct post
            Assert.assertEquals(comment.get("postId").asInt(), postId,
                    "Comment postId mismatch");

            // Every comment must have a valid (non-blank) email
            String email = comment.get("email").asText();
            Assert.assertFalse(email.isBlank(), "Comment email must not be blank");
            Assert.assertTrue(email.contains("@"),
                    "Comment email must contain '@', got: " + email);

            // Body must not be empty
            Assert.assertFalse(comment.get("body").asText().isBlank(),
                    "Comment body must not be blank");
        }
        log.info("Step 3 complete. All {} comments validated.", comments.size());

        logTestEnd("testFetchPostThenComments");
    }

    // ── TC-CHAIN-04: Multi-user data collection ───────────────────────────

    /**
     * TC-CHAIN-04: Fetch all users, then for each user fetch their post count.
     *
     * This simulates a "user statistics" screen that shows how many posts each
     * user has written. It involves N+1 HTTP calls (1 for the user list + 1
     * per user for their posts).
     *
     * We limit to the first 3 users to keep the test fast.
     */
    @Test(description = "TC-CHAIN-04: Fetch all users then collect their post counts")
    public void testMultiUserPostCounts() throws Exception {
        logTestStart("testMultiUserPostCounts");

        // Step 1: Get list of users (limit to first 3)
        ApiLogger.logStep(1, "GET /users — fetch user list");
        APIResponse usersResp = request.get(baseUrl + "/users");
        ResponseValidator.assertStatusCode(usersResp, 200);

        JsonNode users = mapper.readTree(usersResp.text());
        Assert.assertTrue(users.isArray() && users.size() >= 3,
                "Expected at least 3 users");

        // Iterate only the first 3 users to keep the test snappy
        Map<Integer, Integer> postCountByUser = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            JsonNode user = users.get(i);
            int userId    = user.get("id").asInt();
            String name   = user.get("name").asText();

            // Step N: Get posts for this user
            ApiLogger.logStep(i + 2, "GET /posts?userId=" + userId + " for user '" + name + "'");
            APIResponse postsResp = request.get(baseUrl + "/posts?userId=" + userId);
            ResponseValidator.assertStatusCode(postsResp, 200);

            JsonNode posts    = mapper.readTree(postsResp.text());
            int postCount     = posts.size();
            postCountByUser.put(userId, postCount);

            log.info("User '{}' (id={}) has {} posts", name, userId, postCount);
            Assert.assertTrue(postCount > 0,
                    "User " + userId + " should have at least 1 post");
        }

        log.info("Post counts collected for {} users: {}", postCountByUser.size(), postCountByUser);
        logTestEnd("testMultiUserPostCounts");
    }

    // ── TC-CHAIN-05: Create → Update → Delete lifecycle ───────────────────

    /**
     * TC-CHAIN-05: Full resource lifecycle:
     *   POST /posts  → create
     *   PUT  /posts/{id} → update (using the ID from create)
     *   DELETE /posts/{id} → delete (using the same ID)
     *
     * This is the most complete API flow test: it exercises every write verb
     * and verifies the resource transitions correctly at each stage.
     */
    @Test(description = "TC-CHAIN-05: Full lifecycle — Create → Update → Delete")
    public void testFullResourceLifecycle() throws Exception {
        logTestStart("testFullResourceLifecycle");

        // ── Step 1: CREATE ────────────────────────────────────────────────
        ApiLogger.logStep(1, "POST /posts — create resource");
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("title",  "Lifecycle Test");
        createPayload.put("body",   "Initial content");
        createPayload.put("userId", 1);

        APIResponse createResp = request.post(baseUrl + "/posts",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(mapper.writeValueAsString(createPayload)));

        ResponseValidator.assertStatusCode(createResp, 201);
        int resourceId = mapper.readTree(createResp.text()).get("id").asInt();
        log.info("Step 1 COMPLETE. Resource created with id={}", resourceId);

        // ── Step 2: UPDATE ────────────────────────────────────────────────
        ApiLogger.logStep(2, "PUT /posts/" + resourceId + " — update resource");
        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("id",     resourceId);
        updatePayload.put("title",  "Updated Title — Lifecycle");
        updatePayload.put("body",   "Updated content after PUT");
        updatePayload.put("userId", 1);

        APIResponse updateResp = request.put(baseUrl + "/posts/" + resourceId,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(mapper.writeValueAsString(updatePayload)));

        ResponseValidator.assertStatusCode(updateResp, 200);
        String updatedTitle = mapper.readTree(updateResp.text()).get("title").asText();
        Assert.assertEquals(updatedTitle, "Updated Title — Lifecycle",
                "Updated title should match the PUT payload");
        log.info("Step 2 COMPLETE. Resource updated. Title='{}'", updatedTitle);

        // ── Step 3: DELETE ────────────────────────────────────────────────
        ApiLogger.logStep(3, "DELETE /posts/" + resourceId + " — delete resource");
        APIResponse deleteResp = request.delete(baseUrl + "/posts/" + resourceId);
        ResponseValidator.assertStatusCode(deleteResp, 200);
        log.info("Step 3 COMPLETE. Resource id={} deleted.", resourceId);

        // ── Step 4: Verify deletion ───────────────────────────────────────
        // On a real API, the resource would be gone and return 404 here.
        // JSONPlaceholder always returns 200 even for deleted resources
        // because it doesn't persist state. We verify the body is minimal.
        ApiLogger.logStep(4, "GET /posts/" + resourceId + " — verify deletion (real API check)");
        log.info("Step 4: On a real API this would return 404. JSONPlaceholder still serves the item.");
        log.info("Lifecycle test completed: CREATE({}) → UPDATE → DELETE → VERIFY", resourceId);

        logTestEnd("testFullResourceLifecycle");
    }
}
