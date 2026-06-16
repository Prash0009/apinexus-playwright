package com.apinexus.report;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;

/**
 * ExtentLogAppender — Custom Logback appender that forwards every log line
 * written by ApiLogger / ResponseValidator into the HTML report, attached to
 * whichever test is currently running on that thread.
 *
 * WHY THIS EXISTS
 * ─────────────────
 * Without this class, the ExtentReports HTML file would only show PASS/FAIL
 * and the final exception for each test — exactly as much detail as the
 * plain Surefire report already gives you. The whole point of "detailed
 * level reporting" is that opening one test's node in the HTML report shows
 * the SAME request/response/assertion trail that ApiLogger already writes to
 * the console and to logs/apinexus-test.log — just organised per test
 * instead of as one giant chronological file.
 *
 * HOW LOGBACK WIRES THIS IN
 * ──────────────────────────
 * Registered in logback-test.xml as a normal appender:
 *
 *   <appender name="EXTENT" class="com.apinexus.report.ExtentLogAppender"/>
 *   <logger name="com.apinexus" level="DEBUG">
 *       <appender-ref ref="EXTENT"/>
 *   </logger>
 *
 * Every time ApiLogger (or any class under com.apinexus) calls log.info(),
 * log.warn(), or log.error(), Logback invokes append() on every appender
 * registered for that logger — including this one — IN ADDITION TO the
 * console and rolling-file appenders already configured. No existing logging
 * code changes; this is purely an additional sink for the same events.
 *
 * THREAD SAFETY
 * ──────────────
 * ExtentReportManager.getCurrentTest() reads a ThreadLocal, so log events
 * are always attached to the test running on the SAME thread that produced
 * them — correct even when data-provider rows run concurrently.
 */
public class ExtentLogAppender extends AppenderBase<ILoggingEvent> {

    /**
     * Called by Logback for every log event on a logger this appender is
     * attached to. Must return quickly and never throw — a logging appender
     * that throws can crash the entire test run.
     */
    @Override
    protected void append(ILoggingEvent event) {
        // If no test is currently running on this thread (e.g. a log line
        // emitted during @BeforeSuite/@AfterSuite setup, before any
        // ExtentTest node exists), there is nothing to attach to — skip it
        // safely rather than throwing a NullPointerException.
        ExtentTest currentTest = ExtentReportManager.getCurrentTest();
        if (currentTest == null) {
            return;
        }

        String message = event.getFormattedMessage();

        // Map the Logback level to an ExtentReports Status so the report
        // colour-codes lines the same way the console does:
        //   ERROR → red FAIL-style line
        //   WARN  → orange (used by ApiLogger for "✘ CHECK" assertion misses)
        //   INFO  → plain informational line (the vast majority of lines:
        //           ▶ REQUEST, ◀ RESPONSE, ✔ PASS, STEP n, etc.)
        //   DEBUG → grey, low-priority detail
        switch (event.getLevel().toString()) {
            case "ERROR":
                currentTest.log(Status.FAIL, message);
                break;
            case "WARN":
                currentTest.log(Status.WARNING, message);
                break;
            case "DEBUG":
                currentTest.log(Status.INFO, message);
                break;
            default: // INFO and anything else
                currentTest.log(Status.INFO, message);
        }
    }
}
