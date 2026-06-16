package com.apinexus.report;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * ReportLogAppender — Custom Logback appender that forwards every log line
 * written by ApiLogger / ResponseValidator into the currently running test's
 * TestResultModel, so the HTML dashboard can show the full request/response/
 * assertion trail per test, not just a pass/fail badge.
 *
 * REGISTERED IN logback-test.xml:
 *   <appender name="REPORT" class="com.apinexus.report.ReportLogAppender"/>
 *   <logger name="com.apinexus" level="DEBUG">
 *       <appender-ref ref="REPORT"/>
 *   </logger>
 *
 * Every com.apinexus.* log call also reaches the console and the rolling
 * file appender as before — this is purely an ADDITIONAL sink for the same
 * events, via Logback's normal multi-appender fan-out.
 *
 * THREAD SAFETY: CustomReportListener.getCurrentTest() reads a ThreadLocal,
 * so a log line is always attached to the test running on the SAME thread
 * that produced it — correct even when @DataProvider rows run concurrently.
 */
public class ReportLogAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        TestResultModel currentTest = CustomReportListener.getCurrentTest();
        if (currentTest == null) {
            // No test running on this thread right now (e.g. @BeforeSuite
            // setup) — nothing to attach this line to, skip safely.
            return;
        }

        // Prefix with the level so FAIL/WARN lines are visually distinct
        // once rendered inside the <pre> log block in the HTML report.
        String line = "[" + event.getLevel() + "] " + event.getFormattedMessage();
        currentTest.addLogLine(line);
    }
}
