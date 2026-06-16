package com.apinexus.report;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

/**
 * ExtentReportManager — Singleton that owns the single ExtentReports instance
 * for the whole test run and tracks "which test is currently executing" on a
 * per-thread basis.
 *
 * WHY A SINGLETON?
 * Only one ExtentReports object should exist per test run — it owns the
 * output HTML file and accumulates every test node into it. If multiple
 * instances were created, each would generate its own (incomplete) report.
 *
 * WHY ThreadLocal<ExtentTest>?
 * testng.xml sets data-provider-thread-count="3", which means rows from the
 * same @DataProvider can run concurrently on different threads (see
 * CrudOperationsTest#testGetPostDataDriven and
 * PaginationFilteringTest#testPerPageBoundaries). If we stored "the current
 * test" in a single shared field, two threads would overwrite each other's
 * reference and log lines could end up attached to the wrong test node.
 * ThreadLocal gives each thread its own private "current test" slot.
 *
 * HOW IT IS WIRED UP
 * ───────────────────
 *   ExtentTestListener  (TestNG ITestListener)
 *       onTestStart    → ExtentReportManager.startTest(name, description)
 *       onTestSuccess  → ExtentReportManager.getCurrentTest().pass(...)
 *       onTestFailure  → ExtentReportManager.getCurrentTest().fail(...)
 *       onFinish       → ExtentReportManager.flush()
 *
 *   ExtentLogAppender   (Logback appender, registered in logback-test.xml)
 *       append(event)  → ExtentReportManager.getCurrentTest().info(...)
 *       (streams every ApiLogger line into the currently running test node)
 */
public final class ExtentReportManager {

    private static final Logger log = LoggerFactory.getLogger(ExtentReportManager.class);

    // The single ExtentReports instance for this JVM / test run.
    // volatile so the double-checked-locking initialisation in getInstance()
    // is visible correctly across threads.
    private static volatile ExtentReports extentReports;

    // Per-thread "currently executing test" pointer.
    // Set in startTest(), read by both the listener and the log appender.
    private static final ThreadLocal<ExtentTest> currentTest = new ThreadLocal<>();

    // Directory (relative to the Maven working directory) where reports are written.
    private static final String REPORT_DIR = "reports";

    // Private constructor — this class is never instantiated; all access is static.
    private ExtentReportManager() {}

    // ── Initialisation (double-checked locking, same pattern as ConfigManager) ──

    /**
     * Returns the shared ExtentReports instance, creating and configuring it
     * on the very first call. Safe to call from multiple threads.
     */
    public static ExtentReports getInstance() {
        if (extentReports == null) {
            synchronized (ExtentReportManager.class) {
                if (extentReports == null) {
                    extentReports = createReport();
                }
            }
        }
        return extentReports;
    }

    /**
     * Builds a fresh ExtentReports instance backed by an ExtentSparkReporter
     * (a single self-contained HTML file with a Bootstrap-based dashboard).
     */
    private static ExtentReports createReport() {
        // Ensure the reports/ directory exists before ExtentSparkReporter
        // tries to write inside it.
        File dir = new File(REPORT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Timestamp the report file name so successive CI runs don't overwrite
        // each other — useful when reports/ is archived as a build artifact.
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String reportPath = REPORT_DIR + "/ApiNexus-Test-Report-" + timestamp + ".html";

        ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);

        // DARK theme renders better on most screens for long log dumps
        // (request/response bodies) than the default light theme.
        spark.config().setTheme(Theme.DARK);
        spark.config().setDocumentTitle("ApiNexus API Test Report");
        spark.config().setReportName("ApiNexus — Playwright Java API Test Suite");
        // Timestamp format shown against every log line inside the report.
        spark.config().setTimeStampFormat("yyyy-MM-dd HH:mm:ss.SSS");

        ExtentReports extent = new ExtentReports();
        extent.attachReporter(spark);

        // ── System / environment info panel ───────────────────────────────
        // Shown at the top of the report — useful when comparing two runs
        // (e.g. "did this fail because of a different Java version?").
        extent.setSystemInfo("Framework",     "ApiNexus — Playwright Java");
        extent.setSystemInfo("Java Version",  System.getProperty("java.version"));
        extent.setSystemInfo("OS",            System.getProperty("os.name") + " " + System.getProperty("os.version"));
        extent.setSystemInfo("User",          System.getProperty("user.name"));
        extent.setSystemInfo("Mock Mode",     System.getProperty("mock.mode", "local (default)"));
        extent.setSystemInfo("Report Generated", timestamp);

        log.info("ExtentReports initialised. Output file: {}", reportPath);
        return extent;
    }

    // ── Per-test lifecycle ───────────────────────────────────────────────

    /**
     * Starts a new test node in the report and stores it as "the current test"
     * for THIS thread. Called from ExtentTestListener#onTestStart.
     *
     * @param testName    Display name shown in the report (method + parameters)
     * @param description The @Test(description=...) string from the test method
     * @return The newly created ExtentTest node (also retrievable via getCurrentTest())
     */
    public static ExtentTest startTest(String testName, String description) {
        ExtentTest test = getInstance().createTest(testName, description);
        currentTest.set(test);
        return test;
    }

    /**
     * Returns the ExtentTest node for the test currently running on THIS thread,
     * or null if no test has been started on this thread yet (e.g. the call
     * comes from a background thread not tracked by TestNG).
     */
    public static ExtentTest getCurrentTest() {
        return currentTest.get();
    }

    /**
     * Clears the current-thread pointer once a test has finished.
     * Prevents a stale reference from leaking into the next test that happens
     * to reuse the same thread from TestNG's thread pool.
     */
    public static void clearCurrentTest() {
        currentTest.remove();
    }

    // ── Finalisation ─────────────────────────────────────────────────────

    /**
     * Writes the accumulated report to disk. MUST be called exactly once,
     * after every test has finished (ExtentTestListener#onFinish).
     * Calling createTest() after flush() will not appear in the saved file.
     */
    public static void flush() {
        if (extentReports != null) {
            extentReports.flush();
            log.info("ExtentReports HTML report written to disk under '{}/'", REPORT_DIR);
        }
    }
}
