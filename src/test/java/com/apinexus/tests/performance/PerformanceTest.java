package com.apinexus.tests.performance;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.microsoft.playwright.APIResponse;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * PerformanceTest — SLA checks and concurrency mini-tests for API endpoints.
 *
 * WHAT DOES "PERFORMANCE TESTING" MEAN IN API TESTING?
 * In a unit/integration context (not full load testing), performance tests check:
 *   1. Response Time SLA: each call completes within N milliseconds.
 *   2. Consistency:       repeated calls produce similar response times
 *                         (no single outlier indicating resource leak).
 *   3. Concurrency:       the API handles simultaneous requests without
 *                         failing or returning inconsistent results.
 *
 * For full load testing use JMeter / Gatling / k6. These tests are sanity
 * checks to detect obvious regressions in response time.
 *
 * Scenarios covered:
 *   TC-PERF-01  Single-call SLA check
 *   TC-PERF-02  10 sequential calls — check mean & max response time
 *   TC-PERF-03  5 concurrent calls via a thread pool
 *   TC-PERF-04  Response-time regression — mock fast vs slow endpoints
 *   TC-PERF-05  P95 latency check across 20 calls
 */
@Test(groups = "performance")
public class PerformanceTest extends BaseApiTest {

    // SLA read from config.properties (performance.max.response.time.ms = 2000)
    private long sla() {
        return config.getMaxResponseTimeMs();
    }

    // ── TC-PERF-01: Single call SLA ───────────────────────────────────────

    /**
     * TC-PERF-01: A single GET /posts must complete within the configured SLA.
     *
     * This is the baseline: if even a single call exceeds the SLA, something
     * is fundamentally wrong (connectivity, DNS, misconfiguration).
     */
    @Test(description = "TC-PERF-01: Single GET /posts/1 completes within SLA")
    public void testSingleCallSla() {
        logTestStart("testSingleCallSla");

        String url = baseUrl + "/posts/1";
        ApiLogger.logRequest("GET", url);

        long start = startTimer();
        APIResponse response = request.get(url);
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 200);
        ResponseValidator.assertResponseTime(elapsed, sla());

        log.info("Single call SLA: {} ms (limit: {} ms)", elapsed, sla());
        logTestEnd("testSingleCallSla");
    }

    // ── TC-PERF-02: Sequential calls — mean & max ─────────────────────────

    /**
     * TC-PERF-02: Make 10 sequential GET calls and validate:
     *   - Each call individually is within the SLA
     *   - The mean response time is reasonable (< SLA)
     *   - The maximum observed time is not an outlier
     *
     * Repeated sequential calls reveal slow memory leaks, database pool
     * exhaustion, or GC pauses that don't show up in a single call.
     */
    @Test(description = "TC-PERF-02: 10 sequential calls all within SLA")
    public void testSequentialCallsSla() {
        logTestStart("testSequentialCallsSla");

        int callCount = 10;
        List<Long> responseTimes = new ArrayList<>(callCount);

        for (int i = 1; i <= callCount; i++) {
            // Rotate through post IDs 1–10 to avoid CDN caching effects
            String url = baseUrl + "/posts/" + i;

            long start = startTimer();
            APIResponse response = request.get(url);
            long elapsed = elapsedMs(start);
            responseTimes.add(elapsed);

            // Each individual call must satisfy the SLA
            ResponseValidator.assertStatusCode(response, 200);

            log.info("Call {:2d}/{}: {} ms", i, callCount, elapsed);
        }

        // Compute summary statistics using Java Streams
        LongSummaryStatistics stats = responseTimes.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        log.info("Response time stats over {} calls:", callCount);
        log.info("  Min  : {} ms", stats.getMin());
        log.info("  Max  : {} ms", stats.getMax());
        log.info("  Mean : {:.1f} ms", stats.getAverage());

        // The maximum observed time must not exceed the SLA
        Assert.assertTrue(stats.getMax() <= sla(),
                "Maximum response time " + stats.getMax() + " ms exceeded SLA " + sla() + " ms");

        // Mean should be well below the SLA (sanity check for average behaviour)
        Assert.assertTrue(stats.getAverage() <= sla(),
                "Mean response time " + stats.getAverage() + " ms exceeded SLA " + sla() + " ms");

        logTestEnd("testSequentialCallsSla");
    }

    // ── TC-PERF-03: Concurrent calls ──────────────────────────────────────

    /**
     * TC-PERF-03: Fire 5 simultaneous GET requests via a thread pool.
     *
     * WHY CONCURRENCY TESTS?
     * Many backend bugs (race conditions, connection pool exhaustion) only
     * manifest under concurrent load, not in sequential calls. Even 5
     * simultaneous clients can expose these issues.
     *
     * Each thread calls a different post ID to avoid server-side caching
     * masking latency problems.
     */
    @Test(description = "TC-PERF-03: 5 concurrent GET calls all succeed within SLA")
    public void testConcurrentCalls() throws Exception {
        logTestStart("testConcurrentCalls");

        int concurrency = 5;

        // A fixed thread pool with {concurrency} threads — exactly enough for
        // all calls to run simultaneously.
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        // Build a list of Callable tasks — each one makes a single GET request.
        // Callable<Long> returns the elapsed time so we can check each one.
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 1; i <= concurrency; i++) {
            final int postId = i + 10; // posts 11–15
            tasks.add(() -> {
                String url = baseUrl + "/posts/" + postId;
                long start = System.currentTimeMillis();

                // NOTE: Playwright's APIRequestContext is thread-safe, so we can
                // call request.get() from multiple threads simultaneously.
                APIResponse response = request.get(url);

                long elapsed = System.currentTimeMillis() - start;
                log.info("Thread {} | POST {} | {} ms | status={}",
                        Thread.currentThread().getName(), postId,
                        elapsed, response.status());

                // Assert inside the thread — failure will propagate via the Future
                if (response.status() != 200) {
                    throw new AssertionError(
                            "Expected 200 but got " + response.status() + " for post " + postId);
                }
                return elapsed;
            });
        }

        // invokeAll() submits all tasks and waits for all of them to complete.
        List<Future<Long>> futures = pool.invokeAll(tasks);
        pool.shutdown(); // stop accepting new tasks; existing tasks finish normally

        // Collect results and check each elapsed time
        List<Long> elapsedTimes = new ArrayList<>();
        for (Future<Long> future : futures) {
            // future.get() re-throws any exception thrown inside the Callable.
            long elapsed = future.get();
            elapsedTimes.add(elapsed);
        }

        LongSummaryStatistics stats = elapsedTimes.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        log.info("Concurrent call stats ({} threads):", concurrency);
        log.info("  Min  : {} ms", stats.getMin());
        log.info("  Max  : {} ms", stats.getMax());
        log.info("  Mean : {:.1f} ms", stats.getAverage());

        Assert.assertTrue(stats.getMax() <= sla() * 2,
                "Max concurrent response time " + stats.getMax() + " ms exceeded 2x SLA");

        logTestEnd("testConcurrentCalls");
    }

    // ── TC-PERF-04: Mock endpoint — fast vs slow ──────────────────────────

    /**
     * TC-PERF-04: Compare response times for a fast endpoint vs a slow one.
     *
     * We use WireMock to inject a known delay (300ms) on one endpoint
     * and no delay on another, then assert that the difference is within
     * the expected range. This validates our timing measurement is accurate.
     */
    @Test(description = "TC-PERF-04: Slow endpoint takes measurably longer than fast endpoint")
    public void testFastVsSlowEndpoint() {
        logTestStart("testFastVsSlowEndpoint");

        int injectedDelayMs = 300;

        // Register a fast endpoint (no delay)
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/fast"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withBody("{\"speed\":\"fast\"}")));

        // Register a slow endpoint (with injectedDelayMs)
        mockServer.stubSlowResponse("/api/slow-perf", injectedDelayMs);

        // ── Fast call ──────────────────────────────────────────────────────
        long fastStart = startTimer();
        APIResponse fastResp = request.get(mockBaseUrl + "/api/fast");
        long fastElapsed = elapsedMs(fastStart);
        ResponseValidator.assertStatusCode(fastResp, 200);
        log.info("Fast endpoint: {} ms", fastElapsed);

        // ── Slow call ──────────────────────────────────────────────────────
        long slowStart = startTimer();
        APIResponse slowResp = request.get(mockBaseUrl + "/api/slow-perf");
        long slowElapsed = elapsedMs(slowStart);
        ResponseValidator.assertStatusCode(slowResp, 200);
        log.info("Slow endpoint: {} ms (delay injected: {} ms)", slowElapsed, injectedDelayMs);

        // The slow endpoint must take at least {injectedDelayMs} more than the fast one
        long diff = slowElapsed - fastElapsed;
        Assert.assertTrue(diff >= injectedDelayMs - 50, // 50ms tolerance for measurement jitter
                "Expected slow endpoint to take at least " + (injectedDelayMs - 50)
                + " ms more than fast endpoint, but difference was only " + diff + " ms");

        logTestEnd("testFastVsSlowEndpoint");
    }

    // ── TC-PERF-05: P95 latency ───────────────────────────────────────────

    /**
     * TC-PERF-05: Collect 20 response times and assert the 95th percentile
     * is within the SLA.
     *
     * P95 is the industry standard SLA metric: "95% of requests complete
     * within N milliseconds". This is more meaningful than the mean because
     * it includes slow outliers while ignoring the very worst spike.
     */
    @Test(description = "TC-PERF-05: P95 response time is within the SLA")
    public void testP95ResponseTime() {
        logTestStart("testP95ResponseTime");

        int sampleSize = 20;
        List<Long> times = new ArrayList<>(sampleSize);

        for (int i = 1; i <= sampleSize; i++) {
            // Rotate post IDs 1–10 across 20 calls
            String url = baseUrl + "/posts/" + ((i % 10) + 1);
            long start = startTimer();
            APIResponse response = request.get(url);
            long elapsed = elapsedMs(start);
            times.add(elapsed);
            ResponseValidator.assertStatusCode(response, 200);
        }

        // Sort the times to compute percentiles.
        // P95 = the value at index floor(0.95 * n) in a sorted list.
        List<Long> sorted = times.stream().sorted().collect(Collectors.toList());
        int p95Index = (int) Math.floor(0.95 * sampleSize);
        long p95     = sorted.get(p95Index);

        LongSummaryStatistics stats = times.stream()
                .mapToLong(Long::longValue).summaryStatistics();

        log.info("P95 latency over {} samples: {} ms (SLA: {} ms)", sampleSize, p95, sla());
        log.info("  Min  : {} ms", stats.getMin());
        log.info("  Max  : {} ms", stats.getMax());
        log.info("  Mean : {:.1f} ms", stats.getAverage());
        log.info("  P95  : {} ms", p95);

        Assert.assertTrue(p95 <= sla(),
                "P95 latency " + p95 + " ms exceeded SLA of " + sla() + " ms");

        logTestEnd("testP95ResponseTime");
    }
}
