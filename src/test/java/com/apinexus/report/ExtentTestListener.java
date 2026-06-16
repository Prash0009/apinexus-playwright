package com.apinexus.report;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.aventstack.extentreports.markuputils.ExtentColor;
import com.aventstack.extentreports.markuputils.MarkupHelper;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * ExtentTestListener — TestNG lifecycle hook that drives ExtentReportManager.
 *
 * HOW TESTNG DISCOVERS THIS LISTENER
 * ───────────────────────────────────
 * Registered declaratively in testng.xml:
 *   <listeners>
 *       <listener class-name="com.apinexus.report.ExtentTestListener"/>
 *   </listeners>
 *
 * TestNG instantiates this class once per suite run and calls its callback
 * methods automatically at the corresponding points in the test lifecycle —
 * no code in the test classes themselves needs to change.
 *
 * EVENT → ACTION MAP
 * ───────────────────
 *   onStart          (suite begins)   → nothing (report initialises lazily on first test)
 *   onTestStart       (each @Test)    → create an ExtentTest node, log description + groups
 *   onTestSuccess                     → mark node PASS (green), log duration
 *   onTestFailure                     → mark node FAIL (red), log full exception + stack trace
 *   onTestSkipped                     → mark node SKIP (orange), log skip reason
 *   onFinish         (suite ends)     → flush the report to disk, print summary to console
 */
public class ExtentTestListener implements ITestListener {

    // ── Test start ────────────────────────────────────────────────────────

    @Override
    public void onTestStart(ITestResult result) {
        // Build a display name that includes data-provider parameters, e.g.:
        //   "testGetPostDataDriven"               → no params
        //   "testGetPostDataDriven [1, 200]"       → data-driven invocation
        String testName = buildTestName(result);

        // result.getMethod().getDescription() reads the @Test(description=...)
        // attribute we wrote on every test method — e.g. "TC-CRUD-01: GET all
        // posts returns 200 and a non-empty array". This becomes the report's
        // one-line summary for the node.
        String description = result.getMethod().getDescription();

        ExtentTest test = ExtentReportManager.startTest(testName, description);

        // Tag the node with its TestNG group (crud, auth, mock, ...) so the
        // report's category filter can slice results by suite.
        String[] groups = result.getMethod().getGroups();
        if (groups != null && groups.length > 0) {
            test.assignCategory(groups);
        }

        // Tag with the test class's simple name too, for the "Author" filter.
        test.assignAuthor(result.getTestClass().getRealClass().getSimpleName());

        test.info("Test started: " + testName);
    }

    // ── Test outcomes ─────────────────────────────────────────────────────

    @Override
    public void onTestSuccess(ITestResult result) {
        ExtentTest test = ExtentReportManager.getCurrentTest();
        if (test != null) {
            long durationMs = result.getEndMillis() - result.getStartMillis();
            test.log(Status.PASS,
                    MarkupHelper.createLabel("PASSED in " + durationMs + " ms", ExtentColor.GREEN));
        }
        ExtentReportManager.clearCurrentTest();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        ExtentTest test = ExtentReportManager.getCurrentTest();
        if (test != null) {
            long durationMs = result.getEndMillis() - result.getStartMillis();

            // Log the failure headline in red …
            test.log(Status.FAIL,
                    MarkupHelper.createLabel("FAILED after " + durationMs + " ms", ExtentColor.RED));

            // … followed by the full exception (message + stack trace).
            // result.getThrowable() is the AssertionError or Exception that
            // caused the failure — ResponseValidator throws AssertionError
            // with a descriptive message, so this is usually self-explanatory.
            Throwable t = result.getThrowable();
            if (t != null) {
                test.fail(t);   // ExtentReports renders the full stack trace, collapsible
            }
        }
        ExtentReportManager.clearCurrentTest();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        ExtentTest test = ExtentReportManager.getCurrentTest();
        if (test == null) {
            // A test can be skipped before onTestStart ever fires (e.g. a
            // dependent @BeforeMethod failed) — create the node now so the
            // skip still shows up in the report instead of disappearing.
            test = ExtentReportManager.startTest(buildTestName(result),
                    result.getMethod().getDescription());
        }

        String reason = result.getThrowable() != null
                ? result.getThrowable().getMessage()
                : "Skipped (no reason captured)";
        test.log(Status.SKIP,
                MarkupHelper.createLabel("SKIPPED — " + reason, ExtentColor.ORANGE));

        ExtentReportManager.clearCurrentTest();
    }

    // ── Suite end ────────────────────────────────────────────────────────

    @Override
    public void onFinish(ITestContext context) {
        // Write the accumulated HTML report to disk exactly once,
        // after every test in this <test> block of testng.xml has finished.
        ExtentReportManager.flush();

        // Print a one-line summary to the console / CI log so the report
        // location is visible without opening the HTML file.
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

    /**
     * Builds a human-readable test name including @DataProvider parameters
     * (if any), so each data-driven invocation gets its own distinct node
     * in the report instead of all rows being merged into one.
     */
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
}
