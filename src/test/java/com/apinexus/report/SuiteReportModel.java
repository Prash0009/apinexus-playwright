package com.apinexus.report;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SuiteReportModel — Holds every TestResultModel collected during one
 * `mvn test` run, plus suite-level metadata (name, generation timestamp).
 *
 * This is the single root object that HtmlReportRenderer consumes to
 * produce the final HTML file. CustomReportListener is the only writer;
 * it is a singleton-per-JVM-run (static field), matching how TestNG runs
 * one suite per JVM fork.
 *
 * THREAD SAFETY
 * Multiple test threads can call onTestSuccess/onTestFailure/onTestSkipped
 * concurrently (data-provider-thread-count=3 in testng.xml), each adding
 * its own TestResultModel to `results`. CopyOnWriteArrayList makes
 * concurrent adds safe without explicit synchronization.
 */
public class SuiteReportModel {

    private final String suiteName;
    private final LocalDateTime generatedAt;
    private final List<TestResultModel> results = new CopyOnWriteArrayList<>();

    public SuiteReportModel(String suiteName) {
        this.suiteName = suiteName;
        this.generatedAt = LocalDateTime.now();
    }

    public void addResult(TestResultModel result) {
        results.add(result);
    }

    public String getSuiteName() { return suiteName; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public List<TestResultModel> getResults() { return results; }

    // ── Aggregate counts used by the dashboard summary cards ───────────────

    public long countByStatus(String status) {
        return results.stream().filter(r -> status.equals(r.getStatus())).count();
    }

    public int getTotalCount() { return results.size(); }
    public long getPassedCount() { return countByStatus("PASS"); }
    public long getFailedCount() { return countByStatus("FAIL"); }
    public long getSkippedCount() { return countByStatus("SKIP"); }

    public long getTotalDurationMs() {
        return results.stream().mapToLong(TestResultModel::getDurationMs).sum();
    }
}
