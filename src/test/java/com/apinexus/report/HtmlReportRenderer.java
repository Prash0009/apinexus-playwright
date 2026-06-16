package com.apinexus.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * HtmlReportRenderer — Builds a single self-contained HTML file from a
 * SuiteReportModel, styled after Cluecumber's clean card/donut-chart look
 * (normally seen on Cucumber JSON reports) but driven entirely by our own
 * TestNG result model — no Cucumber dependency anywhere in this project.
 *
 * DESIGN GOALS
 * ─────────────────────────────────────────────────────────────────────────
 *   1. ONE FILE — no CDN dependency, no separate CSS/JS assets, opens
 *      correctly even with zero internet access (double-click and go).
 *   2. Cluecumber-style layout:
 *        - Summary stat cards (Total / Passed / Failed / Skipped)
 *        - A CSS-only donut chart (no Chart.js) showing the pass/fail/skip split
 *        - Collapsible per-class sections, each containing collapsible
 *          per-test rows (native <details>/<summary> — zero JS needed for
 *          expand/collapse)
 *        - Filter chips (by TestNG group) and a live search box (small
 *          vanilla JS, no framework)
 *   3. Same level of per-test detail as before: every captured log line is
 *      shown inside the test's expanded panel, plus the full failure
 *      message/stack trace when a test fails.
 *
 * HOW IT IS INVOKED
 * CustomReportListener#onFinish calls HtmlReportRenderer.render(model) once
 * the suite completes.
 */
public final class HtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);
    private static final String REPORT_DIR = "reports";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    private HtmlReportRenderer() {}

    /**
     * Renders the given model to reports/ApiNexus-Test-Report-<timestamp>.html.
     */
    public static void render(SuiteReportModel model) {
        try {
            File dir = new File(REPORT_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String fileName = "ApiNexus-Test-Report-" + model.getGeneratedAt().format(FILE_TS) + ".html";
            Path path = dir.toPath().resolve(fileName);

            String html = buildHtml(model);
            Files.write(path, html.getBytes(StandardCharsets.UTF_8));

            log.info("HTML report written to {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write HTML report", e);
        }
    }

    // ── HTML assembly ────────────────────────────────────────────────────

    private static String buildHtml(SuiteReportModel model) {
        long total   = model.getTotalCount();
        long passed  = model.getPassedCount();
        long failed  = model.getFailedCount();
        long skipped = model.getSkippedCount();

        // Avoid division by zero when the suite produced no results.
        double passPct = total == 0 ? 0 : (passed  * 100.0 / total);
        double failPct = total == 0 ? 0 : (failed  * 100.0 / total);
        double skipPct = total == 0 ? 0 : (skipped * 100.0 / total);

        // conic-gradient stops, expressed as cumulative percentages.
        double stop1 = passPct;
        double stop2 = passPct + failPct;

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<title>ApiNexus Test Report</title>\n");
        html.append("<style>").append(CSS).append("</style>\n");
        html.append("</head>\n<body>\n");

        // ── Header ──────────────────────────────────────────────────────
        html.append("<header class=\"top-bar\">\n");
        html.append("  <div class=\"top-bar-inner\">\n");
        html.append("    <h1>ApiNexus Test Report</h1>\n");
        html.append("    <p class=\"subtitle\">Generated ")
            .append(esc(model.getGeneratedAt().format(DISPLAY_TS)))
            .append(" &middot; Total duration ")
            .append(formatDuration(model.getTotalDurationMs()))
            .append("</p>\n");
        html.append("  </div>\n");
        html.append("</header>\n");

        html.append("<main class=\"container\">\n");

        // ── Summary cards + donut ──────────────────────────────────────
        html.append("<section class=\"summary\">\n");
        html.append("  <div class=\"stat-cards\">\n");
        html.append(statCard("Total",   total,   "total"));
        html.append(statCard("Passed",  passed,  "pass"));
        html.append(statCard("Failed",  failed,  "fail"));
        html.append(statCard("Skipped", skipped, "skip"));
        html.append("  </div>\n");

        html.append("  <div class=\"donut-wrap\">\n");
        html.append("    <div class=\"donut\" style=\"background: conic-gradient(")
            .append("var(--pass) 0% ").append(fmt(stop1)).append("%, ")
            .append("var(--fail) ").append(fmt(stop1)).append("% ").append(fmt(stop2)).append("%, ")
            .append("var(--skip) ").append(fmt(stop2)).append("% 100%);\">\n");
        html.append("      <div class=\"donut-hole\"><span>")
            .append(fmt(passPct)).append("%</span><small>passed</small></div>\n");
        html.append("    </div>\n");
        html.append("    <ul class=\"legend\">\n");
        html.append("      <li><i class=\"dot pass\"></i> Passed (").append(passed).append(")</li>\n");
        html.append("      <li><i class=\"dot fail\"></i> Failed (").append(failed).append(")</li>\n");
        html.append("      <li><i class=\"dot skip\"></i> Skipped (").append(skipped).append(")</li>\n");
        html.append("    </ul>\n");
        html.append("  </div>\n");
        html.append("</section>\n");

        // ── Filter chips + search ───────────────────────────────────────
        TreeSet<String> groups = new TreeSet<>();
        for (TestResultModel r : model.getResults()) {
            groups.add(r.getGroup());
        }

        html.append("<section class=\"toolbar\">\n");
        html.append("  <div class=\"chips\" id=\"chips\">\n");
        html.append("    <button class=\"chip active\" data-group=\"all\">All</button>\n");
        for (String g : groups) {
            html.append("    <button class=\"chip\" data-group=\"").append(esc(g)).append("\">")
                .append(esc(g)).append("</button>\n");
        }
        html.append("  </div>\n");
        html.append("  <input type=\"text\" id=\"search\" placeholder=\"Search test name or description...\" />\n");
        html.append("</section>\n");

        // ── Per-class accordion ─────────────────────────────────────────
        Map<String, java.util.List<TestResultModel>> byClass = groupByClass(model.getResults());

        html.append("<section class=\"results\" id=\"results\">\n");
        for (Map.Entry<String, java.util.List<TestResultModel>> entry : byClass.entrySet()) {
            html.append(renderClassBlock(entry.getKey(), entry.getValue()));
        }
        html.append("</section>\n");

        html.append("</main>\n");
        html.append("<footer class=\"footer\">ApiNexus &middot; Playwright Java API Test Suite</footer>\n");

        html.append("<script>").append(JS).append("</script>\n");
        html.append("</body>\n</html>\n");

        return html.toString();
    }

    private static String statCard(String label, long count, String cssClass) {
        return "    <div class=\"stat-card " + cssClass + "\">\n" +
               "      <span class=\"stat-number\">" + count + "</span>\n" +
               "      <span class=\"stat-label\">" + esc(label) + "</span>\n" +
               "    </div>\n";
    }

    private static Map<String, java.util.List<TestResultModel>> groupByClass(java.util.List<TestResultModel> results) {
        Map<String, java.util.List<TestResultModel>> map = new LinkedHashMap<>();
        for (TestResultModel r : results) {
            map.computeIfAbsent(r.getClassName(), k -> new java.util.ArrayList<>()).add(r);
        }
        return map;
    }

    private static String renderClassBlock(String className, java.util.List<TestResultModel> tests) {
        long passed = tests.stream().filter(t -> "PASS".equals(t.getStatus())).count();
        long total  = tests.size();
        boolean allPassed = passed == total;

        StringBuilder sb = new StringBuilder();
        sb.append("  <details class=\"class-block\" open data-class=\"").append(esc(className)).append("\">\n");
        sb.append("    <summary class=\"class-summary\">\n");
        sb.append("      <span class=\"class-name\">").append(esc(className)).append("</span>\n");
        sb.append("      <span class=\"class-badge ").append(allPassed ? "pass" : "fail").append("\">")
          .append(passed).append("/").append(total).append(" passed</span>\n");
        sb.append("    </summary>\n");
        sb.append("    <div class=\"test-list\">\n");
        for (TestResultModel t : tests) {
            sb.append(renderTestRow(t));
        }
        sb.append("    </div>\n");
        sb.append("  </details>\n");
        return sb.toString();
    }

    private static String renderTestRow(TestResultModel t) {
        String statusClass = statusCssClass(t.getStatus());
        String icon = statusIcon(t.getStatus());

        StringBuilder sb = new StringBuilder();
        sb.append("      <details class=\"test-row\" data-group=\"").append(esc(t.getGroup()))
          .append("\" data-name=\"").append(esc((t.getTestName() + " " + t.getDescription()).toLowerCase())).append("\">\n");
        sb.append("        <summary class=\"test-summary ").append(statusClass).append("\">\n");
        sb.append("          <span class=\"status-icon\">").append(icon).append("</span>\n");
        sb.append("          <span class=\"test-name\">").append(esc(t.getTestName())).append("</span>\n");
        sb.append("          <span class=\"test-desc\">").append(esc(t.getDescription())).append("</span>\n");
        sb.append("          <span class=\"test-duration\">").append(t.getDurationMs()).append(" ms</span>\n");
        sb.append("        </summary>\n");
        sb.append("        <div class=\"test-detail\">\n");

        if ("FAIL".equals(t.getStatus()) && t.getFailureMessage() != null) {
            sb.append("          <div class=\"failure-box\">\n");
            sb.append("            <strong>Failure:</strong> ").append(esc(t.getFailureMessage())).append("\n");
            if (t.getStackTrace() != null) {
                sb.append("            <details class=\"stack-trace\"><summary>Stack trace</summary><pre>")
                  .append(esc(t.getStackTrace())).append("</pre></details>\n");
            }
            sb.append("          </div>\n");
        }

        if ("SKIP".equals(t.getStatus()) && t.getFailureMessage() != null) {
            sb.append("          <div class=\"skip-box\"><strong>Skip reason:</strong> ")
              .append(esc(t.getFailureMessage())).append("</div>\n");
        }

        sb.append("          <pre class=\"log-block\">").append(esc(joinLogLines(t))).append("</pre>\n");
        sb.append("        </div>\n");
        sb.append("      </details>\n");
        return sb.toString();
    }

    private static String joinLogLines(TestResultModel t) {
        List<String> lines = t.getLogLines();
        if (lines.isEmpty()) {
            return "(no log lines captured)";
        }
        return String.join("\n", lines);
    }

    private static String statusCssClass(String status) {
        switch (status) {
            case "PASS": return "pass";
            case "FAIL": return "fail";
            case "SKIP": return "skip";
            default: return "";
        }
    }

    private static String statusIcon(String status) {
        switch (status) {
            case "PASS": return "&#10003;"; // ✓
            case "FAIL": return "&#10007;"; // ✗
            case "SKIP": return "&#8226;";  // •
            default: return "?";
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        double seconds = ms / 1000.0;
        if (seconds < 60) return String.format("%.1f s", seconds);
        long minutes = (long) (seconds / 60);
        double remSeconds = seconds - (minutes * 60);
        return String.format("%dm %.1fs", minutes, remSeconds);
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    /** Minimal HTML-escaping for safe embedding of arbitrary text (JSON bodies, stack traces, etc.) */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ── Embedded CSS (Cluecumber-inspired: light theme, card-based, status colors) ──

    private static final String CSS =
        ":root{--pass:#2e7d32;--fail:#c62828;--skip:#f9a825;--bg:#f4f6f9;--card:#ffffff;--text:#202833;--muted:#6b7785;--accent:#0d6efd;--border:#e3e7ed;}" +
        "*{box-sizing:border-box;}" +
        "body{margin:0;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--text);}" +
        ".top-bar{background:linear-gradient(135deg,#0d2840,#0d6efd);color:#fff;padding:28px 0;}" +
        ".top-bar-inner{max-width:1100px;margin:0 auto;padding:0 24px;}" +
        ".top-bar h1{margin:0 0 4px;font-size:26px;font-weight:600;}" +
        ".subtitle{margin:0;opacity:.85;font-size:14px;}" +
        ".container{max-width:1100px;margin:0 auto;padding:24px;}" +
        ".summary{display:flex;flex-wrap:wrap;gap:24px;align-items:center;margin-bottom:24px;}" +
        ".stat-cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;flex:1;min-width:280px;}" +
        ".stat-card{background:var(--card);border-radius:10px;padding:16px;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.07);border-top:4px solid var(--muted);}" +
        ".stat-card.total{border-top-color:var(--accent);}" +
        ".stat-card.pass{border-top-color:var(--pass);}" +
        ".stat-card.fail{border-top-color:var(--fail);}" +
        ".stat-card.skip{border-top-color:var(--skip);}" +
        ".stat-number{display:block;font-size:28px;font-weight:700;}" +
        ".stat-label{display:block;font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em;margin-top:4px;}" +
        ".donut-wrap{display:flex;align-items:center;gap:16px;background:var(--card);border-radius:10px;padding:16px 20px;box-shadow:0 1px 3px rgba(0,0,0,.07);}" +
        ".donut{width:96px;height:96px;border-radius:50%;display:flex;align-items:center;justify-content:center;flex-shrink:0;}" +
        ".donut-hole{width:62px;height:62px;border-radius:50%;background:var(--card);display:flex;flex-direction:column;align-items:center;justify-content:center;}" +
        ".donut-hole span{font-size:16px;font-weight:700;}" +
        ".donut-hole small{font-size:9px;color:var(--muted);}" +
        ".legend{list-style:none;margin:0;padding:0;font-size:13px;}" +
        ".legend li{display:flex;align-items:center;gap:6px;margin:4px 0;}" +
        ".dot{width:10px;height:10px;border-radius:50%;display:inline-block;}" +
        ".dot.pass{background:var(--pass);}.dot.fail{background:var(--fail);}.dot.skip{background:var(--skip);}" +
        ".toolbar{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between;margin-bottom:18px;}" +
        ".chips{display:flex;flex-wrap:wrap;gap:8px;}" +
        ".chip{border:1px solid var(--border);background:var(--card);border-radius:999px;padding:6px 14px;font-size:13px;cursor:pointer;color:var(--text);}" +
        ".chip.active{background:var(--accent);color:#fff;border-color:var(--accent);}" +
        "#search{flex:1;min-width:220px;max-width:320px;padding:8px 14px;border:1px solid var(--border);border-radius:999px;font-size:13px;}" +
        ".class-block{background:var(--card);border-radius:10px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.07);overflow:hidden;}" +
        ".class-summary{display:flex;justify-content:space-between;align-items:center;padding:14px 18px;cursor:pointer;font-weight:600;list-style:none;}" +
        ".class-summary::-webkit-details-marker{display:none;}" +
        ".class-name{font-size:15px;}" +
        ".class-badge{font-size:12px;font-weight:600;padding:3px 10px;border-radius:999px;}" +
        ".class-badge.pass{background:#e6f4ea;color:var(--pass);}" +
        ".class-badge.fail{background:#fdecea;color:var(--fail);}" +
        ".test-list{border-top:1px solid var(--border);}" +
        ".test-row{border-bottom:1px solid var(--border);}" +
        ".test-row:last-child{border-bottom:none;}" +
        ".test-summary{display:flex;align-items:center;gap:10px;padding:10px 18px;cursor:pointer;list-style:none;font-size:13px;}" +
        ".test-summary::-webkit-details-marker{display:none;}" +
        ".status-icon{width:20px;height:20px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:12px;color:#fff;flex-shrink:0;}" +
        ".test-summary.pass .status-icon{background:var(--pass);}" +
        ".test-summary.fail .status-icon{background:var(--fail);}" +
        ".test-summary.skip .status-icon{background:var(--skip);}" +
        ".test-name{font-weight:600;min-width:200px;}" +
        ".test-desc{color:var(--muted);flex:1;}" +
        ".test-duration{color:var(--muted);font-size:12px;white-space:nowrap;}" +
        ".test-detail{padding:0 18px 16px 48px;}" +
        ".failure-box{background:#fdecea;border:1px solid #f5c6c0;color:#7a1f17;border-radius:8px;padding:10px 14px;margin-bottom:10px;font-size:13px;}" +
        ".skip-box{background:#fff7e0;border:1px solid #f5d98b;color:#7a5b00;border-radius:8px;padding:10px 14px;margin-bottom:10px;font-size:13px;}" +
        ".stack-trace{margin-top:8px;}" +
        ".stack-trace pre{background:#1e1e1e;color:#eee;padding:10px;border-radius:6px;max-height:240px;overflow:auto;font-size:11px;}" +
        ".log-block{background:#10151c;color:#c7d3e0;padding:14px;border-radius:8px;font-size:12px;line-height:1.6;max-height:360px;overflow:auto;white-space:pre-wrap;word-break:break-word;}" +
        ".footer{text-align:center;color:var(--muted);font-size:12px;padding:24px;}" +
        "@media (max-width:700px){.stat-cards{grid-template-columns:repeat(2,1fr);}.test-name{min-width:120px;}}";

    // ── Embedded JS (vanilla — filter chips + live search) ──────────────

    private static final String JS =
        "document.addEventListener('DOMContentLoaded',function(){" +
        "var chips=document.querySelectorAll('.chip');" +
        "var search=document.getElementById('search');" +
        "var rows=document.querySelectorAll('.test-row');" +
        "var activeGroup='all';" +
        "function applyFilter(){" +
        "  var term=search.value.toLowerCase();" +
        "  rows.forEach(function(row){" +
        "    var groupOk = activeGroup==='all' || row.getAttribute('data-group')===activeGroup;" +
        "    var textOk = term==='' || row.getAttribute('data-name').indexOf(term)!==-1;" +
        "    row.style.display = (groupOk && textOk) ? '' : 'none';" +
        "  });" +
        "  document.querySelectorAll('.class-block').forEach(function(block){" +
        "    var visible = block.querySelectorAll('.test-row:not([style*=\"display: none\"])').length;" +
        "    block.style.display = visible>0 ? '' : 'none';" +
        "  });" +
        "}" +
        "chips.forEach(function(chip){" +
        "  chip.addEventListener('click',function(){" +
        "    chips.forEach(function(c){c.classList.remove('active');});" +
        "    chip.classList.add('active');" +
        "    activeGroup=chip.getAttribute('data-group');" +
        "    applyFilter();" +
        "  });" +
        "});" +
        "search.addEventListener('input',applyFilter);" +
        "});";
}
