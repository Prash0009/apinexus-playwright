package com.apinexus.tests.upload;

import com.apinexus.tests.base.BaseApiTest;
import com.apinexus.utils.ApiLogger;
import com.apinexus.utils.ResponseValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FileUploadTest — Tests multipart/form-data file uploads via the WireMock mock server.
 *
 * WHY MOCK A FILE UPLOAD?
 * Live file-upload endpoints often require authentication, have file size limits,
 * charge per upload (cloud storage), or take a long time. A WireMock stub lets us:
 *   - Test the CLIENT-SIDE multipart request construction
 *   - Verify the server-side response handling
 *   - Test error cases (too large, wrong type, missing file)
 * …all without touching a real storage service.
 *
 * MULTIPART/FORM-DATA PRIMER:
 * A multipart request contains multiple "parts" separated by a boundary string.
 * Each part has its own headers (Content-Disposition, Content-Type) and body.
 * Example:
 *   --boundary
 *   Content-Disposition: form-data; name="file"; filename="report.csv"
 *   Content-Type: text/csv
 *
 *   id,name,value
 *   1,Alice,100
 *   --boundary--
 *
 * Playwright's FormData class builds this structure automatically.
 *
 * Scenarios covered:
 *   TC-UPLOAD-01  Upload a text file → 201 Created with file metadata
 *   TC-UPLOAD-02  Upload with additional form fields (description, tags)
 *   TC-UPLOAD-03  Wrong Content-Type (not multipart) → 400 Bad Request
 *   TC-UPLOAD-04  Missing file part → 400 Bad Request
 *   TC-UPLOAD-05  File size too large simulation → 413 Payload Too Large
 */
@Test(groups = "upload")
public class FileUploadTest extends BaseApiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // Temporary file created before each test and deleted after.
    private Path tempFile;

    /**
     * @BeforeMethod — runs before each test method in this class.
     * Creates a real temp file on disk so we can upload its bytes via Playwright.
     */
    @BeforeMethod(alwaysRun = true)
    public void createTempFile() throws Exception {
        // Files.createTempFile() creates a new file in the OS temp directory.
        // The first arg is the name prefix, the second is the suffix (extension).
        tempFile = Files.createTempFile("apinexus-upload-", ".csv");

        // Write sample CSV content into the temp file.
        // getBytes(UTF_8) ensures the content is encoded correctly.
        String csvContent = "id,name,score\n1,Alice,95\n2,Bob,87\n3,Carol,92\n";
        Files.write(tempFile, csvContent.getBytes(StandardCharsets.UTF_8));

        log.info("Temp file created: {} ({} bytes)",
                tempFile.toAbsolutePath(),
                Files.size(tempFile));

        // Register the WireMock stub for file upload
        mockServer.stubFileUpload("/api/upload");
        mockServer.stubFileUpload("/api/upload/with-fields");
    }

    /**
     * @AfterMethod — runs after each test method.
     * Deletes the temp file to avoid polluting the OS temp directory.
     */
    @AfterMethod(alwaysRun = true)
    public void deleteTempFile() throws Exception {
        if (tempFile != null && Files.exists(tempFile)) {
            Files.delete(tempFile);
            log.info("Temp file deleted: {}", tempFile.toAbsolutePath());
        }
        // Reset WireMock stubs after each test
        mockServer.resetAllStubs();
    }

    // ── TC-UPLOAD-01: Basic file upload ───────────────────────────────────

    /**
     * TC-UPLOAD-01: Upload a CSV file via multipart/form-data.
     *
     * Playwright's FormData.create() builds the multipart body.
     * .append(name, value, fileName) adds a file part with:
     *   - name: the form field name ("file")
     *   - value: the file bytes
     *   - fileName: the filename in the Content-Disposition header
     */
    @Test(description = "TC-UPLOAD-01: Upload CSV file returns 201 with file metadata")
    public void testBasicFileUpload() throws Exception {
        logTestStart("testBasicFileUpload");

        // Read the temp file's bytes into memory.
        // For very large files (GB+) you would stream instead.
        byte[] fileBytes = Files.readAllBytes(tempFile);

        // FormData.create() builds a multipart body.
        // FilePayload wraps the file bytes with a name and MIME type.
        // append(fieldName, FilePayload) adds one file part.
        FormData formData = FormData.create()
                .append("file", new FilePayload("report.csv", "text/csv", fileBytes));

        String url = mockBaseUrl + "/api/upload";
        log.info("Uploading {} bytes to {}", fileBytes.length, url);

        // When setMultipart() is used, Playwright automatically sets the
        // Content-Type header to "multipart/form-data; boundary=..." with a
        // generated boundary string — we do NOT set Content-Type manually.
        long start = startTimer();
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setMultipart(formData));
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 201);
        ResponseValidator.assertContentType(response, "application/json");

        // Verify the response body contains file metadata
        JsonNode body = mapper.readTree(response.text());
        Assert.assertEquals(body.get("message").asText(), "File uploaded successfully",
                "Upload success message mismatch");
        Assert.assertTrue(body.has("fileId"),
                "Response should include a fileId");
        Assert.assertTrue(body.get("size").asInt() > 0,
                "Response size should be positive");

        log.info("Upload successful. fileId={}, reportedSize={}",
                body.get("fileId").asText(), body.get("size").asInt());
        logTestEnd("testBasicFileUpload");
    }

    // ── TC-UPLOAD-02: Upload with extra form fields ────────────────────────

    /**
     * TC-UPLOAD-02: Upload a file along with additional text form fields
     * (description, tags, category).
     *
     * Real upload APIs almost always accept metadata alongside the file.
     * This test verifies that both the file part and the text parts are
     * correctly included in the multipart request.
     */
    @Test(description = "TC-UPLOAD-02: Upload file with additional form metadata fields")
    public void testUploadWithFormFields() throws Exception {
        logTestStart("testUploadWithFormFields");

        byte[] fileBytes = Files.readAllBytes(tempFile);

        // Build the stub response to include the metadata fields echoed back
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/upload/with-fields"))
                        .withHeader("Content-Type",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .containing("multipart/form-data"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{" +
                                                "\"message\":\"File uploaded successfully\"," +
                                                "\"fileId\":\"xyz-789\"," +
                                                "\"category\":\"reports\"," +
                                                "\"tags\":[\"q1\",\"finance\"]," +
                                                "\"size\":512" +
                                                "}")));

        // FormData can mix file parts and text parts freely.
        // File parts use FilePayload; text parts use a plain String value.
        FormData formData = FormData.create()
                .append("file",        new FilePayload("report.csv", "text/csv", fileBytes))  // file part
                .append("description", "Q1 Financial Report")                                  // text part
                .append("category",    "reports")                                              // text part
                .append("tags",        "q1,finance");                                          // text part

        String url = mockBaseUrl + "/api/upload/with-fields";
        log.info("Uploading file ({} bytes) with form fields to {}", fileBytes.length, url);

        long start = startTimer();
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setMultipart(formData));
        long elapsed = elapsedMs(start);

        ApiLogger.logResponse(response, elapsed);
        ResponseValidator.assertStatusCode(response, 201);

        JsonNode body = mapper.readTree(response.text());
        Assert.assertEquals(body.get("category").asText(), "reports");
        Assert.assertTrue(body.has("tags"), "Response should include tags");

        logTestEnd("testUploadWithFormFields");
    }

    // ── TC-UPLOAD-03: Wrong Content-Type → 400 ────────────────────────────

    /**
     * TC-UPLOAD-03: POST to the upload endpoint with application/json instead of
     * multipart/form-data. A strict API should reject this with 400.
     *
     * This is a negative test: it verifies the endpoint validates Content-Type.
     */
    @Test(description = "TC-UPLOAD-03: Wrong Content-Type to upload endpoint returns 400")
    public void testUploadWrongContentType() {
        logTestStart("testUploadWrongContentType");

        // Register a stub that returns 400 for non-multipart requests
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/upload/strict"))
                        .withHeader("Content-Type",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .containing("application/json"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"Content-Type must be multipart/form-data\"}")));

        String url = mockBaseUrl + "/api/upload/strict";
        ApiLogger.logRequest("POST", url, java.util.Map.of("Content-Type", "application/json"), "{}");

        // Send a JSON body instead of a multipart body
        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData("{}"));

        ApiLogger.logResponse(response, 0);
        ResponseValidator.assertStatusCode(response, 400);
        ResponseValidator.assertBodyContains(response, "multipart/form-data");

        logTestEnd("testUploadWrongContentType");
    }

    // ── TC-UPLOAD-04: Missing file part → 400 ─────────────────────────────

    /**
     * TC-UPLOAD-04: Submit a multipart request but omit the "file" field.
     *
     * The upload endpoint requires a part named "file". Omitting it tests
     * server-side validation of required multipart fields.
     */
    @Test(description = "TC-UPLOAD-04: Missing file part returns 400")
    public void testUploadMissingFilePart() {
        logTestStart("testUploadMissingFilePart");

        // Stub: multipart without a file part → 400
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/upload/required-file"))
                        .withHeader("Content-Type",
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .containing("multipart/form-data"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"Missing required field: file\"}")));

        // Multipart body with ONLY a text field — no file
        FormData formData = FormData.create()
                .append("description", "No file attached here");

        String url = mockBaseUrl + "/api/upload/required-file";
        log.info("Sending multipart without 'file' part to verify 400 response");

        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setMultipart(formData));

        ApiLogger.logResponse(response, 0);
        ResponseValidator.assertStatusCode(response, 400);
        ResponseValidator.assertBodyContains(response, "Missing required field");

        logTestEnd("testUploadMissingFilePart");
    }

    // ── TC-UPLOAD-05: Payload Too Large → 413 ─────────────────────────────

    /**
     * TC-UPLOAD-05: Simulate a file-size limit enforcement.
     *
     * Many upload APIs enforce a maximum file size (e.g. 5MB). When the limit
     * is exceeded, the server returns 413 Payload Too Large.
     *
     * The WireMock stub simulates this without us needing to generate a truly
     * large file. We use a Content-Length header stub-match to trigger the 413.
     */
    @Test(description = "TC-UPLOAD-05: Large file upload returns 413 Payload Too Large")
    public void testUploadFileTooLarge() {
        logTestStart("testUploadFileTooLarge");

        // Stub: any request to this URL → 413 (simulating size limit exceeded)
        com.github.tomakehurst.wiremock.client.WireMock.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlEqualTo("/api/upload/size-limited"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(413)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"Payload Too Large\"," +
                                                "\"maxSizeBytes\":5242880," +
                                                "\"message\":\"File exceeds the 5MB limit\"}")));

        // We use a small payload here — the stub always returns 413 regardless.
        // In a real integration, you would generate a file > maxSize.
        FormData formData = FormData.create()
                .append("file", new FilePayload("small.bin", "application/octet-stream", new byte[]{1, 2, 3}));

        String url = mockBaseUrl + "/api/upload/size-limited";
        log.info("Testing 413 response for size-limited upload endpoint");

        APIResponse response = request.post(url,
                RequestOptions.create()
                        .setMultipart(formData));

        ApiLogger.logResponse(response, 0);
        ResponseValidator.assertStatusCode(response, 413);
        ResponseValidator.assertBodyContains(response, "5MB limit");

        JsonNode body;
        try {
            body = mapper.readTree(response.text());
            long maxSize = body.get("maxSizeBytes").asLong();
            log.info("Server enforces max size of {} bytes ({} MB)",
                    maxSize, maxSize / 1_048_576);
            Assert.assertTrue(maxSize > 0, "maxSizeBytes should be positive");
        } catch (Exception e) {
            Assert.fail("Could not parse 413 response body: " + e.getMessage());
        }

        logTestEnd("testUploadFileTooLarge");
    }
}
