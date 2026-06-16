package com.apinexus.report;

import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * CustomReportListener — TestNG lifecycle hook that builds the SuiteReportModel
 * and triggers HtmlReportRenderer once the suite finishes.
 *
 * HOW TESTNG DISCOVERS THIS LISTENER
 * ───────────────────────────────────
 * Registered declaratively in testng.xml:
 *   <listeners>
 *       <listener class-name="com.apinexus.report.CustomReportListener"/>
 *   </listeners>
 *
 * WHY ThreadLocal<TestResultModel>?
 * data-provider-thread-count="3" in testng.xml means rows from the same
 * @DataProvider can run concurrently on different threads (see
 * CrudOperationsTest#testGetPostDataDriven). ThreadLocal gives each thread
 * its own "current test" pointer so ReportLogAppender always attaches log
 * lines to the correct test, even under concurrency.
 */
public class CustomReportListener implements ITestListener {

    // One model for the entire JVM run (TestNG/Surefire forks one JVM per
    // suite execution, so a static field is the right scope here).
    private static final SuiteReportModel SUITE_MODEL = new SuiteReportModel("ApiNexus API Test Suite");

    // Per-thread pointer to the test currently executing on that thread.
    private static final ThreadLocal<TestResultModel> CURRENT = new ThreadLocal<>();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Read by ReportLogAppender on every log event to find out which test
     * (if any) is currently running on the calling thread.
     */
    public static TestResultModel getCurrentTest() {
        return CURRENT.get();
    }

    // ── Test start ────────────────────────────────────────────────────────

    @Override
    public void onTestStart(ITestResult result) {
        String testName = buildTestName(result);
        String description = result.getMethod().getDescription();
        String className = result.getTestClass().getRealClass().getSimpleName();

        String[] groups = result.getMethod().getGroups();
        String primaryGroup = (groups != null && groups.length > 0) ? groups[0] : null;

        TestResultModel model = new TestResultModel(testName, description, className, primaryGroup);
        model.setStartTime(LocalTime.now().format(TIME_FMT));

        // Register the model in the suite NOW (not at finish) so a test that
        // crashes the JVM mid-run still appears in the partial report.
        SUITE_MODEL.addResult(model);
        CURRENT.set(model);
    }

    // ── Test outcomes ─────────────────────────────────────────────────────

    @Override
    public void onTestSuccess(ITestResult result) {
        TestResultModel model = CURRENT.get();
        if (model != null) {
            model.setStatus("PASS");
            model.setDurationMs(result.getEndMillis() - result.getStartMillis());
        }
        CURRENT.remove();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        TestResultModel model = CURRENT.get();
        if (model != null) {
            model.setStatus("FAIL");
            model.setDurationMs(result.getEndMillis() - result.getStartMillis());

            Throwable t = result.getThrowable();
            if (t != null) {
                model.setFailureMessage(t.getMessage() != null ? t.getMessage() : t.toString());
                model.setStackTrace(stackTraceToString(t));
            }
        }
        CURRENT.remove();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        TestResultModel model = CURRENT.get();
        if (model == null) {
            // A test can be skipped before onTestStart fires (e.g. a failed
            // @BeforeMethod) — create the node now so it still appears.
            String testName = buildTestName(result);
            String className = result.getTestClass().getRealClass().getSimpleName();
            String[] groups = result.getMethod().getGroups();
            String primaryGroup = (groups != null && groups.length > 0) ? groups[0] : null;
            model = new TestResultModel(testName, result.getMethod().getDescription(), className, primaryGroup);
            SUITE_MODEL.addResult(model);
        }
        model.setStatus("SKIP");
        String reason = result.getThrowable() != null ? result.getThrowable().getMessage() : "No reason captured";
        model.setFailureMessage(reason);
        CURRENT.remove();
    }

    // ── Suite end ────────────────────────────────────────────────────────

    @Override
    public void onFinish(ITestContext context) {
        // Render the HTML dashboard to disk. Safe to call multiple times
        // across multiple <test> blocks in testng.xml — each call simply
        // re-renders the full accumulated model, so the file always reflects
        // everything that has run so far.
        HtmlReportRenderer.render(SUITE_MODEL);

        System.out.println(
                "════════════════════════════════════════════════════════════════\n" +
                "  ApiNexus: " + context.getName() + " finished — " +
                "Passed: "  + context.getPassedTests().size()  + ", " +
                "Failed: "  + context.getFailedTests().size()  + ", " +
                "Skipped: " + context.getSkippedTests().size() + "\n" +
                "  Detailed HTML report written under reports/\n" +
                "════════════════════════════════════════════════════════════════"
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String buildTestName(ITestResult result) {
        Object[] params = result.getParameters();
        String baseName = result.getMethod().getMethodName();
        if (params == null || params.length == 0) {
            return baseName;
        }
        String paramString = Arrays.stream(params)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return baseName + " [" + paramString + "]";
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
