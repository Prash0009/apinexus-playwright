package com.apinexus.utils;

import com.microsoft.playwright.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * ApiLogger — Centralised logging helper for HTTP requests and responses.
 *
 * Every test method calls these helpers so that the log file contains a
 * complete audit trail of every HTTP interaction: what was sent, what came
 * back, and how long it took. This makes diagnosing failures trivial because
 * you can always replay the exact call from the log.
 *
 * Design note: static utility methods are used intentionally.
 * ApiLogger has no state — it is a collection of formatting and logging
 * functions, not an object that needs to be instantiated.
 */
public class ApiLogger {

    // Logger named after this class; the name appears in every log line,
    // making it easy to grep for "ApiLogger" entries in the log file.
    private static final Logger log = LoggerFactory.getLogger(ApiLogger.class);

    // Visual separators make individual request/response blocks easy to
    // locate when scrolling through a long log file.
    private static final String SEPARATOR = "═".repeat(70);
    private static final String SUB_SEP   = "─".repeat(70);

    // Private constructor: prevents anyone from accidentally instantiating
    // a utility class that is designed to be used via static methods only.
    private ApiLogger() {}

    // ── Request logging ───────────────────────────────────────────────────

    /**
     * Logs the outgoing HTTP request BEFORE it is sent.
     *
     * @param method   HTTP method (GET, POST, PUT, PATCH, DELETE, etc.)
     * @param url      Fully qualified URL including any query parameters
     * @param headers  Map of request headers; may be null or empty
     * @param body     Request body as a String; null for body-less requests (GET)
     */
    public static void logRequest(String method, String url,
                                   Map<String, String> headers, String body) {
        // Print the opening separator so the request block is visually distinct
        log.info(SEPARATOR);
        log.info("▶ REQUEST");
        log.info("  Method  : {}", method);
        log.info("  URL     : {}", url);

        // Log each header on its own line for readability.
        // If headers is null or empty, skip the block entirely.
        if (headers != null && !headers.isEmpty()) {
            log.info("  Headers :");
            // Map.forEach iterates every key-value pair in insertion order
            // (LinkedHashMap) or hash order (HashMap) — order does not matter here.
            headers.forEach((k, v) ->
                    // Mask Authorization header values so tokens don't appear in logs
                    log.info("    {} : {}", k, maskSensitiveHeader(k, v)));
        }

        // Only print the body block when there is something to log.
        if (body != null && !body.isBlank()) {
            log.info("  Body    :");
            log.info("    {}", body);
        }

        log.info(SUB_SEP);
    }

    /**
     * Convenience overload for requests that have no custom headers and no body
     * (typical GET requests).
     */
    public static void logRequest(String method, String url) {
        logRequest(method, url, null, null);
    }

    // ── Response logging ──────────────────────────────────────────────────

    /**
     * Logs the HTTP response AFTER it is received, including timing.
     *
     * @param response      Playwright APIResponse object
     * @param elapsedMs     Round-trip time in milliseconds (caller measures this)
     */
    public static void logResponse(APIResponse response, long elapsedMs) {
        log.info("◀ RESPONSE");
        log.info("  Status  : {} {}", response.status(), statusText(response.status()));
        log.info("  Time    : {} ms", elapsedMs);

        // Log response headers — useful for checking Content-Type,
        // Cache-Control, X-RateLimit-*, CORS headers, etc.
        Map<String, String> headers = response.headers();
        if (!headers.isEmpty()) {
            log.info("  Headers :");
            headers.forEach((k, v) -> log.info("    {} : {}", k, v));
        }

        // response.text() reads the body as a UTF-8 string.
        // We truncate at 2000 chars so huge responses do not flood the log.
        String body = response.text();
        log.info("  Body    :");
        log.info("    {}", truncate(body, 2000));
        log.info(SEPARATOR);
    }

    // ── Assertion logging ─────────────────────────────────────────────────

    /**
     * Logs a single assertion check (pass or fail).
     * Call this BEFORE making the actual TestNG assertion so the log
     * reflects the intent even when the assertion throws.
     *
     * @param description  Human-readable description of what is being checked
     * @param expected     What we expect
     * @param actual       What the API returned
     */
    public static void logAssertion(String description, Object expected, Object actual) {
        boolean pass = expected != null && expected.equals(actual);
        if (pass) {
            log.info("  ✔ PASS  | {} | expected='{}' actual='{}'",
                    description, expected, actual);
        } else {
            log.warn("  ✘ CHECK | {} | expected='{}' actual='{}'",
                    description, expected, actual);
        }
    }

    /**
     * Logs the start of a test method — useful for correlating log lines
     * to the test that produced them when tests run in parallel.
     */
    public static void logTestStart(String testName) {
        log.info("\n\n{}", SEPARATOR);
        log.info("TEST START: {}", testName);
        log.info(SEPARATOR);
    }

    /**
     * Logs the successful completion of a test method.
     */
    public static void logTestEnd(String testName) {
        log.info("TEST END  : {} — PASSED", testName);
        log.info(SEPARATOR);
    }

    /**
     * Logs a step within a test — helps narrate multi-step flows like
     * API chaining where several calls happen in sequence.
     */
    public static void logStep(int stepNumber, String description) {
        log.info("  STEP {} ▶ {}", stepNumber, description);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Truncates a string to maxLength characters and appends "…" when trimmed.
     * Prevents enormous JSON arrays (e.g. 100 posts) from filling the log.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "<null>";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "… [truncated]";
    }

    /**
     * Replaces the VALUE of known sensitive headers with asterisks.
     * The header name itself is still printed so you can see it was present.
     */
    private static String maskSensitiveHeader(String name, String value) {
        // Compare case-insensitively because HTTP header names are case-insensitive.
        String lower = name.toLowerCase();
        if (lower.contains("authorization") || lower.contains("x-api-key")
                || lower.contains("cookie") || lower.contains("token")) {
            // Show the first 6 chars (e.g. "Bearer") then mask the rest
            if (value.length() > 6) {
                return value.substring(0, 6) + "****[MASKED]";
            }
            return "****[MASKED]";
        }
        return value;
    }

    /**
     * Returns a short human-readable reason phrase for common HTTP status codes.
     * Not exhaustive — just the ones most likely to appear in API test logs.
     */
    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default:  return "";
        }
    }
}
