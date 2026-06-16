# ApiNexus — Interview Question Bank

A comprehensive set of interview questions (with answers) built around this
project. Use it to prepare for SDET / QA Automation / API Testing interviews
where you may be asked to walk through a real framework you built — every
answer below references actual files and design decisions in this repo so
you can speak to them with specifics, not just theory.

---

## Table of Contents

1. [Project Overview & "Walk Me Through It" Questions](#1-project-overview--walk-me-through-it-questions)
2. [Playwright for Java Questions](#2-playwright-for-java-questions)
3. [API Testing Fundamentals](#3-api-testing-fundamentals)
4. [WireMock & Mocking Questions](#4-wiremock--mocking-questions)
5. [TestNG Questions](#5-testng-questions)
6. [JSON Schema Validation Questions](#6-json-schema-validation-questions)
7. [Authentication Testing Questions](#7-authentication-testing-questions)
8. [Error Handling, Status Codes & Resilience](#8-error-handling-status-codes--resilience)
9. [Pagination & Filtering Questions](#9-pagination--filtering-questions)
10. [Performance Testing Questions](#10-performance-testing-questions)
11. [Reporting & Logging Questions](#11-reporting--logging-questions)
12. [CI/CD & DevOps Questions](#12-cicd--devops-questions)
13. [Java, OOP & Design Pattern Questions](#13-java-oop--design-pattern-questions)
14. [Configuration & Environment Questions](#14-configuration--environment-questions)
15. [Scenario-Based / Troubleshooting Questions](#15-scenario-based--troubleshooting-questions)
16. [Behavioral & Process Questions](#16-behavioral--process-questions)
17. [Rapid-Fire Round](#17-rapid-fire-round)

---

## 1. Project Overview & "Walk Me Through It" Questions

**Q1. Walk me through what this project does and why you built it this way.**
> ApiNexus is a pure API-testing framework — no browser, no UI — built with
> Playwright for Java's `APIRequestContext` as the HTTP client, TestNG as the
> runner, WireMock as an embedded mock server, Jackson for JSON
> (de)serialisation, the networknt library for JSON Schema validation, and
> Logback for logging. It tests two live public APIs (JSONPlaceholder for
> CRUD/chaining, and a mocked ReqRes contract for auth/pagination) plus an
> entirely local WireMock server for error simulation, file upload, and
> performance scenarios. The goal was full coverage of API-testing
> categories — CRUD, auth, schema, errors, pagination, mocking, performance,
> chaining, upload — in one cohesive, heavily-commented codebase.

**Q2. Why Playwright instead of RestAssured or raw HttpClient for API testing?**
> Three reasons: (1) If a team is already using Playwright for UI/E2E tests,
> reusing the same library for API tests means one less dependency and one
> shared mental model. (2) `APIRequestContext` has a clean, fluent API
> (`request.get()`, `.post()`, `RequestOptions.create().setHeader(...)`)
> that's comparable to RestAssured's fluency. (3) Built-in support for
> multipart uploads via `FormData`/`FilePayload` without extra dependencies.
> The tradeoff: Playwright's Java API documentation for `APIRequestContext`
> is thinner than RestAssured's, and `Playwright.create()` by default tries
> to download browser binaries even though we never launch a browser — we
> work around that with `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`.

**Q3. What's the directory layout and why is it split that way?**
> `src/main/java` holds reusable, non-test-specific code: `ConfigManager`,
> `ApiLogger`, `ResponseValidator`, and the model POJOs (`Post`, `User`).
> `src/test/java` holds everything that depends on test-only libraries
> (TestNG, WireMock): the actual `@Test` classes, `MockServerManager` /
> `RemoteMockServerManager` (WireMock is test-scoped in `pom.xml`), and the
> reporting package. This split matters because Maven enforces it — putting
> a WireMock import in `src/main` would fail to compile in a consumer
> project that only pulls in the main jar, since `wiremock-jre8-standalone`
> has `<scope>test</scope>`.

**Q4. How many test cases does the suite have and how are they organised?**
> 61 test cases (including data-driven variants) across 9 TestNG groups:
> crud, auth, schema, errors, pagination, mock, performance, chaining,
> upload. Each group maps to one test class and one `<test>` block in
> `testng.xml`. Full step-by-step specs for every case live in
> [TEST_CASES.md](TEST_CASES.md).

**Q5. What real APIs does this project depend on, and what happens if they're unreachable?**
> `jsonplaceholder.typicode.com` for CRUD/schema/chaining/pagination(posts)
> — free, no auth, write operations are faked server-side. Originally
> `reqres.in` was used live for auth/pagination(users)/schema(user), but
> reqres.in started requiring an `x-api-key` header on every request (a
> breaking change to a previously free API), so those scenarios are now
> fully simulated via WireMock (`stubReqResLogin`, `stubReqResSingleUser`,
> `stubReqResUsersPage`) — same response contract, but offline and immune to
> third-party policy changes. If JSONPlaceholder itself is unreachable, the
> crud/schema(post)/chaining/pagination(posts) groups fail with connection
> errors, but mock/upload/most-of-errors continue to pass since they never
> leave localhost.

---

## 2. Playwright for Java Questions

**Q6. What is `APIRequestContext` and how does it differ from a `Page`?**
> `APIRequestContext` is Playwright's pure HTTP client — created via
> `playwright.request().newContext(...)` — with no browser process behind
> it. A `Page` belongs to a `Browser`/`BrowserContext` and renders a DOM.
> We only ever use `APIRequestContext` in this project; `Playwright.create()`
> is still called because it's the entry point to the `request()` factory,
> but no browser is ever launched.

**Q7. How do you set a base URL so you don't repeat the full URL in every test?**
> `APIRequest.NewContextOptions().setBaseURL(baseUrl)` when creating the
> context in `BaseApiTest.suiteSetUp()`. Any relative path passed to
> `request.get("/posts")` gets the base URL prepended. In practice this
> project builds full URLs explicitly (`baseUrl + "/posts/1"`) in most
> tests for log clarity, but the context-level base URL is still set and
> available.

**Q8. How does Playwright handle request timeouts, and where is that configured here?**
> `APIRequest.NewContextOptions().setTimeout(ms)` sets the default timeout
> for every request made through that context. Here it's wired to
> `http.read.timeout` from `config.properties` (10000ms default) inside
> `BaseApiTest.suiteSetUp()`.

**Q9. How do you send a JSON POST body with Playwright?**
> `RequestOptions.create().setHeader("Content-Type", "application/json").setData(jsonString)`,
> passed as the second argument to `request.post(url, options)`. The body
> is built with Jackson's `ObjectMapper.writeValueAsString()` rather than
> hand-written strings, so it's guaranteed syntactically valid.

**Q10. How do you upload a file with Playwright's Java API?**
> Via `FormData.create().append("file", new FilePayload(fileName, mimeType, byteArray))`,
> then `RequestOptions.create().setMultipart(formData)`. Playwright
> automatically sets the `Content-Type: multipart/form-data; boundary=...`
> header — you must NOT set it manually or you'll get a malformed boundary.
> See `FileUploadTest.testBasicFileUpload()`. Note the API takes a
> `FilePayload` object, not a raw `(name, bytes, filename)` triple — that
> overload doesn't exist and was a real compile error I hit while building
> this.

**Q11. How do you send a request with a method that has no dedicated convenience method, like OPTIONS?**
> `request.fetch(url, RequestOptions.create().setMethod("OPTIONS"))`. See
> `MockServerTest.testCorsPreFlightRequest()`.

**Q12. Is `APIRequestContext` thread-safe? How do you know?**
> Yes — `PerformanceTest.testConcurrentCalls()` fires 5 simultaneous
> `request.get()` calls from a fixed thread pool against the same shared
> static `request` context and all 5 succeed with correct, independent
> responses. Playwright documents the request context as safe for
> concurrent use from multiple threads.

**Q13. What's the lifecycle cost of `Playwright.create()` and how does this project amortise it?**
> Creating a `Playwright` instance spins up a Node.js-based driver process.
> Doing this once per test would be slow and wasteful. `BaseApiTest` creates
> it exactly once in `@BeforeSuite` and disposes it once in `@AfterSuite`,
> shared via a `protected static Playwright playwright` field across every
> test class in the suite.

---

## 3. API Testing Fundamentals

**Q14. What's the difference between testing an API's contract vs. testing its behaviour?**
> Contract testing (here: `ResponseValidator.assertJsonSchema()`) verifies
> the STRUCTURE of the response — field names, types, required-ness, format
> constraints — independent of the specific values returned. Behavioural
> testing (`assertStatusCode`, `assertJsonField`) verifies that a SPECIFIC
> input produces a SPECIFIC output. A response can pass schema validation
> while still being behaviourally wrong (e.g. returning the wrong user's
> data with a perfectly valid shape) — which is why this project does both
> (see `SchemaValidationTest` vs the rest of the suite).

**Q15. Why test error responses as thoroughly as success responses?**
> Clients make decisions based on status codes: retry on 503, don't retry
> on 400, redirect to login on 401. A server that returns the WRONG code
> (200 for a failure, or 500 instead of 400 for bad input) breaks client
> logic silently. `ErrorHandlingTest` covers 404, 400, 500, 405, 429, and a
> raw network fault — each verifying not just the status code but that the
> accompanying signal (error body, `Allow` header, `Retry-After` header) is
> present and correct.

**Q16. What's the difference between PUT and PATCH, and how does this project demonstrate it?**
> PUT replaces the entire resource — fields omitted from the request should
> be cleared/defaulted. PATCH applies a partial update — only the supplied
> fields change. `CrudOperationsTest.testFullUpdatePost()` (PUT) sends `id`,
> `title`, `body`, and `userId` together; `testPartialUpdatePost()` (PATCH)
> sends ONLY `title` and asserts the response reflects just that change.

**Q17. How do you verify a DELETE actually worked, given many fake/test APIs don't persist state?**
> Ideally: DELETE returns 200/204, then a subsequent GET on the same
> resource returns 404. JSONPlaceholder doesn't persist deletes (it always
> serves the original data), so `CrudOperationsTest.testDeletePost()` only
> asserts the DELETE call itself returns 200, and the lifecycle test
> (`ApiChainingTest.testFullResourceLifecycle()`) explicitly documents that
> limitation in a log line rather than asserting a 404 that the live API
> can never produce.

**Q18. What is idempotency, and which HTTP methods are supposed to be idempotent?**
> An idempotent operation produces the same end-state no matter how many
> times it's repeated. GET, PUT, DELETE, and HEAD are specified as
> idempotent; POST and PATCH are not. This project doesn't explicitly test
> idempotency (e.g. calling DELETE twice and confirming the second call
> doesn't error), but it would be a natural extension — calling
> `request.delete()` twice on the same mock resource and asserting both
> calls return non-5xx.

**Q19. How do you test that a filter/query parameter is actually applied server-side and not just decorative?**
> Don't just check the status code — iterate every item in the response and
> assert it independently satisfies the filter predicate.
> `PaginationFilteringTest.testFilterByUserId()` does exactly this: it
> fetches `/posts?userId=1` and then loops every returned post asserting
> `userId == 1`, failing loudly (logging each violation) if the server ever
> leaks a post from another user.

**Q20. What's the value of testing CORS pre-flight (OPTIONS) requests in an API suite that has no browser?**
> Even without a real browser, CORS correctness matters because the API
> will eventually be called FROM a browser-based frontend on a different
> origin. `MockServerTest.testCorsPreFlightRequest()` verifies the server
> would respond correctly to a pre-flight `OPTIONS` request (status 204,
> `Access-Control-Allow-Origin`/`-Methods` headers) — catching a
> misconfiguration before a frontend team ever hits it.

---

## 4. WireMock & Mocking Questions

**Q21. Why use a mock server at all instead of only testing against real APIs?**
> Four reasons demonstrated in this project: (1) Isolation — tests don't
> depend on a live service being up. (2) Control — you can trigger
> conditions that are impractical on a live system: 500 errors, rate
> limiting, network resets, arbitrary delays. (3) Determinism — the same
> stub always returns the same response, eliminating flakiness from
> external data changes. (4) Coverage of scenarios a live API simply
> doesn't expose, e.g. `Fault.CONNECTION_RESET_BY_PEER` in
> `ErrorHandlingTest.testNetworkFaultHandling()`.

**Q22. How does WireMock decide which stub matches when two stubs could both match the same request?**
> By default, when multiple stubs have the same (default) priority, the
> MOST RECENTLY REGISTERED stub wins — not the more specific one. This is a
> real bug I hit in this project: `stubBearerAuthProtected()` registers a
> specific 200-stub (correct token) and then a catch-all 401-stub. Because
> neither had an explicit priority, the catch-all (registered second) won
> even for requests WITH the correct token, so every "valid auth" test was
> actually getting 401. The fix was adding `.atPriority(1)` to the specific
> stub and `.atPriority(5)` to the catch-all — lower number = higher
> priority in WireMock, evaluated first.

**Q23. How do you simulate a multi-step/stateful flow with WireMock?**
> WireMock Scenarios. `MockServerManager.stubStatefulOrder()` registers
> three stubs on the same URL, each tied to a `scenarioName`: the first
> requires `Scenario.STARTED` and transitions to `"PROCESSING"` after
> firing; the second requires `"PROCESSING"` and transitions to
> `"COMPLETE"`; the third requires `"COMPLETE"` and is terminal. Three
> successive calls to the same URL walk through PENDING → PROCESSING →
> COMPLETE — see `MockServerTest.testStatefulPollingFlow()`.

**Q24. How do you simulate a slow backend to test client timeout handling?**
> `.willReturn(aResponse().withFixedDelay(milliseconds))`. The test
> `MockServerTest.testSlowEndpointResponse()` injects a 500ms delay and
> asserts the measured elapsed time is at least 500ms (proving the delay
> was honoured) while still being under the SLA + delay window.

**Q25. How do you simulate a connection-level failure (not just an HTTP error status)?**
> `.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))`.
> This makes WireMock abruptly close the TCP connection instead of sending
> any HTTP response, which causes Playwright's client to throw an
> exception rather than return an `APIResponse`. The test wraps the call in
> try/catch and asserts an exception with a non-blank message was thrown —
> proving the client doesn't crash uncaught on network failure.

**Q26. How do you verify the CLIENT made a request correctly, not just that the response looked right?**
> `WireMock.verify(count, requestPattern)`, e.g.
> `WireMock.verify(2, getRequestedFor(urlEqualTo("/api/verify-me")))` in
> `MockServerTest.testWireMockRequestVerification()`. This is fundamentally
> different from asserting on the response — it inspects WireMock's
> recorded request log to confirm the exact number and shape of calls made.

**Q27. What's the difference between matching on `urlEqualTo` and `urlPathEqualTo`?**
> `urlEqualTo` matches the full URL including the query string exactly.
> `urlPathEqualTo` matches only the path, letting you add separate
> `.withQueryParam(name, matcher)` constraints — necessary when you want to
> match SOME query params but not require an exact full query string (e.g.
> `stubReqResUsersPage` matches `page` always but `per_page` only when the
> caller explicitly wants that constraint).

**Q28. How would you make WireMock stubs available to a remote team or CI pipeline, not just locally?**
> This project supports three options (see [DEPLOYMENT.md](DEPLOYMENT.md)):
> WireMock Cloud (hosted, free tier, 5-minute setup), self-hosted on Fly.io
> via Docker (`Dockerfile` + `fly.toml`), or `docker-compose.yml` on any
> VPS. The switch is one config change:
> `mock.mode=remote` + `mock.remote.url=<url>` in `config.properties`. A
> `MockServerStrategy` interface lets `RemoteMockServerManager` register the
> exact same stubs via WireMock's `/__admin/mappings` REST API instead of
> the embedded Java DSL — no test code changes either way.

**Q29. How do you avoid stub pollution between tests when running many tests against one shared WireMock instance?**
> `mockServer.resetAllStubs()` called from an `@AfterMethod`, present in
> `MockServerTest`, `FileUploadTest`, `AuthenticationTest`,
> `SchemaValidationTest`, `ErrorHandlingTest`, and `PaginationFilteringTest`.
> Without this, a stub registered by one test could still be live (and
> potentially conflict) when a later test in the same class runs, since the
> WireMock server itself is a single suite-scoped static instance shared by
> every test class.

**Q30. What does `withRequestBody(containing(...))` actually match against, and what are its limits?**
> It does a raw substring match against the request body bytes — it does
> NOT parse JSON. That's why `stubReqResLogin` can match a deliberately
> malformed JSON body in `ErrorHandlingTest.testMalformedRequestBody()`: as
> long as the broken string doesn't happen to contain the exact
> `"email":"..."` substring the success stub requires, it correctly falls
> through to the 400 catch-all regardless of whether the body is valid JSON.

---

## 5. TestNG Questions

**Q31. Why TestNG over JUnit 5 for this project?**
> TestNG's built-in `@DataProvider` (parameterised tests without extra
> dependencies), native support for `<suite>`/`<test>` XML configuration
> with group filtering, and first-class `ITestListener` hooks made it a
> natural fit for a suite organised around 9 distinct groups that need to
> be run independently (`mvn test -Dgroups=auth`). JUnit 5 can do similar
> things with `@ParameterizedTest`/`@Tag`, but TestNG's suite XML gives more
> declarative control without annotations scattered everywhere.

**Q32. What's the difference between `@BeforeMethod` and `@BeforeSuite` here, and why does it matter?**
> `@BeforeSuite` (in `BaseApiTest.suiteSetUp()`) runs exactly ONCE before
> any test in the entire suite — used for expensive, shared setup:
> Playwright initialisation, starting WireMock, reading config.
> `@BeforeMethod`/`@AfterMethod` run before/after EVERY test method — used
> in this project just for `mockServer.resetAllStubs()`, since that needs
> to happen between every individual test, not once per suite.

**Q33. How does `@DataProvider` work, and where is it used here?**
> A method annotated `@DataProvider(name="...")` returns an `Object[][]`;
> each inner array becomes one set of arguments for a test method declared
> with `@Test(dataProvider="...")`. TestNG invokes the test method once per
> row. Used in `CrudOperationsTest.testGetPostDataDriven()` (4 rows: post
> IDs 1, 5, 10, 9999 with expected codes) and
> `PaginationFilteringTest.testPerPageBoundaries()` (3 rows: per_page 1, 6, 12).

**Q34. Do `@DataProvider` rows run in parallel by default? Why does this matter for stub-based tests?**
> No — rows run SEQUENTIALLY unless the `@DataProvider` explicitly sets
> `parallel = true`. `testng.xml` sets `data-provider-thread-count="3"`, but
> that setting only takes effect for providers that opt into `parallel=true`
> — neither provider in this project does, so it's safe to register a
> WireMock stub and reset it in `@AfterMethod` between rows without a race
> condition. I verified this explicitly while debugging
> `testPerPageBoundaries` to make sure resetting stubs between rows
> couldn't wipe out a stub a concurrently-running row still needed.

**Q35. How do you run only a subset of the suite from the command line?**
> `mvn test -Dgroups=auth` runs only tests tagged with the `auth` TestNG
> group. `mvn test -Dtest=AuthenticationTest` runs one class.
> `mvn test -Dtest=AuthenticationTest#testValidLogin` runs one method.
> Group filtering is the most-used form here since `testng.xml` organises
> every class into exactly one group.

**Q36. What is an `ITestListener` and how is one used in this project?**
> An interface with callback methods (`onTestStart`, `onTestSuccess`,
> `onTestFailure`, `onTestSkipped`, `onFinish`) that TestNG invokes
> automatically at each point in the test lifecycle, without any code in
> the test classes themselves. `CustomReportListener` implements this
> interface and is registered declaratively in `testng.xml`
> (`<listeners><listener class-name="com.apinexus.report.CustomReportListener"/></listeners>`)
> to build the HTML dashboard data model as tests run.

**Q37. How would you make a test depend on another test's success (e.g. ensure login runs before a token-using test)?**
> TestNG supports `@Test(dependsOnMethods = {"testValidLogin"})`. This
> project deliberately avoids that pattern — each test is self-contained
> and registers its own login stub or performs its own login call inline
> (see `AuthenticationTest.testLoginThenAccessProtectedResource()`, which
> does both steps in ONE method) so tests can run in any order or in
> isolation without hidden ordering dependencies.

**Q38. What does `preserve-order="true"` do in `testng.xml`, and is method order also guaranteed?**
> It preserves the ORDER OF `<class>` ELEMENTS within a `<test>` block —
> i.e., class-level ordering. It does NOT guarantee the order of `@Test`
> methods WITHIN a class; TestNG's default method ordering is not
> documented as strictly declaration-order, so tests should never assume
> method B runs after method A unless an explicit dependency or priority is
> set.

---

## 6. JSON Schema Validation Questions

**Q39. What's the practical difference between asserting individual field values and validating against a JSON Schema?**
> Field assertions (`assertJsonField(response, "id", "1")`) check that ONE
> known input produces ONE known output — they tell you nothing about
> fields you didn't think to check. Schema validation
> (`assertJsonSchema(response, "schemas/post_schema.json")`) checks the
> ENTIRE shape of the response against a contract: every required field is
> present, every field has the right type, string formats are valid, and
> (with `additionalProperties: false`) no unexpected extra fields exist.
> It's a structural regression detector that field assertions can't be.

**Q40. What does `additionalProperties: false` protect against, concretely?**
> It fails validation if the response contains ANY field not explicitly
> listed in the schema. This catches breaking API changes early — if the
> backend team adds a new field tomorrow that old clients don't expect, or
> renames a field (which shows up as one new field plus one missing
> field), the schema test fails immediately instead of silently passing.

**Q41. How do you test that your schema validator itself actually works, rather than trusting it blindly?**
> A negative/meta test: `SchemaValidationTest.testSchemaNegativeValidation()`
> registers a WireMock stub that deliberately returns a broken response
> (missing the required `data`/`support` wrapper), then asserts that
> `assertJsonSchema()` throws an `AssertionError`. If the validator
> incorrectly accepted the broken response, this test would fail — meaning
> a passing schema test on the rest of the suite isn't giving false
> confidence.

**Q42. What draft of JSON Schema does this project use, and why does the draft version matter?**
> Draft-07, set explicitly via
> `JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)` in
> `ResponseValidator.assertJsonSchema()`. The draft version matters because
> schema keyword support differs between drafts (e.g. `if`/`then`/`else`
> conditionals were added in 2019-09); mismatching the factory's version
> against the schema file's declared `$schema` can cause keywords to be
> silently ignored rather than erroring.

**Q43. How would you validate that an email field is not just a string but actually looks like an email?**
> `"format": "email"` in the schema (used on `data.email` in
> `user_schema.json`). The networknt validator checks the string against
> the email format rule in addition to confirming it's a string at all.
> Same idea for `"format": "uri"` on the avatar/support URL fields.

---

## 7. Authentication Testing Questions

**Q44. What auth mechanisms does this project cover, and what's the real-world use case for each?**
> Bearer token (RFC 6750, `Authorization: Bearer <token>`) — the dominant
> pattern for modern session/JWT auth. Basic auth (RFC 7617, base64
> `user:pass`) — simple admin/internal tooling APIs. API key in a custom
> header (`X-API-Key`) — common for third-party developer APIs. API key as
> a query parameter — legacy/simple integrations, less secure since it
> leaks into logs/browser history. A full login→token→protected-call chain
> — the realistic end-to-end flow used by web/mobile clients.

**Q45. Why test the "missing/invalid auth" case as carefully as the "valid auth" case?**
> It's a security boundary check, not just a functional one.
> `AuthenticationTest.testMissingBearerToken()` confirms the server returns
> 401 (not 200, and not silently serving the protected data) when no
> credentials are presented — verifying the API doesn't accidentally leak
> protected resources to unauthenticated callers.

**Q46. How do you Base64-encode Basic auth credentials correctly in Java, and what's a common mistake?**
> `Base64.getEncoder().encodeToString((username + ":" + password).getBytes(UTF_8))`,
> prefixed with `"Basic "`. The common mistake is forgetting the explicit
> `UTF_8` charset (relying on the platform default, which can differ across
> environments) or encoding username and password as two separate base64
> strings instead of one combined `user:pass` string.

**Q47. Walk me through the multi-step auth flow test in this project.**
> `testLoginThenAccessProtectedResource()`: Step 1 registers a mocked login
> stub and POSTs credentials, extracting the `token` field from the JSON
> response via Jackson. Step 2 registers a SEPARATE WireMock stub
> (`stubBearerAuthProtected`) that specifically requires `Authorization:
> Bearer {that exact token}`, then makes the protected call using the token
> extracted in step 1. This proves the token returned by login is actually
> the one accepted downstream — not just two independently-passing
> unrelated assertions.

**Q48. What happened when reqres.in (the live API this auth suite originally used) changed its policy, and how did you respond?**
> reqres.in began requiring an `x-api-key` header on every request,
> breaking ~10 previously-passing tests across auth, schema, and pagination
> overnight with zero code changes on our side — a textbook example of why
> hard external dependencies in a test suite are risky. The fix: added
> `stubReqResLogin()`, `stubReqResSingleUser()`, and `stubReqResUsersPage()`
> to `MockServerManager`/`RemoteMockServerManager`, reproducing reqres.in's
> exact response contract (same field names, same status codes, same
> pagination math) via WireMock, and repointed the affected tests from
> `authBaseUrl` to `mockBaseUrl`. Same test intent, zero external dependency.

---

## 8. Error Handling, Status Codes & Resilience

**Q49. Why might a server return 422 instead of 400, and does this project distinguish them?**
> 400 Bad Request generally signals malformed syntax (the request couldn't
> even be parsed); 422 Unprocessable Entity signals the request was
> syntactically valid but semantically invalid (e.g. a well-formed JSON
> body with a value that fails business validation). This project's
> `testMalformedRequestBody()` deliberately accepts EITHER as correct
> (`status >= 400 && status < 500`) because reasonable APIs differ on which
> one they use for malformed JSON specifically.

**Q50. What must a 429 Too Many Requests response include to be considered well-formed, and why?**
> A `Retry-After` header (RFC 6585) telling the client how many seconds to
> wait before retrying. `ErrorHandlingTest.testRateLimitResponse()` asserts
> not just the 429 status but that `Retry-After` is present and parses to a
> positive integer — without it, a client has no principled way to decide
> when to retry.

**Q51. What must a 405 Method Not Allowed response include?**
> An `Allow` header listing the HTTP methods that ARE supported on that
> URL. `testMethodNotAllowed()` asserts the header is present and contains
> `"GET"` — a client (or a developer debugging in the browser network tab)
> can read this to immediately know what to try instead.

**Q52. How do you test that your OWN test code handles a network-level failure gracefully, not just an HTTP error?**
> `testNetworkFaultHandling()` triggers `Fault.CONNECTION_RESET_BY_PEER` and
> wraps the call in try/catch, asserting that an exception with a non-blank
> message is thrown rather than letting it propagate as an unhandled
> crash. This tests the CLIENT's resilience, which is just as important as
> testing the server's responses.

**Q53. Why does this project assert response bodies are non-empty even for 500 errors?**
> Because even failure responses should give the caller SOMETHING to act
> on or log. `testInternalServerError()` asserts the 500 body is not blank,
> and separately checks the `Content-Type` is one of the two realistic
> options (`text/plain` for a raw stack-trace-style error, or
> `application/json` for a structured one) — modeling the fact that 500s in
> the wild often aren't as cleanly formatted as 4xx errors.

---

## 9. Pagination & Filtering Questions

**Q54. What invariant should always hold between `total`, `per_page`, and `total_pages` in a paginated response?**
> `total_pages == ceil(total / per_page)`. `testDefaultPaginationPage1()`
> asserts this explicitly using `Math.ceil((double) total / perPage)` —
> catching an off-by-one bug in the server's pagination math (a classic
> source of bugs: rounding down instead of up when `total` isn't evenly
> divisible by `per_page`).

**Q55. How do you verify a server doesn't silently ignore pagination and just dump everything onto every page?**
> Request two different page numbers and assert the response actually
> differs / reflects the requested page —
> `testPage2()` asserts the response's `page` field literally equals `2`,
> not just that SOME data came back. The more thorough check is
> `testTotalCountConsistency()`, which paginates through every page,
> summing `data.size()` per page, and asserts the sum equals the reported
> `total` — if the server returned duplicate or missing items across pages,
> this sum would be wrong.

**Q56. What's a sensible way to handle a request for a page number beyond the last page?**
> Either an empty `data` array with 200 (preferred — predictable,
> no special-casing for clients), or 404. `testPageBeyondTotal()`
> deliberately accepts either, asserting only that it's NEVER a 500 and
> NEVER the wrong/wrapped-around page.

**Q57. Boundary value testing — what boundaries does `testPerPageBoundaries` cover and why those specific values?**
> `per_page=1` (the smallest valid page size — exactly one item, tests the
> lower boundary), `per_page=6` (the "normal" default), and `per_page=12`
> (equal to the total dataset size — every item fits on one page, tests the
> upper boundary where `total_pages` should become 1). Boundary testing
> like this catches off-by-one errors that mid-range values would never
> expose.

---

## 10. Performance Testing Questions

**Q58. What's the difference between what this project calls "performance testing" and real load testing with JMeter/Gatling/k6?**
> This project's `PerformanceTest` does lightweight SANITY checks within
> the same test run: single-call SLA, sequential-call mean/max, a small
> 5-thread concurrency burst, and P95 latency over 20 samples. It is NOT a
> substitute for dedicated load testing tools, which simulate hundreds or
> thousands of concurrent users, ramp-up/ramp-down patterns, and sustained
> load over minutes/hours. The goal here is to catch an obvious regression
> (a change that doubles response time) in the same CI run as functional
> tests, not to characterise the system's capacity limits.

**Q59. Why measure P95 instead of just the average response time?**
> The average can hide a bad tail — if 19 calls take 50ms and one takes
> 5000ms, the average looks fine (~300ms) but one in 20 users had a
> terrible experience. P95 ("95% of requests complete within N ms") is the
> industry-standard SLA metric because it captures realistic worst-case
> behaviour while still ignoring the single most extreme outlier.
> `testP95ResponseTime()` sorts 20 samples and reads the value at index
> `floor(0.95 * 20)`.

**Q60. How do you test concurrency correctness, not just speed, with a small thread pool?**
> `testConcurrentCalls()` uses a fixed thread pool of 5 threads, each
> calling a DIFFERENT post ID concurrently via `ExecutorService.invokeAll()`,
> and asserts each thread's response status individually (throwing an
> `AssertionError` inside the `Callable` if any thread got a non-200,
> which `Future.get()` re-throws). This catches connection-pool exhaustion
> or race conditions that sequential calls would never expose, even at
> small scale.

**Q61. How do you prove your timing measurement is actually accurate, rather than trusting `System.currentTimeMillis()` blindly?**
> `testFastVsSlowEndpoint()` compares a 0-delay mock endpoint against a
> 300ms-delay mock endpoint and asserts the measured DIFFERENCE is at least
> 250ms (injected delay minus a 50ms tolerance for measurement jitter). If
> the difference were near zero, that would indicate the timer wasn't
> actually wrapping the real network call.

---

## 11. Reporting & Logging Questions

**Q62. Why build a custom HTML reporter instead of using an existing library like ExtentReports or Allure?**
> This project actually started with ExtentReports and switched. The
> reason: ExtentReports' default look didn't fit, and Allure requires an
> extra CLI step (`allure:serve`/`allure:report`) to render — it's not a
> single auto-generated file. The custom renderer
> (`HtmlReportRenderer`) produces ONE self-contained HTML file (inline CSS,
> vanilla JS, no CDN dependency) styled after Cluecumber's clean
> card/donut-chart look, with full control over the design and zero new
> runtime dependencies beyond what the project already had.

**Q63. How does the custom report capture per-test log detail without modifying every test method?**
> A custom Logback appender, `ReportLogAppender`, is registered in
> `logback-test.xml` against the `com.apinexus` logger. Every log line any
> test already produces via `ApiLogger`/`ResponseValidator` is ALSO routed
> through this appender, which looks up "the test currently running on this
> thread" via `CustomReportListener.getCurrentTest()` (a `ThreadLocal`) and
> appends the line to that test's `TestResultModel`. No test class needed
> to change — the existing logging calls feed both the console/file AND
> the HTML report automatically.

**Q64. Why `ThreadLocal<TestResultModel>` instead of a single shared "current test" field?**
> Because `data-provider-thread-count="3"` in `testng.xml` means rows from
> a `parallel=true` data provider COULD run on different threads
> simultaneously (even though, in this project's case, neither provider
> actually opts into that). `ThreadLocal` is the correct defensive design
> regardless: each thread gets its own private pointer, so log lines are
> never misattributed to the wrong test even under concurrency.

**Q65. How does the report visually distinguish pass/fail/skip, and how is that data captured?**
> `CustomReportListener` implements `onTestSuccess`/`onTestFailure`/
> `onTestSkipped`, each setting the corresponding `TestResultModel.status`
> field ("PASS"/"FAIL"/"SKIP") and, on failure, capturing the exception
> message and full stack trace. `HtmlReportRenderer` then renders a
> colour-coded badge (green/red/amber) per test row and a CSS-only
> conic-gradient donut chart for the overall pass/fail/skip split — computed
> directly from the aggregate counts, no charting library needed.

**Q66. Why does the suite produce THREE different reports, and when would you use each?**
> (1) The custom HTML dashboard (`reports/ApiNexus-Test-Report-*.html`) —
> for humans, drilling into request/response detail per test. (2) Raw
> Surefire XML (`target/surefire-reports/*.xml`) — for machines/CI tools
> that need a standard, parseable format (the GitHub Actions workflow
> parses this to build its Job Summary table). (3) The classic Surefire
> HTML report (`mvn surefire-report:report`) — a simple fallback table view
> with no custom code involved, useful as a sanity check that the custom
> renderer's numbers agree with a trusted standard tool.

**Q67. What information does `ApiLogger` deliberately mask, and why?**
> Authorization header values, X-API-Key, Cookie, and any header containing
> "token" — `maskSensitiveHeader()` shows only the first 6 characters
> followed by `[MASKED]`. The header NAME is still logged (so you can
> confirm it was sent) but the secret VALUE is not — preventing tokens/keys
> from leaking into log files or, worse, into the HTML report artifact that
> might get uploaded to CI or shared with a team.

---

## 12. CI/CD & DevOps Questions

**Q68. What triggers the GitHub Actions workflow, and why include a manual trigger with a group filter?**
> Push to `main`, pull requests targeting `main`, and `workflow_dispatch`
> (manual) with an optional `groups` input. The manual trigger with a group
> filter lets you re-run just ONE suite (e.g. `mock`) without waiting for
> the full ~25-second run — useful when iterating on a single area without
> needing a new commit to trigger CI.

**Q69. Why does the workflow set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`?**
> Because `Playwright.create()` would otherwise attempt to download
> Chromium/Firefox/WebKit binaries on every fresh runner, even though this
> project never launches a browser — only `APIRequestContext` is used. This
> both speeds up CI and avoids spurious failures if the binary CDN has a
> transient outage (which I actually hit while debugging locally — the
> sandbox had no internet access for the download, masking the REAL test
> failures underneath).

**Q70. How does the workflow surface results without requiring a teammate to download an artifact?**
> It writes a Markdown table (per-class tests/failures/errors/skipped,
> parsed from the Surefire XML with `grep -o`) directly into
> `$GITHUB_STEP_SUMMARY`, which GitHub renders on the workflow run page —
> visible immediately, no download needed. Artifacts (Surefire XML, the
> HTML dashboard, the log file) are uploaded too, `if: always()`, for
> deeper investigation when something fails.

**Q71. Why does the workflow use `continue-on-error: true` on the test step and then explicitly fail the job in a later step?**
> Because steps after a failed step are skipped by default unless they
> have `if: always()`. We WANT the artifact-upload and summary steps to run
> even when tests fail (that's exactly when you need them most), so the
> test step is allowed to "succeed" at the step level via
> `continue-on-error`, and a final step explicitly checks
> `steps.run_tests.outcome == 'failure'` and calls `exit 1` — making the
> overall JOB fail (so branch protection/PR checks work correctly) only
> after the reporting steps have already run.

**Q72. What's the tradeoff of using Maven's dependency cache in `actions/setup-java`?**
> Faster builds on unchanged dependencies (cache keyed on `pom.xml`'s
> hash) — but if you change a dependency version without bumping anything
> else recognisable, you rely on the cache key correctly invalidating; a
> stale cache could theoretically serve an old artifact. In practice
> `setup-java`'s built-in Maven caching handles the key correctly via the
> pom.xml hash, so this is a minor theoretical concern, not a practical one.

---

## 13. Java, OOP & Design Pattern Questions

**Q73. Where is the Singleton pattern used in this project, and why?**
> `ConfigManager.getInstance()` — double-checked locking ensures
> `config.properties` is read from disk exactly once across the whole test
> run regardless of how many test classes call it, and is thread-safe if
> tests ever run in parallel.

**Q74. Where is the Strategy pattern used, and what problem does it solve here?**
> `MockServerStrategy` is an interface implemented by both
> `MockServerManager` (local embedded WireMock) and `RemoteMockServerManager`
> (remote WireMock via its Admin REST API). `BaseApiTest` holds a field of
> the INTERFACE type, and `suiteSetUp()` picks the concrete implementation
> at runtime based on `config.properties`' `mock.mode` value. This means
> switching from local to a cloud-hosted mock server is a one-line config
> change with ZERO test code changes — the whole point of the Strategy
> pattern.

**Q75. Why are `ApiLogger` and `ResponseValidator` designed as static utility classes instead of instantiated objects?**
> They hold no state of their own (other than a shared `ObjectMapper`/
> logger instance, which is itself stateless from the caller's
> perspective) — they're pure functions over their arguments. Making them
> static utility classes (private constructor, all-static methods) avoids
> unnecessary object instantiation in every test method and makes the call
> sites read cleanly: `ApiLogger.logRequest(...)` rather than
> `new ApiLogger().logRequest(...)`.

**Q76. Why does `BaseApiTest` use `protected static` fields rather than instance fields?**
> Because `@BeforeSuite`/`@AfterSuite` run ONCE for the whole suite, but
> TestNG instantiates a NEW instance of each test class for its own run.
> If `request`/`playwright`/`mockServer` were instance fields, every test
> class would need its own separate Playwright/WireMock setup — exactly the
> expensive repetition `@BeforeSuite` is meant to avoid. `static` fields are
> shared across all instances and survive for the life of the JVM/suite.

**Q77. What's the purpose of `@JsonIgnoreProperties(ignoreUnknown = true)` on the `Post`/`User` POJOs?**
> It tells Jackson to silently skip any JSON key in the response that
> doesn't have a matching Java field, rather than throwing
> `UnrecognizedPropertyException`. This makes the POJOs resilient to the
> API adding new fields in the future without breaking deserialization —
> a small but important piece of API-evolution tolerance.

**Q78. Why does `User.java` use `@JsonProperty("first_name")` instead of relying on Jackson's default field-name matching?**
> Because the JSON uses snake_case (`first_name`) while Java convention is
> camelCase (`firstName`) — Jackson's default matching is exact-name, so
> without the explicit `@JsonProperty` annotation bridging the naming
> convention gap, the field would deserialize as `null`.

**Q79. Why is `MockServerManager` in `src/test/java` rather than `src/main/java`, given it's a "manager" class like `ConfigManager`?**
> Because it imports WireMock classes, and `wiremock-jre8-standalone` is
> declared with `<scope>test</scope>` in `pom.xml` — putting a WireMock
> import in `src/main` would fail to compile, since main-scope code can
> only use main-scope (or wider) dependencies, not test-scope ones. This
> was an actual compile error I hit and had to fix by moving the file.

---

## 14. Configuration & Environment Questions

**Q80. How does this project avoid hardcoding URLs, ports, and timeouts throughout the test code?**
> A single `config.properties` file read once by `ConfigManager`, exposing
> typed getters (`getBaseUrl()`, `getMockServerPort()`, `getMaxResponseTimeMs()`,
> etc.). Every test class accesses these through the shared `config` field
> on `BaseApiTest` rather than referencing literals — changing an SLA or a
> port is a one-line edit, not a search-and-replace across dozens of files.

**Q81. What happens if a required key is missing from `config.properties`?**
> `ConfigManager.getString()` throws `IllegalArgumentException` immediately
> with the missing key's name, rather than returning `null` and letting a
> `NullPointerException` surface confusingly deep inside a test later. This
> is a deliberate fail-fast design choice.

**Q82. How would you run the same suite against a staging environment instead of the hardcoded URLs?**
> Override the relevant property via a Maven system property, e.g.
> `mvn test -Dbase.url=https://staging.example.com` IF `ConfigManager` were
> wired to check system properties before falling back to the file (it
> currently reads only from the properties file via
> `Properties.load(InputStream)`). As built today, you'd edit
> `config.properties` directly or maintain a second properties file and
> swap it on the classpath — a reasonable follow-up would be layering
> `System.getProperty(key, fileValue)` into `ConfigManager` for true CLI
> overridability.

**Q83. Why is `mock.remote.api.key` left blank by default in `config.properties`, and is it safe to commit?**
> It's blank because most self-hosted WireMock setups (Docker, Fly.io) run
> with no Admin API authentication by default. It's safe to commit as a
> blank placeholder, but a REAL key (for WireMock Cloud) should never be
> committed — it should be supplied via an environment variable or a
> gitignored local override file in a real deployment.

---

## 15. Scenario-Based / Troubleshooting Questions

**Q84. A test that uses `mockServer.stubBearerAuthProtected()` is returning 401 even though you're sending the correct token. How would you debug it?**
> This literally happened in this project. Steps: (1) Confirm the token
> string matches EXACTLY (case, whitespace) between the stub registration
> and the request header. (2) Check whether a SECOND, more general stub on
> the same URL could be matching instead — list all registered stubs via
> `GET /__admin/mappings` if testing against a real WireMock instance, or
> add temporary debug logging. (3) Check stub PRIORITY — if multiple stubs
> match equally, WireMock's tie-break is "most recently registered wins,"
> NOT "most specific wins." That was the actual root cause here: the
> catch-all 401 stub, registered after the specific 200 stub with no
> explicit priority, was winning. Fix: add `.atPriority(1)` to the specific
> stub and a higher number (lower priority) to the catch-all.

**Q85. All your live-API tests suddenly start failing in CI with no code changes. What's your triage process?**
> First, check whether it's isolated to one external dependency or
> everything (run `mvn test -Dgroups=crud` vs `-Dgroups=auth` separately —
> if `crud` against JSONPlaceholder still passes but `auth`/`schema`
> against the other live API fails, it's likely THAT service, not your
> environment). Second, manually `curl` the failing endpoint to see the
> actual raw response/status — this is exactly how I discovered reqres.in
> had started requiring an `x-api-key` header, turning what looked like a
> flaky CI run into a clear, reproducible external API contract change.
> Third, decide: get the new required credential, or remove the hard
> dependency by mocking the endpoint (this project chose the latter).

**Q86. A test passes locally but fails in CI. What are the likely causes given this project's architecture?**
> (1) No internet access in the CI runner for the live-API groups (unlikely
> on GitHub Actions, but a real issue in sandboxed/offline environments —
> I hit this directly while developing, which is why
> `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` exists). (2) A port collision on
> `mock.server.port` (8089) if another process is already using it on the
> CI runner. (3) Timing-sensitive performance assertions being tighter than
> a slower/shared CI runner can reliably meet — the SLA in
> `config.properties` may need to be more generous in CI than locally.

**Q87. How would you add a brand-new test scenario for, say, testing webhook delivery retries?**
> Add a new package `com.apinexus.tests.webhooks`, create the test class
> extending `BaseApiTest`, register it with a new TestNG group `"webhooks"`
> in `testng.xml` as its own `<test>` block, and add corresponding stub
> methods to `MockServerStrategy` (implemented in BOTH
> `MockServerManager` and `RemoteMockServerManager`, since the interface
> requires it) — e.g. a stub that returns 500 on the first call and 200 on
> a retry, modeled the same way `stubStatefulOrder()` models multi-call
> state transitions. Finally, document the new test cases in
> `TEST_CASES.md` following the existing TC-<PREFIX>-<NN> convention.

**Q88. The HTML report shows 0 captured log lines for a test that you know logs heavily. What would you check?**
> Whether `ReportLogAppender` is actually attached to the `com.apinexus`
> logger in `logback-test.xml` (a misconfigured `<appender-ref>` would
> silently produce no output, not an error). Whether the log call happened
> OUTSIDE the test's lifecycle window — e.g. in `@BeforeSuite`, before
> `CustomReportListener.onTestStart()` ever set the `ThreadLocal` for that
> thread, in which case `ReportLogAppender.append()` correctly returns
> early since there's no "current test" to attach to.

---

## 16. Behavioral & Process Questions

**Q89. Tell me about a bug you found in your OWN framework, not in the system under test.**
> The WireMock stub-priority bug (Q22/Q84): `stubBearerAuthProtected()`
> looked correct, had a comment confidently asserting "WireMock resolves
> stubs in declaration order, most recently declared first... the more
> specific stub takes priority" — which was simply WRONG about WireMock's
> actual default behaviour. It went unnoticed because the `auth` TestNG
> group had never actually been executed end-to-end until I ran the full
> suite for verification. The lesson: a passing compile and a
> plausible-sounding comment are not the same as a test that has actually
> RUN.

**Q90. Describe a time an external dependency change broke your tests, and how you responded.**
> reqres.in started requiring an API key with no warning (Q48). My
> response: confirmed the scope of impact precisely (curl the endpoint
> directly, then run each TestNG group in isolation to see exactly which
> ones were affected vs. unaffected) before touching any code, then
> migrated the affected scenarios to WireMock stubs that reproduce the
> exact same contract — preserving full test coverage and intent while
> removing a fragile, externally-controlled dependency entirely.

**Q91. How do you decide what belongs in a live-API test vs. a mocked test in a framework like this?**
> Live calls are valuable for testing your client's REAL integration
> against a REAL implementation of the documented contract (catching
> things like subtle serialization differences a mock might not
> reproduce). Mocks are valuable for anything you can't reliably or safely
> trigger live: errors, rate limits, network faults, slow responses, and
> anything where the live service's policies might change unexpectedly
> (the exact failure mode reqres.in exposed). This project leans on live
> calls for CRUD/chaining against JSONPlaceholder (stable, free, designed
> for testing) and mocks for everything adversarial or stateful.

**Q92. How would you explain the value of this project to a non-technical stakeholder in 30 seconds?**
> "This automatically checks, every time code changes, that our APIs still
> behave correctly — not just the happy path, but also what happens when
> something goes wrong: bad input, missing auth, slow responses, server
> errors. It runs in under a minute, produces a clear pass/fail report
> anyone can open in a browser, and catches problems before they reach
> customers instead of after."

---

## 17. Rapid-Fire Round

Quick one-liners — good for a fast-paced screening round.

| Q | A |
|---|---|
| What HTTP client does this project use? | Playwright's `APIRequestContext` |
| What test runner? | TestNG |
| What mock server? | WireMock (embedded or remote via Admin API) |
| What JSON library? | Jackson (`jackson-databind`) |
| What schema validator? | networknt `json-schema-validator`, draft-07 |
| What logging framework? | SLF4J + Logback |
| How many test groups? | 9 (crud, auth, schema, errors, pagination, mock, performance, chaining, upload) |
| How many total test cases? | 61 |
| Default mock server port? | 8089 |
| Default performance SLA? | 2000ms |
| What CI platform? | GitHub Actions |
| What design pattern switches local/remote mocking? | Strategy pattern (`MockServerStrategy`) |
| What pattern does `ConfigManager` use? | Singleton (double-checked locking) |
| What annotation creates parameterised tests in TestNG? | `@DataProvider` |
| What HTTP status requires a `Retry-After` header? | 429 |
| What HTTP status requires an `Allow` header? | 405 |
| What WireMock fault simulates a dropped connection? | `Fault.CONNECTION_RESET_BY_PEER` |
| Lower or higher number = higher priority in WireMock? | Lower number = higher priority |
| What env var skips Playwright's browser download? | `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` |
| Where are JSON schemas stored? | `src/test/resources/schemas/` |
| What's the report output directory? | `reports/` |
| Single-file or multi-file HTML report? | Single self-contained file |
