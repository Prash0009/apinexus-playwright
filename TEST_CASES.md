# ApiNexus — Test Case Specification

This document lists **every test case** in the ApiNexus suite with explicit
step-by-step execution details: preconditions, steps, test data, and expected
results. It complements the inline JavaDoc comments in the source code and is
intended as a standalone reference for QA review, manual verification, or
onboarding a new team member who has not read the Java code.

**Total test cases: 61** (including data-driven variants) across **9 suites**.

> Naming convention: `TC-<SUITE>-<NN>` matches the `@Test(description = "...")`
> string on the corresponding method, so you can search the source code for
> the exact ID to jump straight to the implementation.

---

## Table of Contents

1. [CRUD Operations (TC-CRUD)](#1-crud-operations-tc-crud) — 12 cases
2. [Authentication (TC-AUTH)](#2-authentication-tc-auth) — 8 cases
3. [Schema Validation (TC-SCHEMA)](#3-schema-validation-tc-schema) — 5 cases
4. [Error Handling (TC-ERR)](#4-error-handling-tc-err) — 8 cases
5. [Pagination & Filtering (TC-PAGE)](#5-pagination--filtering-tc-page) — 10 cases
6. [Mock Server (TC-MOCK)](#6-mock-server-tc-mock) — 8 cases
7. [Performance (TC-PERF)](#7-performance-tc-perf) — 5 cases
8. [API Chaining (TC-CHAIN)](#8-api-chaining-tc-chain) — 5 cases
9. [File Upload (TC-UPLOAD)](#9-file-upload-tc-upload) — 5 cases

---

## 1. CRUD Operations (TC-CRUD)

**Class:** `com.apinexus.tests.crud.CrudOperationsTest` | **Group:** `crud` | **Target:** `https://jsonplaceholder.typicode.com`

### TC-CRUD-01 — GET all posts returns 200 and a non-empty array

| Field | Detail |
|---|---|
| **Precondition** | Suite-level `APIRequestContext` is initialised with `baseUrl` |
| **Test Steps** | 1. Send `GET /posts`<br>2. Record response status, headers, and body<br>3. Assert status code equals 200<br>4. Assert `Content-Type` header contains `application/json`<br>5. Parse body as JSON array; assert array size ≥ 100 |
| **Test Data** | None (no request body / params) |
| **Expected Result** | HTTP 200; JSON array with at least 100 post objects |

### TC-CRUD-02 — GET single post returns correct fields

| Field | Detail |
|---|---|
| **Precondition** | Post with `id=1` exists on JSONPlaceholder |
| **Test Steps** | 1. Send `GET /posts/1`<br>2. Assert status code equals 200<br>3. Parse response body into a JSON tree<br>4. Assert `id` field equals `"1"`<br>5. Assert `userId` field equals `"1"`<br>6. Assert `title` field is not blank |
| **Test Data** | Post ID = 1 |
| **Expected Result** | HTTP 200; body contains `id=1`, `userId=1`, non-empty `title` |

### TC-CRUD-03 — GET post by various IDs (data-driven)

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | For each `(postId, expectedCode)` row:<br>1. Send `GET /posts/{postId}`<br>2. Assert status code equals `expectedCode`<br>3. If `expectedCode == 200`, also assert the returned `id` field equals `postId` |
| **Test Data** | Row 1: `postId=1, expectedCode=200`<br>Row 2: `postId=5, expectedCode=200`<br>Row 3: `postId=10, expectedCode=200`<br>Row 4: `postId=9999, expectedCode=404` |
| **Expected Result** | Valid IDs (1, 5, 10) return 200 with matching `id`; ID 9999 returns 404 |

### TC-CRUD-04 — POST creates a new post and returns 201

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Build a JSON request body with `title`, `body`, `userId`<br>2. Send `POST /posts` with `Content-Type: application/json`<br>3. Assert status code equals 201<br>4. Parse response body; assert it has an `id` field<br>5. Assert the returned `title` field is not blank |
| **Test Data** | `title="ApiNexus Test Post"`, `body="This post was created by the ApiNexus test suite."`, `userId=1` |
| **Expected Result** | HTTP 201; response echoes the submitted fields and includes a generated `id` |

### TC-CRUD-05 — PUT fully replaces a post

| Field | Detail |
|---|---|
| **Precondition** | Post `id=1` exists |
| **Test Steps** | 1. Build a full replacement JSON body containing `id`, `title`, `body`, `userId`<br>2. Send `PUT /posts/1`<br>3. Assert status code equals 200<br>4. Assert response body contains the new title text<br>5. Assert `id` field still equals `"1"` |
| **Test Data** | `id=1`, `title="Updated Title via PUT"`, `body="Updated body via PUT request from ApiNexus."`, `userId=1` |
| **Expected Result** | HTTP 200; all fields fully replaced in the response |

### TC-CRUD-06 — PATCH partially updates a post title

| Field | Detail |
|---|---|
| **Precondition** | Post `id=1` exists |
| **Test Steps** | 1. Build a partial JSON body containing only `title`<br>2. Send `PATCH /posts/1`<br>3. Assert status code equals 200<br>4. Assert response body contains the new title text |
| **Test Data** | `title="Patched Title by ApiNexus"` (no `body`/`userId` sent) |
| **Expected Result** | HTTP 200; only the `title` field changes, other fields untouched |

### TC-CRUD-07 — DELETE a post returns 200

| Field | Detail |
|---|---|
| **Precondition** | Post `id=1` exists |
| **Test Steps** | 1. Send `DELETE /posts/1`<br>2. Assert status code equals 200 |
| **Test Data** | Post ID = 1 |
| **Expected Result** | HTTP 200 |

### TC-CRUD-08 — GET with custom headers succeeds

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Build custom headers: `Accept`, `X-Request-ID`, `X-Client-Version`, `X-Custom-Header`<br>2. Send `GET /posts/1` with all four headers attached<br>3. Assert status code equals 200<br>4. Assert `id` field equals `"1"` |
| **Test Data** | `Accept=application/json`, `X-Request-ID=apinexus-test-12345`, `X-Client-Version=1.0.0`, `X-Custom-Header=playwright-api-test` |
| **Expected Result** | HTTP 200; server accepts and ignores the custom headers without error |

### TC-CRUD-09 — GET comments filtered by postId query parameter

| Field | Detail |
|---|---|
| **Precondition** | Post `id=1` has at least one comment |
| **Test Steps** | 1. Send `GET /comments?postId=1`<br>2. Assert status code equals 200<br>3. Parse body as JSON array<br>4. Iterate every comment and assert its `postId` field equals `1` |
| **Test Data** | Query parameter `postId=1` |
| **Expected Result** | HTTP 200; every returned comment has `postId == 1` (no leakage from other posts) |

---

## 2. Authentication (TC-AUTH)

**Class:** `com.apinexus.tests.auth.AuthenticationTest` | **Group:** `auth` | **Target:** `https://reqres.in/api` (live) + WireMock (mock)

### TC-AUTH-01 — Valid login credentials return a Bearer token

| Field | Detail |
|---|---|
| **Precondition** | ReqRes demo account `eve.holt@reqres.in` / `cityslicka` is active |
| **Test Steps** | 1. Build JSON body with `email` and `password`<br>2. Send `POST /login`<br>3. Assert status code equals 200<br>4. Assert `Content-Type` contains `application/json`<br>5. Parse body; assert a non-blank `token` field is present |
| **Test Data** | `email=eve.holt@reqres.in`, `password=cityslicka` |
| **Expected Result** | HTTP 200; JSON body contains a non-empty `token` |

### TC-AUTH-02 — Invalid credentials return 400 and an error body

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Build JSON body with an invalid `email`/`password` pair<br>2. Send `POST /login`<br>3. Assert status code equals 400<br>4. Assert response body contains the word `error` |
| **Test Data** | `email=invalid@no.domain`, `password=wrongpassword` |
| **Expected Result** | HTTP 400; body contains an `error` field explaining the rejection |

### TC-AUTH-03 — Valid Bearer token returns 200

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/protected-resource` registered: matches `Authorization: Bearer mock-bearer-token-abc123` → 200 |
| **Test Steps** | 1. Send `GET /api/protected-resource` with header `Authorization: Bearer mock-bearer-token-abc123`<br>2. Assert status code equals 200<br>3. Assert body contains `"secret content"` |
| **Test Data** | Bearer token = `mock-bearer-token-abc123` |
| **Expected Result** | HTTP 200; protected payload returned |

### TC-AUTH-04 — Missing Bearer token returns 401 Unauthorized

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/secured` registered with a fallback 401 response when no/incorrect `Authorization` header is present |
| **Test Steps** | 1. Send `GET /api/secured` with **no** `Authorization` header<br>2. Assert status code equals 401<br>3. Assert body contains `"Unauthorized"` |
| **Test Data** | No headers sent |
| **Expected Result** | HTTP 401; body explains a Bearer token is required |

### TC-AUTH-05 — Basic authentication header is accepted

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/admin` registered: matches `Authorization: Basic base64(admin:secret123)` → 200 |
| **Test Steps** | 1. Base64-encode `admin:secret123` per RFC 7617<br>2. Send `GET /api/admin` with header `Authorization: Basic <encoded>`<br>3. Assert status code equals 200<br>4. Assert body contains `"admin"` and `"granted"` |
| **Test Data** | Username = `admin`, Password = `secret123` |
| **Expected Result** | HTTP 200; body indicates admin access granted |

### TC-AUTH-06 — API key in X-API-Key header grants access

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/data` registered: matches header `X-API-Key: nexus-api-key-secret-9876` → 200 |
| **Test Steps** | 1. Send `GET /api/data` with header `X-API-Key: nexus-api-key-secret-9876`<br>2. Assert status code equals 200<br>3. Assert body contains `"sensitive result"` |
| **Test Data** | API key = `nexus-api-key-secret-9876` |
| **Expected Result** | HTTP 200; protected data returned |

### TC-AUTH-07 — API key as query parameter grants access

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/reports` registered: matches query param `apikey=nexus-api-key-secret-9876` → 200 |
| **Test Steps** | 1. Send `GET /api/reports?apikey=nexus-api-key-secret-9876`<br>2. Assert status code equals 200<br>3. Assert body contains `"reports"` |
| **Test Data** | Query param `apikey=nexus-api-key-secret-9876` |
| **Expected Result** | HTTP 200; reports array returned |

### TC-AUTH-08 — Token from login is reused in a protected call

| Field | Detail |
|---|---|
| **Precondition** | ReqRes demo account active; WireMock available for step 2 |
| **Test Steps** | **Step 1 (live):** Send `POST /login` with valid credentials; extract `token` from the response.<br>**Step 2 (mock):** Register a WireMock stub on `/api/secure-users` that requires `Authorization: Bearer {token}` from step 1; send `GET /api/secure-users` with that header; assert 200 and body contains `"Protected Data"` |
| **Test Data** | `email=eve.holt@reqres.in`, `password=cityslicka` |
| **Expected Result** | Step 1 returns a token; step 2 succeeds using exactly that token (full auth chain) |

---

## 3. Schema Validation (TC-SCHEMA)

**Class:** `com.apinexus.tests.schema.SchemaValidationTest` | **Group:** `schema`

### TC-SCHEMA-01 — ReqRes single-user response matches user_schema.json

| Field | Detail |
|---|---|
| **Precondition** | ReqRes user `id=2` exists |
| **Test Steps** | 1. Send `GET https://reqres.in/api/users/2`<br>2. Assert status code equals 200<br>3. Validate response body against `schemas/user_schema.json` (draft-07) |
| **Test Data** | User ID = 2 |
| **Expected Result** | HTTP 200; body satisfies the schema: `data.id` integer, `data.email` valid email format, `data.first_name`/`last_name` non-empty strings, `data.avatar` valid URI, `support.url`/`text` present |

### TC-SCHEMA-02 — JSONPlaceholder post response matches post_schema.json

| Field | Detail |
|---|---|
| **Precondition** | Post `id=1` exists |
| **Test Steps** | 1. Send `GET /posts/1`<br>2. Assert status code equals 200<br>3. Validate response body against `schemas/post_schema.json` |
| **Test Data** | Post ID = 1 |
| **Expected Result** | HTTP 200; body satisfies schema: `userId`/`id` integers ≥ 1, `title`/`body` non-empty strings, no extra fields |

### TC-SCHEMA-03 — Schema validated across multiple post IDs

| Field | Detail |
|---|---|
| **Precondition** | Posts 1, 10, 25, 50, 100 exist |
| **Test Steps** | For each ID in `[1, 10, 25, 50, 100]`:<br>1. Send `GET /posts/{id}`<br>2. Assert status code equals 200<br>3. Validate against `post_schema.json` |
| **Test Data** | Post IDs: 1, 10, 25, 50, 100 |
| **Expected Result** | All 5 posts pass schema validation with no exceptions |

### TC-SCHEMA-04 — Schema validator correctly rejects an invalid response

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/broken-user` registered, returning `{"id":1,"name":"Broken Structure"}` (missing required `data`/`support` wrappers) |
| **Test Steps** | 1. Send `GET /api/broken-user`<br>2. Assert status code equals 200<br>3. Attempt schema validation against `user_schema.json`, expecting it to throw `AssertionError`<br>4. Catch the error and assert it WAS thrown |
| **Test Data** | Deliberately malformed JSON body |
| **Expected Result** | The schema validator correctly flags the response as invalid (negative/meta test — confirms the validator itself works) |

### TC-SCHEMA-05 — Required response headers are present

| Field | Detail |
|---|---|
| **Precondition** | Post `id=1` exists |
| **Test Steps** | 1. Send `GET /posts/1`<br>2. Assert `content-type` header is present<br>3. Assert `Content-Type` contains `application/json`<br>4. Log all response headers for documentation |
| **Test Data** | Post ID = 1 |
| **Expected Result** | `Content-Type: application/json` header present on every response |

---

## 4. Error Handling (TC-ERR)

**Class:** `com.apinexus.tests.errors.ErrorHandlingTest` | **Group:** `errors`

### TC-ERR-01 — GET non-existent post returns 404

| Field | Detail |
|---|---|
| **Precondition** | Post `id=9999` does not exist (JSONPlaceholder only has IDs 1–100) |
| **Test Steps** | 1. Send `GET /posts/9999`<br>2. Assert status code equals 404 |
| **Test Data** | Post ID = 9999 |
| **Expected Result** | HTTP 404 |

### TC-ERR-02 — 404 error body contains required error fields

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/items/99999` registered → 404 with JSON body from `error_response.json` |
| **Test Steps** | 1. Send `GET /api/items/99999`<br>2. Assert status code equals 404<br>3. Assert `Content-Type` contains `application/json`<br>4. Assert body contains `"error"`, `"404"`, and `"message"` |
| **Test Data** | None |
| **Expected Result** | HTTP 404; structured JSON error body with `error`, `code`, `message`, `timestamp` |

### TC-ERR-03 — 500 Internal Server Error is correctly detected

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/exploding-endpoint` registered → 500 with plain-text body |
| **Test Steps** | 1. Send `GET /api/exploding-endpoint`<br>2. Assert status code equals 500<br>3. Assert body is not empty<br>4. Assert `Content-Type` is either `text/plain` or `application/json` |
| **Test Data** | None |
| **Expected Result** | HTTP 500; non-empty error body |

### TC-ERR-04 — POST with missing required field returns 400

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Build a login JSON body containing only `email` (deliberately omit `password`)<br>2. Send `POST https://reqres.in/api/login`<br>3. Assert status code equals 400<br>4. Assert body contains `"error"` |
| **Test Data** | `email=peter@klaven.com` (no password) |
| **Expected Result** | HTTP 400; server rejects the incomplete request |

### TC-ERR-05 — Unsupported HTTP method returns 405

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `DELETE /api/readonly` registered → 405 with `Allow: GET, HEAD, OPTIONS` header |
| **Test Steps** | 1. Send `DELETE /api/readonly`<br>2. Assert status code equals 405<br>3. Assert `Allow` header is present<br>4. Assert `Allow` header contains `"GET"` |
| **Test Data** | None |
| **Expected Result** | HTTP 405; `Allow` header lists the valid methods |

### TC-ERR-06 — 429 rate-limit response includes Retry-After header

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/heavy-operation` registered → 429 with `Retry-After: 60` |
| **Test Steps** | 1. Send `GET /api/heavy-operation`<br>2. Assert status code equals 429<br>3. Assert `Retry-After` header is present<br>4. Parse `Retry-After` as an integer; assert it is positive |
| **Test Data** | None |
| **Expected Result** | HTTP 429; `Retry-After: 60` instructs the client when to retry |

### TC-ERR-07 — Malformed JSON request body returns 400

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Build a syntactically broken JSON string (unterminated, e.g. `{"email": "test@test.com", "password": `)<br>2. Send `POST https://reqres.in/api/login` with this body<br>3. Assert status code is in range 400–499 |
| **Test Data** | Broken JSON string (missing closing brace and value) |
| **Expected Result** | A 4xx status (not 500) — confirms server-side JSON parsing validation |

### TC-ERR-08 — Network fault (connection reset) is caught gracefully

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/unstable-endpoint` registered with `Fault.CONNECTION_RESET_BY_PEER` |
| **Test Steps** | 1. Send `GET /api/unstable-endpoint`<br>2. Expect the call to throw an exception (TCP reset, no HTTP response possible)<br>3. Catch the exception; assert it has a non-blank message |
| **Test Data** | None |
| **Expected Result** | An exception is thrown and handled gracefully — no unhandled crash |

---

## 5. Pagination & Filtering (TC-PAGE)

**Class:** `com.apinexus.tests.pagination.PaginationFilteringTest` | **Group:** `pagination`

### TC-PAGE-01 — Default page 1 returns correct pagination metadata

| Field | Detail |
|---|---|
| **Precondition** | ReqRes `/users` endpoint has 12 users across 2 pages (6 per page) |
| **Test Steps** | 1. Send `GET https://reqres.in/api/users?page=1`<br>2. Assert status code equals 200<br>3. Assert `page` field equals `1`<br>4. Assert `per_page` is positive<br>5. Assert `data` array size ≤ `per_page`<br>6. Assert `total_pages == ceil(total / per_page)` |
| **Test Data** | Query param `page=1` |
| **Expected Result** | HTTP 200; pagination metadata is mathematically consistent |

### TC-PAGE-02 — Requesting page 2 returns page=2 in response metadata

| Field | Detail |
|---|---|
| **Precondition** | Page 2 exists |
| **Test Steps** | 1. Send `GET /users?page=2`<br>2. Assert status code equals 200<br>3. Assert `page` field equals `2`<br>4. Assert `data` array is non-empty |
| **Test Data** | Query param `page=2` |
| **Expected Result** | HTTP 200; server actually returns page 2, not a cached page 1 |

### TC-PAGE-03 — Boundary per_page values return correct item counts (data-driven)

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | For each `(perPage, minExpectedItems)` row:<br>1. Send `GET /users?page=1&per_page={perPage}`<br>2. Assert status code equals 200<br>3. Assert `data.size() >= minExpectedItems` |
| **Test Data** | Row 1: `per_page=1`, expect ≥1 item<br>Row 2: `per_page=6`, expect ≥6 items<br>Row 3: `per_page=12`, expect ≥6 items |
| **Expected Result** | Server honours the requested page size at each boundary |

### TC-PAGE-04 — Page beyond total returns empty data or 404

| Field | Detail |
|---|---|
| **Precondition** | Page 999 does not exist (only 2 pages total) |
| **Test Steps** | 1. Send `GET /users?page=999`<br>2. Assert status code is 200 or 404<br>3. If 200, assert the `data` array is empty |
| **Test Data** | Query param `page=999` |
| **Expected Result** | Graceful handling of an out-of-range page — no 500 error |

### TC-PAGE-05 — Filter /posts by userId=1 returns only posts from user 1

| Field | Detail |
|---|---|
| **Precondition** | User 1 has authored at least one post |
| **Test Steps** | 1. Send `GET /posts?userId=1`<br>2. Assert status code equals 200<br>3. Parse body as array; assert size > 0<br>4. Iterate every post; assert `userId == 1` for all of them |
| **Test Data** | Query param `userId=1` |
| **Expected Result** | HTTP 200; zero posts from any other user appear in the result |

### TC-PAGE-06 — Multiple query-param filters narrow results correctly

| Field | Detail |
|---|---|
| **Precondition** | Post `id=5` has exactly 5 comments (JSONPlaceholder fixed dataset) |
| **Test Steps** | 1. Send `GET /comments?postId=5`<br>2. Assert status code equals 200<br>3. Iterate every comment; assert `postId == 5`<br>4. Assert each comment's `email` field is non-blank |
| **Test Data** | Query param `postId=5` |
| **Expected Result** | HTTP 200; every comment matches the filter and has a valid email |

### TC-PAGE-07 — Sorted response returns items in correct order (mock)

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/items?_sort=price&_order=asc` registered, returning 3 items pre-sorted ascending by price |
| **Test Steps** | 1. Send `GET /api/items?_sort=price&_order=asc`<br>2. Assert status code equals 200<br>3. Parse the 3-item array<br>4. Iterate and assert each item's `price` ≥ the previous item's `price` |
| **Test Data** | Items priced 9, 49, 199 |
| **Expected Result** | Items returned in strict ascending price order |

### TC-PAGE-08 — Sum of paginated items equals the reported total

| Field | Detail |
|---|---|
| **Precondition** | ReqRes `/users` paginates correctly |
| **Test Steps** | 1. Fetch page 1; read `total` and `total_pages` from the response<br>2. Sum `data.size()` for page 1<br>3. Loop fetching pages 2..`total_pages`, summing `data.size()` for each<br>4. Assert the running sum equals the reported `total` |
| **Test Data** | None (driven entirely by live response metadata) |
| **Expected Result** | Total item count across all pages exactly matches the API's reported `total` field |

---

## 6. Mock Server (TC-MOCK)

**Class:** `com.apinexus.tests.mock.MockServerTest` | **Group:** `mock` | **Target:** Embedded WireMock only

### TC-MOCK-01 — GET /api/users returns mock user list

| Field | Detail |
|---|---|
| **Precondition** | `mockServer.stubGetUsers()` registered: serves `users_response.json` |
| **Test Steps** | 1. Send `GET /api/users`<br>2. Assert status code equals 200<br>3. Assert `Content-Type` contains `application/json`<br>4. Parse body; assert `page=1`, `per_page=3`, `total=3`<br>5. Assert `data` array has 3 users; verify first user's `id=101` and `name="Alice Nexus"` |
| **Test Data** | File-backed response: `testdata/mock_responses/users_response.json` |
| **Expected Result** | HTTP 200; exact JSON structure from the fixture file |

### TC-MOCK-02 — POST /api/users creates user and returns Location header

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `POST /api/users/with-location` registered → 201 with `Location` header |
| **Test Steps** | 1. Build a new-user JSON payload (`name`, `email`)<br>2. Send `POST /api/users/with-location`<br>3. Assert status code equals 201<br>4. Assert `Location` header is present and contains `/api/users/201`<br>5. Assert response `id` field equals `201` |
| **Test Data** | `name="New User"`, `email="newuser@apinexus.io"` |
| **Expected Result** | HTTP 201; `Location` header points to the new resource URL |

### TC-MOCK-03 — Stateful stub transitions PENDING→PROCESSING→COMPLETE

| Field | Detail |
|---|---|
| **Precondition** | `mockServer.stubStatefulOrder("/api/orders/42", "order-processing-scenario")` registered |
| **Test Steps** | **Poll 1:** `GET /api/orders/42` → assert `status == "PENDING"`<br>**Poll 2:** Same URL again → assert `status == "PROCESSING"`<br>**Poll 3:** Same URL again → assert `status == "COMPLETE"` |
| **Test Data** | Order ID = 42, scenario name = `order-processing-scenario` |
| **Expected Result** | Each successive call to the same URL advances the WireMock Scenario state machine one step |

### TC-MOCK-04 — Slow endpoint (500ms delay) is received within SLA

| Field | Detail |
|---|---|
| **Precondition** | `mockServer.stubSlowResponse("/api/slow", 500)` registered |
| **Test Steps** | 1. Record start time<br>2. Send `GET /api/slow`<br>3. Record elapsed time<br>4. Assert status code equals 200<br>5. Assert elapsed time ≥ 500ms (the injected delay)<br>6. Assert elapsed time ≤ SLA + delay |
| **Test Data** | Injected delay = 500ms |
| **Expected Result** | Response delivered after the configured delay, still within the extended SLA window |

### TC-MOCK-05 — Different request bodies produce different responses

| Field | Detail |
|---|---|
| **Precondition** | Two WireMock stubs on `POST /api/query`: one matches `"role":"admin"` in the body, the other matches `"role":"user"` |
| **Test Steps** | **Call 1:** Send body `{"role":"admin","filter":"all"}` → assert 200, body contains `"admin_data"`, `records=1000`<br>**Call 2:** Send body `{"role":"user","filter":"own"}` → assert 200, body contains `"user_data"`, `records=10` |
| **Test Data** | Two distinct request bodies differing only by `role` value |
| **Expected Result** | Server response varies based on request body content (content-based routing) |

### TC-MOCK-06 — Response includes all required custom headers

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/resource` registered with `X-Request-ID`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Cache-Control`, `ETag` headers |
| **Test Steps** | 1. Send `GET /api/resource`<br>2. Assert status code equals 200<br>3. Assert each of the five headers is present<br>4. Assert `X-Request-ID` equals `"req-uuid-9876"`<br>5. Assert `X-RateLimit-Limit` equals `"1000"` |
| **Test Data** | None |
| **Expected Result** | All metadata headers present with correct values |

### TC-MOCK-07 — WireMock verifies exact number of calls made

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `GET /api/verify-me` registered |
| **Test Steps** | 1. Call `GET /api/verify-me` twice<br>2. Use `WireMock.verify(2, getRequestedFor(urlEqualTo("/api/verify-me")))` to assert the stub was hit exactly twice |
| **Test Data** | None |
| **Expected Result** | WireMock confirms exactly 2 matching requests were received — verifies the CLIENT behaviour, not just the response |

### TC-MOCK-08 — CORS pre-flight OPTIONS returns correct CORS headers

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `OPTIONS /api/cors-endpoint` registered → 204 with `Access-Control-*` headers |
| **Test Steps** | 1. Send `OPTIONS /api/cors-endpoint`<br>2. Assert status code equals 204<br>3. Assert `Access-Control-Allow-Origin` header present<br>4. Assert `Access-Control-Allow-Methods` header present and contains `"POST"` |
| **Test Data** | None |
| **Expected Result** | Correct CORS pre-flight response — confirms the API would work from a browser-based cross-origin client |

---

## 7. Performance (TC-PERF)

**Class:** `com.apinexus.tests.performance.PerformanceTest` | **Group:** `performance`

### TC-PERF-01 — Single GET /posts/1 completes within SLA

| Field | Detail |
|---|---|
| **Precondition** | SLA configured as 2000ms (`performance.max.response.time.ms`) |
| **Test Steps** | 1. Record start time<br>2. Send `GET /posts/1`<br>3. Record elapsed time<br>4. Assert status code equals 200<br>5. Assert elapsed time ≤ SLA |
| **Test Data** | None |
| **Expected Result** | Single call completes within 2000ms |

### TC-PERF-02 — 10 sequential calls all within SLA

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Loop 10 times, calling `GET /posts/{1..10}` sequentially, recording each elapsed time<br>2. Assert each call returns 200<br>3. Compute min/max/mean of the 10 timings<br>4. Assert max ≤ SLA<br>5. Assert mean ≤ SLA |
| **Test Data** | Post IDs 1 through 10 |
| **Expected Result** | All 10 calls succeed; no individual call or average exceeds the SLA |

### TC-PERF-03 — 5 concurrent GET calls all succeed within SLA

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Create a fixed thread pool of 5 threads<br>2. Submit 5 tasks, each calling `GET /posts/{11..15}` concurrently<br>3. Collect all 5 results via `invokeAll()`<br>4. Assert every call returned 200<br>5. Compute max elapsed time; assert it is ≤ 2× SLA |
| **Test Data** | Post IDs 11 through 15, 5 concurrent threads |
| **Expected Result** | The API handles concurrent load correctly with no errors and acceptable degradation |

### TC-PERF-04 — Slow endpoint takes measurably longer than fast endpoint

| Field | Detail |
|---|---|
| **Precondition** | WireMock stubs: `/api/fast` (no delay), `/api/slow-perf` (300ms delay) |
| **Test Steps** | 1. Call `/api/fast`; record elapsed time<br>2. Call `/api/slow-perf`; record elapsed time<br>3. Assert both return 200<br>4. Assert `(slowElapsed - fastElapsed) >= 250ms` (300ms delay minus 50ms tolerance) |
| **Test Data** | Injected delay = 300ms |
| **Expected Result** | Timing measurement accurately reflects the injected delay difference |

### TC-PERF-05 — P95 response time is within the SLA

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | 1. Make 20 sequential `GET /posts/{rotating 1-10}` calls, recording each elapsed time<br>2. Sort all 20 timings<br>3. Compute P95 = value at index `floor(0.95 × 20)`<br>4. Assert P95 ≤ SLA |
| **Test Data** | 20 samples, post IDs rotating 1–10 |
| **Expected Result** | 95th percentile latency is within the configured SLA |

---

## 8. API Chaining (TC-CHAIN)

**Class:** `com.apinexus.tests.chaining.ApiChainingTest` | **Group:** `chaining`

### TC-CHAIN-01 — Create post then retrieve it and verify content

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | **Step 1:** `POST /posts` with a new post payload; extract `id` from the response.<br>**Step 2:** `GET /posts/{id}` using the extracted ID; assert status 200 and the returned `id` matches |
| **Test Data** | `title="Chaining Test Post"`, `body="This post was created in TC-CHAIN-01."`, `userId=1` |
| **Expected Result** | The ID returned by CREATE is successfully usable in the subsequent READ call |

### TC-CHAIN-02 — Fetch user then their posts and verify ownership

| Field | Detail |
|---|---|
| **Precondition** | User `id=1` exists and has authored posts |
| **Test Steps** | **Step 1:** `GET /users/1`; extract `id` and `username`.<br>**Step 2:** `GET /posts?userId={id}`; assert array is non-empty.<br>**Step 3:** Iterate every returned post; assert `userId` matches the user fetched in step 1 |
| **Test Data** | User ID = 1 |
| **Expected Result** | Every post returned for the user genuinely belongs to that user — no cross-user data leakage |

### TC-CHAIN-03 — Fetch post then its comments and validate them

| Field | Detail |
|---|---|
| **Precondition** | Post `id=3` exists with comments |
| **Test Steps** | **Step 1:** `GET /posts/3`; extract `id` and `title`.<br>**Step 2:** `GET /comments?postId={id}`; assert array returned.<br>**Step 3:** For each comment, assert `postId` matches, `email` is non-blank and contains `@`, `body` is non-blank |
| **Test Data** | Post ID = 3 |
| **Expected Result** | All comments correctly linked to the post, with valid email format and non-empty content |

### TC-CHAIN-04 — Fetch all users then collect their post counts

| Field | Detail |
|---|---|
| **Precondition** | At least 3 users exist |
| **Test Steps** | **Step 1:** `GET /users`; assert ≥3 users returned.<br>**Step 2 (×3):** For each of the first 3 users, `GET /posts?userId={id}`; assert status 200 and post count > 0; record the count |
| **Test Data** | First 3 users from the live `/users` response |
| **Expected Result** | Each of the 3 sampled users has at least 1 post; per-user post counts collected successfully |

### TC-CHAIN-05 — Full lifecycle: Create → Update → Delete

| Field | Detail |
|---|---|
| **Precondition** | None |
| **Test Steps** | **Step 1 (CREATE):** `POST /posts`; assert 201; extract `id`.<br>**Step 2 (UPDATE):** `PUT /posts/{id}` with a new title; assert 200; assert the returned title matches the update.<br>**Step 3 (DELETE):** `DELETE /posts/{id}`; assert 200.<br>**Step 4 (VERIFY):** Document that on a real (stateful) API this would now return 404 |
| **Test Data** | `title="Lifecycle Test"` → updated to `"Updated Title — Lifecycle"` |
| **Expected Result** | All three write operations (POST/PUT/DELETE) succeed in sequence on the same resource ID |

---

## 9. File Upload (TC-UPLOAD)

**Class:** `com.apinexus.tests.upload.FileUploadTest` | **Group:** `upload` | **Target:** Embedded WireMock only

### TC-UPLOAD-01 — Upload CSV file returns 201 with file metadata

| Field | Detail |
|---|---|
| **Precondition** | A temp CSV file is created in `@BeforeMethod`; `mockServer.stubFileUpload("/api/upload")` registered |
| **Test Steps** | 1. Read the temp file's bytes<br>2. Build `multipart/form-data` body with a `file` part (`FilePayload`)<br>3. Send `POST /api/upload`<br>4. Assert status code equals 201<br>5. Assert `Content-Type` contains `application/json`<br>6. Assert body has `message="File uploaded successfully"`, a non-blank `fileId`, and `size > 0` |
| **Test Data** | CSV content: `id,name,score\n1,Alice,95\n2,Bob,87\n3,Carol,92\n` |
| **Expected Result** | HTTP 201; file accepted and metadata returned |

### TC-UPLOAD-02 — Upload file with additional form metadata fields

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `POST /api/upload/with-fields` registered |
| **Test Steps** | 1. Build multipart body with a `file` part PLUS text parts: `description`, `category`, `tags`<br>2. Send `POST /api/upload/with-fields`<br>3. Assert status code equals 201<br>4. Assert response `category` field equals `"reports"`<br>5. Assert response has a `tags` field |
| **Test Data** | `description="Q1 Financial Report"`, `category="reports"`, `tags="q1,finance"` |
| **Expected Result** | HTTP 201; both the file part and the accompanying text fields are accepted |

### TC-UPLOAD-03 — Wrong Content-Type to upload endpoint returns 400

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `POST /api/upload/strict` registered: rejects any request whose `Content-Type` contains `application/json` |
| **Test Steps** | 1. Send `POST /api/upload/strict` with `Content-Type: application/json` and body `{}` (NOT multipart)<br>2. Assert status code equals 400<br>3. Assert body contains `"multipart/form-data"` |
| **Test Data** | Body = `{}`, header `Content-Type: application/json` |
| **Expected Result** | HTTP 400; server enforces the correct Content-Type for uploads |

### TC-UPLOAD-04 — Missing file part returns 400

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `POST /api/upload/required-file` registered: rejects multipart requests lacking a `file` part |
| **Test Steps** | 1. Build a multipart body with ONLY a `description` text part (no `file` part)<br>2. Send `POST /api/upload/required-file`<br>3. Assert status code equals 400<br>4. Assert body contains `"Missing required field"` |
| **Test Data** | `description="No file attached here"` |
| **Expected Result** | HTTP 400; server validates that the required `file` part is present |

### TC-UPLOAD-05 — Large file upload returns 413 Payload Too Large

| Field | Detail |
|---|---|
| **Precondition** | WireMock stub `POST /api/upload/size-limited` registered: always returns 413 (simulating a file-size cap) |
| **Test Steps** | 1. Build a multipart body with a small file (the stub ignores actual size and always returns 413 for this URL)<br>2. Send `POST /api/upload/size-limited`<br>3. Assert status code equals 413<br>4. Assert body contains `"5MB limit"`<br>5. Parse `maxSizeBytes` field; assert it is positive |
| **Test Data** | 3-byte dummy file `small.bin` |
| **Expected Result** | HTTP 413; error body explains the size limit (5MB / 5,242,880 bytes) |

---

## Test Data Summary

| Source | Used By | Notes |
|---|---|---|
| `https://jsonplaceholder.typicode.com` | CRUD, Schema, Errors, Pagination, Chaining | Live, free, no auth required; writes are faked (not persisted) |
| `https://reqres.in/api` | Auth, Schema (user), Pagination (users) | Live, free; simulates real login/pagination semantics |
| Embedded WireMock (`localhost:8089`) | Mock, Auth (partial), Errors (partial), Pagination (sort), Performance (partial), Upload | Started in `@BeforeSuite`; stubs reset between tests |
| `src/test/resources/testdata/mock_responses/users_response.json` | TC-MOCK-01 | Static fixture file |
| `src/test/resources/testdata/mock_responses/error_response.json` | TC-ERR-02 | Static fixture file |
| `src/test/resources/schemas/post_schema.json` | TC-SCHEMA-02/03/04 | JSON Schema draft-07 |
| `src/test/resources/schemas/user_schema.json` | TC-SCHEMA-01/04 | JSON Schema draft-07 |

## Running a Specific Test Case

```bash
# Run one test class
mvn test -Dtest=AuthenticationTest

# Run one test method
mvn test -Dtest=AuthenticationTest#testValidLogin

# Run an entire group
mvn test -Dgroups=auth
```
