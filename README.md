# ApiNexus — Playwright Java API Testing Framework

A comprehensive, production-grade REST API testing framework built with
**Playwright for Java**, **WireMock**, **TestNG**, **Jackson**, and **Logback**.
Designed for pure API testing (no browser required) and importable directly
into **Eclipse** as a Maven project.

---

## Table of Contents

1. [What This Framework Does](#1-what-this-framework-does)
2. [Technology Stack](#2-technology-stack)
3. [Project Structure](#3-project-structure)
4. [Architecture Overview](#4-architecture-overview)
5. [Core Components](#5-core-components)
   - 5.1 ConfigManager
   - 5.2 ApiLogger
   - 5.3 ResponseValidator
   - 5.4 MockServerManager
   - 5.5 BaseApiTest
6. [Test Suites — What Each Covers](#6-test-suites--what-each-covers)
   - 6.1 CrudOperationsTest
   - 6.2 AuthenticationTest
   - 6.3 SchemaValidationTest
   - 6.4 ErrorHandlingTest
   - 6.5 PaginationFilteringTest
   - 6.6 MockServerTest
   - 6.7 PerformanceTest
   - 6.8 ApiChainingTest
   - 6.9 FileUploadTest
7. [Request/Response Mocking — Deep Dive](#7-requestresponse-mocking--deep-dive)
8. [Logging System](#8-logging-system)
9. [Configuration Reference](#9-configuration-reference)
10. [JSON Schema Contracts](#10-json-schema-contracts)
11. [How to Import in Eclipse](#11-how-to-import-in-eclipse)
12. [How to Run Tests](#12-how-to-run-tests)
13. [Adding New Tests](#13-adding-new-tests)
14. [API Scenarios Coverage Map](#14-api-scenarios-coverage-map)
15. [Detailed Reporting (ExtentReports)](#15-detailed-reporting-extentreports)
16. [Continuous Integration (GitHub Actions)](#16-continuous-integration-github-actions)
17. [Troubleshooting](#17-troubleshooting)

See also: [TEST_CASES.md](TEST_CASES.md) for a full step-by-step specification
of all 61 test cases, and [DEPLOYMENT.md](DEPLOYMENT.md) for deploying
WireMock stubs to the cloud.

---

## 1. What This Framework Does

ApiNexus tests REST APIs at the HTTP layer — no browser, no UI. It sends real
HTTP requests (or intercepts them with a local mock server) and asserts on:

| What is checked | How |
|---|---|
| HTTP status codes | `ResponseValidator.assertStatusCode()` |
| Response body fields | `ResponseValidator.assertJsonField()` |
| JSON structure (schema) | `ResponseValidator.assertJsonSchema()` |
| Response headers | `ResponseValidator.assertHeaderPresent()` |
| Response time SLA | `ResponseValidator.assertResponseTime()` |
| Request was made correctly | `WireMock.verify()` |

The framework targets **two live public APIs** (no account required) plus an
**embedded WireMock server** that runs inside the test JVM:

| Backend | Used for |
|---|---|
| `jsonplaceholder.typicode.com` | CRUD, chaining, schema, pagination, errors |
| `reqres.in/api` | Authentication, pagination meta, user schema |
| `localhost:8089` (WireMock) | Mocking, error simulation, upload, performance |

---

## 2. Technology Stack

| Library | Version | Role |
|---|---|---|
| **Playwright for Java** | 1.44.0 | HTTP client (`APIRequestContext`) — the backbone of every request |
| **TestNG** | 7.9.0 | Test runner, parallel execution, `@DataProvider`, `testng.xml` suites |
| **WireMock JRE-8 Standalone** | 2.35.0 | Embedded mock HTTP server — no external process needed |
| **Jackson Databind** | 2.17.1 | JSON serialisation (Java → JSON) and deserialisation (JSON → Java) |
| **networknt JSON Schema Validator** | 1.3.3 | Validates response bodies against JSON Schema draft-07 contracts |
| **SLF4J API** | 2.0.13 | Logging facade — code depends on the API, not on any specific logger |
| **Logback Classic** | 1.5.6 | SLF4J backend — writes to console and a rolling log file |
| **Apache Commons IO** | 2.16.1 | Reads classpath files (mock response JSON, schema files) into strings |
| **Java** | 11 | Language level (LTS; widely available in Eclipse / CI environments) |
| **Maven** | 3.x | Build tool, dependency management, test execution via Surefire plugin |

---

## 3. Project Structure

```
apinexus-playwright/
│
├── pom.xml                                       # Maven build definition
│
├── src/
│   ├── main/java/com/apinexus/
│   │   ├── config/
│   │   │   └── ConfigManager.java               # Singleton config reader
│   │   ├── models/
│   │   │   ├── Post.java                        # JSONPlaceholder post POJO
│   │   │   └── User.java                        # ReqRes user POJO
│   │   └── utils/
│   │       ├── ApiLogger.java                   # Centralised HTTP logging
│   │       └── ResponseValidator.java           # Reusable assertion helpers
│   │
│   └── test/
│       ├── java/com/apinexus/
│       │   ├── mock/
│       │   │   └── MockServerManager.java       # WireMock lifecycle + stubs
│       │   └── tests/
│       │       ├── base/
│       │       │   └── BaseApiTest.java         # @BeforeSuite/@AfterSuite
│       │       ├── crud/
│       │       │   └── CrudOperationsTest.java
│       │       ├── auth/
│       │       │   └── AuthenticationTest.java
│       │       ├── schema/
│       │       │   └── SchemaValidationTest.java
│       │       ├── errors/
│       │       │   └── ErrorHandlingTest.java
│       │       ├── pagination/
│       │       │   └── PaginationFilteringTest.java
│       │       ├── mock/
│       │       │   └── MockServerTest.java
│       │       ├── performance/
│       │       │   └── PerformanceTest.java
│       │       ├── chaining/
│       │       │   └── ApiChainingTest.java
│       │       └── upload/
│       │           └── FileUploadTest.java
│       │
│       └── resources/
│           ├── config.properties               # URLs, ports, timeouts, tokens
│           ├── logback-test.xml                # Logging config for test runs
│           ├── testng.xml                      # Suite orchestration
│           ├── schemas/
│           │   ├── post_schema.json            # JSON Schema for a Post
│           │   └── user_schema.json            # JSON Schema for a User
│           └── testdata/
│               ├── create_user.json            # Body for POST /users
│               └── mock_responses/
│                   ├── users_response.json     # WireMock response body
│                   └── error_response.json     # WireMock 404 error body
│
└── logs/                                       # Created at runtime by Logback
    └── apinexus-test.log
```

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        TestNG Runner                            │
│              (testng.xml → Maven Surefire Plugin)               │
└────────────────────────┬────────────────────────────────────────┘
                         │ @BeforeSuite
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                      BaseApiTest                                │
│  • Creates Playwright + APIRequestContext                       │
│  • Starts WireMock on port 8089                                 │
│  • Reads ConfigManager (config.properties)                      │
│  • Exposes: request, baseUrl, authBaseUrl, mockBaseUrl          │
└────┬───────────────────────────────────────────────────────────┘
     │ extends (all test classes inherit)
     ▼
┌──────────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ...
│  CrudTest    │  │ AuthTest │  │MockTest  │  │ PerfTest │
└──────┬───────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
       │               │              │               │
       ▼               ▼              ▼               ▼
┌──────────────────────────────────────────────────────────────┐
│                   APIRequestContext (Playwright)              │
│      GET / POST / PUT / PATCH / DELETE / fetch(OPTIONS)      │
└──────────────────────────┬───────────────────────────────────┘
                           │
          ┌────────────────┴─────────────────┐
          ▼                                   ▼
   ┌─────────────────┐              ┌──────────────────────┐
   │  Live Internet  │              │  WireMock (local)    │
   │  jsonplaceholder│              │  localhost:8089       │
   │  reqres.in      │              │  (mock stubs)        │
   └─────────────────┘              └──────────────────────┘
          │                                   │
          └───────────────┬───────────────────┘
                          ▼
               ┌─────────────────────┐
               │  APIResponse        │
               │  .status()          │
               │  .text()            │
               │  .headers()         │
               └─────────┬───────────┘
                         │
             ┌───────────┴───────────┐
             ▼                       ▼
   ┌──────────────────┐   ┌──────────────────────┐
   │   ApiLogger      │   │  ResponseValidator   │
   │   (log request,  │   │  (assert status,     │
   │    response,     │   │   body, schema,      │
   │    assertions)   │   │   headers, time)     │
   └──────────────────┘   └──────────────────────┘
```

**Key design decisions:**

- **Single Playwright instance** shared across the entire suite (`@BeforeSuite`) — avoids the cost of creating a new browser driver per test class.
- **Single WireMock instance** on a fixed port — each test registers and clears stubs around itself.
- **Static utility classes** (`ApiLogger`, `ResponseValidator`) — no state, no injection needed, callable from anywhere.
- **Inheritance over composition** for test classes — `BaseApiTest` gives every subclass access to `request`, `config`, and `mockServer` without repetition.

---

## 5. Core Components

### 5.1 ConfigManager

**File:** `src/main/java/com/apinexus/config/ConfigManager.java`

A thread-safe singleton that reads `config.properties` from the classpath once
and caches the result. Uses **double-checked locking** to prevent multiple
initialisation threads from each reading the file.

```java
// How test classes use it:
String url   = config.getBaseUrl();          // https://jsonplaceholder.typicode.com
int    port  = config.getMockServerPort();   // 8089
long   sla   = config.getMaxResponseTimeMs(); // 2000
String token = config.getDemoAuthToken();    // QpwL5tpe83ilfN2
```

If a property key is missing the getter throws `IllegalArgumentException`
immediately — tests fail with a clear message rather than a null pointer later.

---

### 5.2 ApiLogger

**File:** `src/main/java/com/apinexus/utils/ApiLogger.java`

Static methods that write structured, human-readable entries to the SLF4J
logger. Every HTTP interaction is logged before and after:

```
══════════════════════════════════════════════════════════════════════
▶ REQUEST
  Method  : GET
  URL     : https://jsonplaceholder.typicode.com/posts/1
  Headers :
    Authorization : Bearer QpwL5****[MASKED]
──────────────────────────────────────────────────────────────────────
◀ RESPONSE
  Status  : 200 OK
  Time    : 312 ms
  Headers :
    content-type : application/json; charset=utf-8
  Body    :
    {"userId":1,"id":1,"title":"sunt aut facere...","body":"quia et..."}
══════════════════════════════════════════════════════════════════════
```

Security note: `Authorization`, `X-API-Key`, `Cookie`, and `token` header
**values** are automatically masked — only the first 6 characters are shown.
Header names are still logged so you can confirm the header was sent.

Key methods:

| Method | Purpose |
|---|---|
| `logRequest(method, url, headers, body)` | Log outgoing request |
| `logResponse(response, elapsedMs)` | Log incoming response + timing |
| `logAssertion(description, expected, actual)` | Log each assertion with ✔/✘ |
| `logStep(n, description)` | Narrate multi-step flows |
| `logTestStart(name)` / `logTestEnd(name)` | Visually bookend each test |

---

### 5.3 ResponseValidator

**File:** `src/main/java/com/apinexus/utils/ResponseValidator.java`

Static assertion helpers that wrap TestNG `Assert` calls with logging. All
failures include a clear message explaining what was expected vs what arrived.

| Method | What it checks |
|---|---|
| `assertStatusCode(response, 200)` | Exact HTTP status match |
| `assertContentType(response, "application/json")` | Header contains substring |
| `assertJsonField(response, "data/id", "2")` | JSON Pointer field value |
| `assertBodyContains(response, "token")` | Body contains substring |
| `assertBodyNotEmpty(response)` | Body is not null/blank |
| `assertArrayMinSize(response, 100)` | JSON array has ≥ N elements |
| `assertResponseTime(elapsedMs, maxMs)` | Round-trip time within SLA |
| `assertHeaderPresent(response, "etag")` | Header exists (any value) |
| `assertJsonSchema(response, "schemas/post_schema.json")` | Full schema validation |

`assertJsonSchema` uses the **networknt** JSON Schema validator against
draft-07 schemas. Every violation is listed in the failure message:
```
JSON Schema validation FAILED:
  - $.data.email: does not match the email format
  - $.support: is missing and it is required
```

---

### 5.4 MockServerManager

**File:** `src/test/java/com/apinexus/mock/MockServerManager.java`

Wraps the embedded `WireMockServer` with a clean API for the lifecycle and
the most common stub patterns.

**Lifecycle:**
```java
mockServer = new MockServerManager(8089);
mockServer.start();      // opens TCP listener
// ... tests run ...
mockServer.stop();       // releases port
mockServer.resetAllStubs(); // call in @AfterMethod to clear stubs between tests
```

**Built-in stub methods:**

| Method | Behaviour |
|---|---|
| `stubGetUsers()` | `GET /api/users` → 200, body from `users_response.json` |
| `stubCreateUser(body)` | `POST /api/users` → 201, custom body |
| `stubNotFound(path)` | `GET {path}` → 404, body from `error_response.json` |
| `stubInternalServerError(path)` | `GET {path}` → 500, plain-text body |
| `stubSlowResponse(path, delayMs)` | `GET {path}` → 200 after N ms delay |
| `stubNetworkFault(path)` | `GET {path}` → TCP connection reset (no HTTP response) |
| `stubBearerAuthProtected(path, token, body)` | Valid token → 200; missing token → 401 |
| `stubStatefulOrder(path, scenario)` | 3-state scenario: PENDING → PROCESSING → COMPLETE |
| `stubRateLimited(path)` | `GET {path}` → 429 + `Retry-After: 60` |
| `stubFileUpload(path)` | `POST {path}` (multipart) → 201 with file metadata |

For stubs not covered by these helpers, call WireMock's static DSL directly
inside test methods — full examples are in `MockServerTest.java` and
`AuthenticationTest.java`.

---

### 5.5 BaseApiTest

**File:** `src/test/java/com/apinexus/tests/base/BaseApiTest.java`

The parent class every test class extends. Manages the full suite lifecycle:

```
@BeforeSuite
  1. Read config.properties  →  set baseUrl, authBaseUrl
  2. new MockServerManager(port).start()  →  WireMock ready on port 8089
  3. Playwright.create()  →  driver process started
  4. playwright.request().newContext(baseURL, timeout)  →  request object ready

@AfterSuite
  1. request.dispose()     →  flush/abort in-flight requests
  2. playwright.close()    →  terminate driver process
  3. mockServer.stop()     →  release TCP port
```

Protected fields available to all test classes:

| Field | Type | Description |
|---|---|---|
| `request` | `APIRequestContext` | The HTTP client for all API calls |
| `config` | `ConfigManager` | Typed access to config.properties |
| `mockServer` | `MockServerManager` | WireMock lifecycle and stub helpers |
| `baseUrl` | `String` | JSONPlaceholder base URL |
| `authBaseUrl` | `String` | ReqRes base URL |
| `mockBaseUrl` | `String` | `http://localhost:8089` |

---

## 6. Test Suites — What Each Covers

### 6.1 CrudOperationsTest

**Group:** `crud` | **API:** JSONPlaceholder

| TC | Method | Endpoint | Scenario |
|---|---|---|---|
| CRUD-01 | GET | `/posts` | Retrieve all 100 posts |
| CRUD-02 | GET | `/posts/1` | Retrieve single post, assert fields |
| CRUD-03 | GET | `/posts/{id}` | Data-driven: IDs 1, 5, 10 → 200; ID 9999 → 404 |
| CRUD-04 | POST | `/posts` | Create post, verify 201 + echoed body |
| CRUD-05 | PUT | `/posts/1` | Full update, verify all fields replaced |
| CRUD-06 | PATCH | `/posts/1` | Partial update, only title changed |
| CRUD-07 | DELETE | `/posts/1` | Delete, verify 200 |
| CRUD-08 | GET | `/posts/1` | Custom headers: Accept, X-Request-ID, X-Client-Version |
| CRUD-09 | GET | `/comments?postId=1` | Query param filter, verify every result has `postId=1` |

**Data Provider example (TC-CRUD-03):**
```java
@DataProvider(name = "postIds")
public Object[][] postIdProvider() {
    return new Object[][] {
        { 1,    200 },
        { 5,    200 },
        { 9999, 404 }
    };
}
```
TestNG calls the test method once per row — three executions in this case.

---

### 6.2 AuthenticationTest

**Group:** `auth` | **API:** ReqRes (live) + WireMock (mock)

| TC | Scenario | Auth type |
|---|---|---|
| AUTH-01 | Valid login → receive token | Live POST /login |
| AUTH-02 | Invalid credentials → 400 | Live POST /login |
| AUTH-03 | Correct Bearer token → 200 | WireMock header match |
| AUTH-04 | Missing Bearer token → 401 | WireMock fallback stub |
| AUTH-05 | `Authorization: Basic base64(user:pass)` | WireMock header match |
| AUTH-06 | `X-API-Key: <key>` in header | WireMock header match |
| AUTH-07 | `?apikey=<key>` in query param | WireMock query-param match |
| AUTH-08 | Login → extract token → use in next call | 2-step chained flow |

**How Bearer auth stubs work:**

WireMock registers two stubs for the same path. The more specific one
(with header match) takes priority:

```
Stub 1: GET /api/protected  WITH  Authorization: Bearer abc123  →  200
Stub 2: GET /api/protected  (no header match / catch-all)        →  401
```

If the request includes the correct header, Stub 1 wins.
If the header is absent or wrong, Stub 2 fires.

---

### 6.3 SchemaValidationTest

**Group:** `schema` | **API:** JSONPlaceholder + ReqRes + WireMock

| TC | Scenario |
|---|---|
| SCHEMA-01 | Single user response vs `user_schema.json` |
| SCHEMA-02 | Single post response vs `post_schema.json` |
| SCHEMA-03 | Same schema checked across post IDs 1, 10, 25, 50, 100 |
| SCHEMA-04 | Broken response (missing required fields) → validator must REJECT it |
| SCHEMA-05 | Required response headers present (Content-Type, etc.) |

**What the schemas enforce:**

`post_schema.json` requires:
- `userId`: integer ≥ 1
- `id`: integer ≥ 1
- `title`: non-empty string
- `body`: non-empty string
- No additional properties

`user_schema.json` requires:
- `data.id`: integer
- `data.email`: valid email format
- `data.first_name`: non-empty string
- `data.avatar`: valid URI
- `support.url`: valid URI
- `support.text`: string

**TC-SCHEMA-04 (negative test):** A mock endpoint returns `{"id":1,"name":"Broken"}` which is missing the `data` and `support` wrappers. The test asserts that `assertJsonSchema` throws an `AssertionError` — this validates our validator actually works.

---

### 6.4 ErrorHandlingTest

**Group:** `errors` | **API:** JSONPlaceholder + ReqRes + WireMock

| TC | Status | Trigger |
|---|---|---|
| ERR-01 | 404 | GET `/posts/9999` on live API |
| ERR-02 | 404 | WireMock stub returning JSON error body |
| ERR-03 | 500 | WireMock stub returning plain-text error body |
| ERR-04 | 400 | POST to ReqRes `/login` with missing `password` field |
| ERR-05 | 405 | DELETE to a read-only WireMock endpoint; `Allow` header verified |
| ERR-06 | 429 | WireMock stub; `Retry-After: 60` header verified |
| ERR-07 | 4xx | POST with syntactically broken JSON body |
| ERR-08 | Exception | WireMock `Fault.CONNECTION_RESET_BY_PEER` — caught gracefully |

**TC-ERR-08 pattern:**
```java
try {
    APIResponse response = request.get(url);  // TCP reset → PlaywrightException
    log.warn("Unexpected response received");
} catch (Exception e) {
    log.info("Network fault correctly produced exception: {}", e.getMessage());
    Assert.assertTrue(e.getMessage() != null);
    // Test passes — no unhandled exception
}
```

---

### 6.5 PaginationFilteringTest

**Group:** `pagination` | **API:** ReqRes + JSONPlaceholder + WireMock

| TC | Scenario |
|---|---|
| PAGE-01 | Page 1: verify `page`, `per_page`, `total_pages` math |
| PAGE-02 | Page 2: verify `page=2` is returned |
| PAGE-03 | `per_page=1`, `per_page=6`, `per_page=12` boundary values |
| PAGE-04 | Page 999: expect 200 + empty array OR 404 |
| PAGE-05 | `GET /posts?userId=1`: every result must have `userId=1` |
| PAGE-06 | `GET /comments?postId=5`: verify count and `postId` on every comment |
| PAGE-07 | Sorted response: ascending price order verified element by element |
| PAGE-08 | Paginate all pages, sum items, compare to reported `total` |

**TC-PAGE-08 pattern (total count consistency):**
```
Fetch page 1 → discover totalPages = 2
Fetch page 2 → collect items
Sum = page1.data.size + page2.data.size
Assert sum == body.total
```

---

### 6.6 MockServerTest

**Group:** `mock` | **Server:** WireMock only

This is the WireMock showcase — every advanced mocking pattern is demonstrated here.

| TC | Pattern |
|---|---|
| MOCK-01 | File-backed response body (loaded from classpath JSON) |
| MOCK-02 | POST → 201 + `Location` header pointing to new resource URL |
| MOCK-03 | Stateful scenario: 3 calls return PENDING → PROCESSING → COMPLETE |
| MOCK-04 | 500ms injected delay; assert `elapsed >= 500ms` |
| MOCK-05 | Conditional response: `"role":"admin"` body → 1000 records; `"role":"user"` → 10 |
| MOCK-06 | Response with `X-Request-ID`, `X-RateLimit-*`, `Cache-Control`, `ETag` |
| MOCK-07 | `WireMock.verify(2, getRequestedFor(...))` — assert called exactly twice |
| MOCK-08 | OPTIONS pre-flight; `Access-Control-Allow-Methods` verified |

**Stateful scenario internals (TC-MOCK-03):**

```
WireMock Scenario "order-processing-scenario"

State: STARTED       → GET /api/orders/42 → returns {"status":"PENDING"}
                                          → transitions to "PROCESSING"
State: PROCESSING    → GET /api/orders/42 → returns {"status":"PROCESSING"}
                                          → transitions to "COMPLETE"
State: COMPLETE      → GET /api/orders/42 → returns {"status":"COMPLETE"}
                                          → stays in COMPLETE (terminal)
```

Each sequential call moves the state machine forward. This is WireMock's
built-in `Scenario` API — no custom code required.

---

### 6.7 PerformanceTest

**Group:** `performance` | **API:** JSONPlaceholder + WireMock

| TC | What is measured |
|---|---|
| PERF-01 | Single GET within `performance.max.response.time.ms` (2000ms) |
| PERF-02 | 10 sequential calls: mean ≤ SLA, max ≤ SLA |
| PERF-03 | 5 simultaneous threads: max ≤ 2× SLA (concurrency overhead allowed) |
| PERF-04 | Fast mock (0ms) vs slow mock (300ms): difference ≥ 250ms |
| PERF-05 | P95 over 20 samples: 95th-percentile time ≤ SLA |

**Concurrency test pattern (TC-PERF-03):**
```java
ExecutorService pool = Executors.newFixedThreadPool(5);
List<Callable<Long>> tasks = /* 5 tasks, each calls request.get() */;
List<Future<Long>> futures = pool.invokeAll(tasks);
// APIRequestContext is thread-safe — concurrent calls are fine
```

**P95 calculation:**
```java
List<Long> sorted = times.stream().sorted().collect(toList());
int index = (int) Math.floor(0.95 * sampleSize);  // e.g. floor(0.95 * 20) = 19
long p95  = sorted.get(index);
```

---

### 6.8 ApiChainingTest

**Group:** `chaining` | **API:** JSONPlaceholder + WireMock

| TC | Chain |
|---|---|
| CHAIN-01 | POST `/posts` → extract `id` → GET `/posts/{id}` → verify fields match |
| CHAIN-02 | GET `/users/1` → extract `userId` → GET `/posts?userId={id}` → verify ownership |
| CHAIN-03 | GET `/posts/3` → extract `id` → GET `/comments?postId={id}` → validate emails |
| CHAIN-04 | GET `/users` → for each of first 3 users → GET their posts → collect counts |
| CHAIN-05 | POST `/posts` → PUT `/posts/{id}` → DELETE `/posts/{id}` → full lifecycle |

**Chaining pattern:**
```java
// Step 1 — create
APIResponse create = request.post(url, options);
int id = mapper.readTree(create.text()).get("id").asInt();  // extract

// Step 2 — use id from step 1
APIResponse read = request.get(baseUrl + "/posts/" + id);   // inject
```

The extracted value from one response becomes the input to the next request.

---

### 6.9 FileUploadTest

**Group:** `upload` | **Server:** WireMock only

| TC | Scenario |
|---|---|
| UPLOAD-01 | Upload CSV file → 201 + `fileId` + `size` in response |
| UPLOAD-02 | Upload file + text fields (`description`, `category`, `tags`) |
| UPLOAD-03 | Send `application/json` instead of `multipart/form-data` → 400 |
| UPLOAD-04 | Multipart without the `file` part → 400 |
| UPLOAD-05 | Any request to size-limited endpoint → 413 + `maxSizeBytes` in response |

**Playwright multipart API:**
```java
// FilePayload wraps bytes + filename + MIME type into one part
FormData form = FormData.create()
    .append("file",        new FilePayload("report.csv", "text/csv", csvBytes))
    .append("description", "Q1 Report")     // plain text part
    .append("category",    "finance");

request.post(url, RequestOptions.create().setMultipart(form));
// Playwright auto-sets: Content-Type: multipart/form-data; boundary=<generated>
```

---

## 7. Request/Response Mocking — Deep Dive

WireMock runs as an embedded HTTP server inside the test JVM. It intercepts
real HTTP calls made to `http://localhost:8089`.

### How a stub is matched

WireMock evaluates incoming requests against registered stubs in
**most-recently-declared-first** order. The first stub whose matchers all
pass wins. If no stub matches, WireMock returns 404 with an explanation.

### Stub anatomy

```java
stubFor(
    get(urlEqualTo("/api/users"))         // URL matcher
        .withHeader("X-API-Key",          // header matcher (optional)
                    equalTo("my-key"))
        .withQueryParam("page",           // query param matcher (optional)
                    equalTo("1"))
        .withRequestBody(                 // body matcher (optional)
                    containing("admin"))
        .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[]}")
                .withFixedDelay(300))     // inject 300ms delay (optional)
);
```

### Request verification

After the test runs, WireMock can verify requests were actually made:
```java
// Assert this URL was called exactly twice
WireMock.verify(2, getRequestedFor(urlEqualTo("/api/users")));
```

### Fault injection

```java
stubFor(get(urlEqualTo("/crash"))
    .willReturn(aResponse()
        .withFault(Fault.CONNECTION_RESET_BY_PEER))); // TCP reset
```

Other fault types: `Fault.EMPTY_RESPONSE`, `Fault.RANDOM_DATA_THEN_CLOSE`,
`Fault.MALFORMED_RESPONSE_CHUNK`.

### Stateful scenarios

```java
stubFor(get(urlEqualTo("/order"))
    .inScenario("my-scenario")
    .whenScenarioStateIs(Scenario.STARTED)   // initial state
    .willReturn(aResponse().withBody("{\"status\":\"PENDING\"}"))
    .willSetStateTo("DONE"));                // transition

stubFor(get(urlEqualTo("/order"))
    .inScenario("my-scenario")
    .whenScenarioStateIs("DONE")
    .willReturn(aResponse().withBody("{\"status\":\"DONE\"}")));
```

---

## 8. Logging System

### Configuration file

`src/test/resources/logback-test.xml` — loaded automatically by Logback
on the test classpath.

### Two outputs

| Output | Location | Rolling policy |
|---|---|---|
| Console | Eclipse Console view / terminal | No rotation |
| File | `logs/apinexus-test.log` | Daily + 10 MB per file, 30 days history |

### Log format

```
HH:mm:ss.SSS [thread] LEVEL  logger-name — message
10:32:15.421 [main]   INFO  c.a.utils.ApiLogger — ▶ REQUEST
10:32:15.422 [main]   INFO  c.a.utils.ApiLogger —   Method  : GET
```

### Log levels per package

| Package | Level | Reason |
|---|---|---|
| `com.apinexus` | DEBUG | Show everything from our own code |
| `com.github.tomakehurst.wiremock` | INFO | Silence chatty WireMock internals |
| `com.microsoft.playwright` | INFO | Silence Playwright driver debug output |

### Grep patterns for log analysis

```bash
# All requests made during the run
grep "▶ REQUEST" logs/apinexus-test.log

# All failed assertions
grep "✘ CHECK" logs/apinexus-test.log

# Response times
grep "Time    :" logs/apinexus-test.log

# A specific test's output
grep -A 50 "TEST START: testValidLogin" logs/apinexus-test.log
```

---

## 9. Configuration Reference

**File:** `src/test/resources/config.properties`

| Key | Default | Description |
|---|---|---|
| `base.url` | `https://jsonplaceholder.typicode.com` | CRUD / schema / chaining base URL |
| `auth.base.url` | `https://reqres.in/api` | Auth / pagination base URL |
| `mock.server.port` | `8089` | WireMock TCP port |
| `http.connect.timeout` | `5000` | TCP connect timeout (ms) |
| `http.read.timeout` | `10000` | Response read timeout (ms) |
| `performance.max.response.time.ms` | `2000` | SLA ceiling for performance tests |
| `auth.demo.token` | `QpwL5tpe83ilfN2` | Fallback Bearer token (ReqRes) |
| `test.valid.post.id` | `1` | Post ID used in single-resource tests |
| `test.invalid.post.id` | `9999` | Post ID expected to return 404 |
| `pagination.default.page` | `1` | Default page for pagination tests |
| `pagination.default.per.page` | `6` | Default per-page size |
| `test.valid.user.id` | `2` | ReqRes user ID expected to exist |
| `test.invalid.user.id` | `23` | ReqRes user ID expected to return 404 |

To override a value without editing the file, use a Maven system property:
```bash
mvn test -Dperformance.max.response.time.ms=5000
```

---

## 10. JSON Schema Contracts

Schemas live in `src/test/resources/schemas/` and are loaded via the classpath
by `ResponseValidator.assertJsonSchema()`.

### post_schema.json

Validates `GET /posts/{id}` from JSONPlaceholder:

```json
{
  "type": "object",
  "required": ["userId", "id", "title", "body"],
  "properties": {
    "userId": { "type": "integer", "minimum": 1 },
    "id":     { "type": "integer", "minimum": 1 },
    "title":  { "type": "string",  "minLength": 1 },
    "body":   { "type": "string",  "minLength": 1 }
  },
  "additionalProperties": false
}
```

`additionalProperties: false` means any field the API adds in the future
that is NOT in this list will cause the test to fail — acting as a breaking-
change detector.

### user_schema.json

Validates `GET /users/{id}` from ReqRes. Note the nested `data` wrapper:

```json
{
  "type": "object",
  "required": ["data", "support"],
  "properties": {
    "data": {
      "required": ["id", "email", "first_name", "last_name", "avatar"],
      "properties": {
        "email":  { "type": "string", "format": "email" },
        "avatar": { "type": "string", "format": "uri" }
      }
    }
  }
}
```

The `"format": "email"` and `"format": "uri"` constraints validate not just
that the field is a string, but that the string looks like a valid email
address / URL.

---

## 11. How to Import in Eclipse

1. **Open Eclipse** (Eclipse IDE for Java Developers, 2022-06 or later recommended).

2. **Import the project:**
   `File → Import → Maven → Existing Maven Projects → Next`

3. **Browse** to the `apinexus-playwright/` directory and click **Finish**.
   Eclipse will read `pom.xml` and download all dependencies automatically.

4. **Wait for Maven to sync** (bottom-right progress bar in Eclipse).
   First-time import downloads ~50 MB of libraries.

5. **Install Playwright browsers** (one-time setup):
   Open a terminal in the project root and run:
   ```bash
   mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install --with-deps chromium"
   ```
   > For pure API testing (no browser), this step is optional — Playwright's
   > `APIRequestContext` does not require a browser binary.

6. **Run tests from Eclipse:**
   - Right-click `pom.xml` → `Run As → Maven test`
   - Or right-click `testng.xml` → `Run As → TestNG Suite`
   - Or right-click any test class → `Run As → TestNG Test`

---

## 12. How to Run Tests

### Run the complete suite
```bash
cd apinexus-playwright
mvn test
```

### Run a specific group
```bash
mvn test -Dgroups=crud
mvn test -Dgroups=auth
mvn test -Dgroups=mock
mvn test -Dgroups=schema
mvn test -Dgroups=errors
mvn test -Dgroups=pagination
mvn test -Dgroups=performance
mvn test -Dgroups=chaining
mvn test -Dgroups=upload
```

### Run a single test class
```bash
mvn test -Dtest=CrudOperationsTest
mvn test -Dtest=MockServerTest#testStatefulPollingFlow
```

### Run with a custom SLA
```bash
mvn test -Dgroups=performance -Dperformance.max.response.time.ms=5000
```

### View test reports

ApiNexus produces **three** reports from a single `mvn test` run:

| Report | Location | What it shows |
|---|---|---|
| **ExtentReports HTML** (detailed) | `reports/ApiNexus-Test-Report-<timestamp>.html` | One page per test with the full request/response/assertion trail, color-coded by pass/fail/skip, filterable by suite/category. Open directly in any browser — no server needed. |
| **Surefire raw XML/TXT** | `target/surefire-reports/` | Machine-readable JUnit-style XML, consumed by CI tools and the GitHub Actions workflow summary. |
| **Surefire classic HTML** | `target/site/surefire-report.html` | Generate with `mvn surefire-report:report` — simple navigable pass/fail table. |

Open the ExtentReports file for the most detailed view:
```bash
open reports/ApiNexus-Test-Report-*.html   # macOS
xdg-open reports/ApiNexus-Test-Report-*.html  # Linux
```

See [Detailed Reporting](#16-detailed-reporting-extentreports) below for how this is built.

### Tail the live log during a run
```bash
# In a second terminal while mvn test is running:
tail -f logs/apinexus-test.log
```

---

## 13. Adding New Tests

### Step 1 — Create the test class

```java
package com.apinexus.tests.myfeature;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.microsoft.playwright.APIResponse;
import org.testng.annotations.Test;

@Test(groups = "myfeature")
public class MyFeatureTest extends BaseApiTest {

    @Test(description = "TC-MY-01: describe what this tests")
    public void testSomething() {
        logTestStart("testSomething");

        String url = baseUrl + "/my-endpoint";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertContentType(response, "application/json");

        logTestEnd("testSomething");
    }
}
```

### Step 2 — Register in testng.xml

Add a `<test>` block to `src/test/resources/testng.xml`:

```xml
<test name="My Feature Tests" preserve-order="true">
    <groups>
        <run><include name="myfeature"/></run>
    </groups>
    <classes>
        <class name="com.apinexus.tests.myfeature.MyFeatureTest"/>
    </classes>
</test>
```

### Step 3 — Add WireMock stubs (if needed)

For stubs specific to one test, register inline in the test method and clean up
with `mockServer.resetAllStubs()` in an `@AfterMethod`.

For stubs shared across a class, add a helper method to `MockServerManager`
and call it from `@BeforeClass`.

---

## 14. API Scenarios Coverage Map

| Category | Scenarios Covered | Test Class |
|---|---|---|
| **HTTP Verbs** | GET, POST, PUT, PATCH, DELETE, OPTIONS | CrudOperationsTest, MockServerTest |
| **Status Codes** | 200, 201, 204, 400, 401, 403, 404, 405, 413, 422, 429, 500 | ErrorHandlingTest, MockServerTest, AuthenticationTest |
| **Authentication** | Bearer token, Basic auth, API key (header), API key (query), chained login | AuthenticationTest |
| **Request** | Query params, path params, custom headers, JSON body, multipart body | CrudOperationsTest, FileUploadTest |
| **Response** | Status, body fields, body contains, headers, Content-Type, response time | ResponseValidator (all tests) |
| **Schema** | JSON Schema draft-07, required fields, types, format, additionalProperties, negative | SchemaValidationTest |
| **Pagination** | Page number, per-page size, empty page, total count consistency | PaginationFilteringTest |
| **Filtering** | Query param filter, result ownership verification | PaginationFilteringTest, CrudOperationsTest |
| **Sorting** | Ascending/descending order verification | PaginationFilteringTest |
| **Mocking** | Inline stubs, file-backed responses, conditional by body, stateful, delay, fault | MockServerTest, MockServerManager |
| **Performance** | Single SLA, sequential mean/max, concurrent threads, fast vs slow, P95 | PerformanceTest |
| **API Chaining** | Create→read, user→owned resources, post→comments, multi-user counts, lifecycle | ApiChainingTest |
| **File Upload** | Multipart form-data, mixed file+text parts, wrong MIME type, missing part, 413 | FileUploadTest |
| **Error Handling** | 4xx, 5xx, malformed JSON body, network fault (TCP reset), method not allowed | ErrorHandlingTest |
| **Logging** | Request, response, assertions, steps, masking of secrets | ApiLogger (all tests) |
| **Config** | Centralised, single source of truth, typed getters, fail-fast on missing keys | ConfigManager |

---

## 15. Detailed Reporting (ExtentReports)

Every `mvn test` run automatically produces a self-contained, interactive
HTML report under `reports/ApiNexus-Test-Report-<timestamp>.html` — no
external CLI tool (unlike Allure) is required to generate or view it.

### How it works

```
testng.xml registers ExtentTestListener
        │
        ├── onTestStart   → creates an ExtentTest node (tagged with
        │                   group + test class), per thread via ThreadLocal
        │
        ├── (test runs)   → ApiLogger / ResponseValidator write normal
        │                   SLF4J log lines as usual; logback-test.xml
        │                   also forwards every "com.apinexus" log line to
        │                   ExtentLogAppender, which appends it to the
        │                   CURRENTLY RUNNING test's report node
        │
        ├── onTestSuccess → marks the node PASS (green)
        ├── onTestFailure → marks the node FAIL (red) + full stack trace
        ├── onTestSkipped → marks the node SKIP (orange) + reason
        │
        └── onFinish      → flush() writes the HTML file to reports/
```

The result: opening any test's node in the report shows the **exact same
request/response/assertion detail** that appears in the console and in
`logs/apinexus-test.log` — just organised per test, filterable, and
color-coded, instead of one long chronological file.

### Report features

- **Dashboard** — pass/fail/skip counts, pie chart, total duration
- **Category filter** — filter by TestNG group (`crud`, `auth`, `mock`, ...)
- **Author filter** — filter by test class name
- **Per-test timeline** — every `ApiLogger` line (▶ REQUEST, ◀ RESPONSE,
  ✔ PASS, STEP n) shown in order, with timestamps
- **Failure detail** — full exception message and stack trace, collapsible
- **System info panel** — Java version, OS, mock mode, generation timestamp

### Key classes

| File | Role |
|---|---|
| `src/test/java/com/apinexus/report/ExtentReportManager.java` | Singleton owning the `ExtentReports` instance; `ThreadLocal<ExtentTest>` tracks "current test per thread" (needed because `data-provider-thread-count=3` runs some data-driven rows concurrently) |
| `src/test/java/com/apinexus/report/ExtentTestListener.java` | `ITestListener` implementation registered in `testng.xml`; drives the manager on every test lifecycle event |
| `src/test/java/com/apinexus/report/ExtentLogAppender.java` | Custom Logback appender; forwards every `com.apinexus.*` log event into the currently running test's report node |

### Generating the classic Surefire HTML report too

```bash
mvn surefire-report:report
open target/site/surefire-report.html
```

---

## 16. Continuous Integration (GitHub Actions)

A workflow at `.github/workflows/api-tests.yml` runs the full suite
automatically.

### Triggers

| Trigger | Behaviour |
|---|---|
| Push to `main` | Runs the entire suite |
| Pull request targeting `main` | Runs the entire suite |
| Manual (`workflow_dispatch`) | Optionally restrict to one group via the `groups` input (e.g. `mock`, `crud`) |

### What the workflow does

1. Checks out the code
2. Sets up JDK 11 (Temurin) with Maven dependency caching
3. Runs `mvn test` (env var `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` skips
   downloading browser binaries Playwright never actually uses for API testing)
4. Uploads three artifacts regardless of pass/fail (`if: always()`):
   - `surefire-reports` — raw XML/TXT results
   - `extent-html-report` — the detailed ExtentReports HTML file
   - `test-logs` — the full `logs/apinexus-test.log`
5. Writes a Markdown table of pass/fail/error/skip counts per test class
   directly into the GitHub Actions run's **Job Summary** tab — visible
   without downloading anything
6. Fails the job explicitly if any test failed, so branch protection and PR
   checks behave correctly

### Running it manually with a specific group

1. Go to the repo's **Actions** tab → **ApiNexus API Test Suite** → **Run workflow**
2. Enter a group name in the `groups` input (e.g. `auth`) or leave as `all`
3. Click **Run workflow**

### Downloading the detailed report from a CI run

1. Open the workflow run in the **Actions** tab
2. Scroll to **Artifacts** at the bottom of the run summary
3. Download `extent-html-report` and open the `.html` file in any browser

---

## 17. Troubleshooting

### `Address already in use` on port 8089
Another process is using port 8089. Change `mock.server.port` in
`config.properties` to any free port (e.g. `8091`) and re-run.

### `Cannot find config.properties on the classpath`
Ensure `src/test/resources/` is marked as a **Test Resources** folder in Eclipse:
right-click the folder → `Build Path → Use as Source Folder`.
In Maven this is automatic.

### `PlaywrightException: net::ERR_NAME_NOT_RESOLVED`
You have no internet access. The live-API tests (CRUD, auth, chaining, schema,
pagination) require connectivity to `jsonplaceholder.typicode.com` and
`reqres.in`. The WireMock-only tests (mock, upload, errors in part) will still
pass offline.

### `Schema validation FAILED: $.email: does not match the email format`
The live API returned an email address that doesn't satisfy the validator's
definition of RFC-5322 format. Check `user_schema.json` — you can change
`"format": "email"` to `"type": "string"` to relax the constraint.

### Tests are slow / timing out
Increase `http.read.timeout` in `config.properties`. The default is 10000ms
(10 seconds). For performance tests, increase `performance.max.response.time.ms`.

### WireMock stub not matching
Enable WireMock's own debug log in `logback-test.xml`:
```xml
<logger name="com.github.tomakehurst.wiremock" level="DEBUG"/>
```
WireMock will log exactly which stubs were evaluated and why they matched or didn't.
