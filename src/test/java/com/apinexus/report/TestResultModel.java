package com.apinexus.report;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TestResultModel — Plain data holder for everything the HTML report needs
 * to know about ONE test method invocation.
 *
 * One instance is created per `@Test` execution (including each row of a
 * `@DataProvider`), populated as the test runs, and finally rendered into
 * one row of the Cluecumber-style HTML dashboard.
 *
 * THREAD SAFETY
 * Each instance belongs to exactly one test invocation and is only ever
 * touched by the single thread executing that invocation (TestNG never runs
 * the same @Test invocation on two threads at once). logLines uses
 * CopyOnWriteArrayList purely as a defensive measure — cheap for the small
 * number of lines a single test produces, and avoids any doubt if a
 * @BeforeMethod/@AfterMethod happens to log from a different point.
 */
public class TestResultModel {

    // ── Identity ─────────────────────────────────────────────────────────

    /** Method name plus @DataProvider parameters, e.g. "testGetPostDataDriven [1, 200]" */
    private String testName;

    /** The @Test(description = "...") string, e.g. "TC-CRUD-01: GET all posts..." */
    private String description;

    /** Simple class name, e.g. "CrudOperationsTest" — used to group rows in the report */
    private String className;

    /** First TestNG group the method belongs to, e.g. "crud" — used for filter chips */
    private String group;

    // ── Outcome ──────────────────────────────────────────────────────────

    /** PASS, FAIL, or SKIP — set once the test finishes */
    private String status = "RUNNING";

    /** Wall-clock duration of the test method in milliseconds */
    private long durationMs;

    /** Human-readable start time, e.g. "14:32:07.418" */
    private String startTime;

    // ── Failure detail (only populated when status == FAIL) ────────────────

    private String failureMessage;
    private String stackTrace;

    // ── Captured log lines (every ApiLogger / ResponseValidator line) ──────

    private final List<String> logLines = new CopyOnWriteArrayList<>();

    // ── Constructors ─────────────────────────────────────────────────────

    public TestResultModel(String testName, String description, String className, String group) {
        this.testName = testName;
        this.description = description == null ? "" : description;
        this.className = className;
        this.group = group == null ? "general" : group;
    }

    // ── Mutators used by CustomReportListener ───────────────────────────

    public void addLogLine(String line) {
        logLines.add(line);
    }

    public void setStatus(String status) { this.status = status; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    // ── Getters used by HtmlReportRenderer ──────────────────────────────

    public String getTestName() { return testName; }
    public String getDescription() { return description; }
    public String getClassName() { return className; }
    public String getGroup() { return group; }
    public String getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
    public String getStartTime() { return startTime; }
    public String getFailureMessage() { return failureMessage; }
    public String getStackTrace() { return stackTrace; }
    public List<String> getLogLines() { return logLines; }
}
