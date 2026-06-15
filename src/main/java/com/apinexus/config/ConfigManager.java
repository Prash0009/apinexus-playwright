package com.apinexus.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ConfigManager — Singleton that loads config.properties from the classpath
 * exactly once and exposes typed getters for every configuration key.
 *
 * WHY A SINGLETON?
 * Reading and parsing a properties file has I/O cost. By loading it once and
 * caching the result in a static field we pay that cost only on the first call
 * from any test class, not once per test method.
 *
 * HOW TO ADD A NEW PROPERTY:
 *   1. Add the key/value pair to src/test/resources/config.properties
 *   2. Add a typed getter below (getString / getInt / getLong)
 */
public class ConfigManager {

    // SLF4J logger — writes to both the console and the rolling log file
    // as configured in logback-test.xml.
    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    // Singleton instance — volatile so the JVM does not reorder instructions
    // across threads (important if tests run in parallel).
    private static volatile ConfigManager instance;

    // Holds all key=value pairs from config.properties after they are loaded.
    private final Properties properties;

    // Name of the properties file on the test classpath.
    private static final String CONFIG_FILE = "config.properties";

    // ── Private constructor ────────────────────────────────────────────────
    // Private so nobody can call `new ConfigManager()` from outside.
    private ConfigManager() {
        properties = new Properties();
        loadProperties();
    }

    // ── Singleton accessor (double-checked locking) ────────────────────────
    /**
     * Returns the single ConfigManager instance, creating it on the first call.
     * Thread-safe via double-checked locking.
     */
    public static ConfigManager getInstance() {
        if (instance == null) {                      // first cheap check (no lock)
            synchronized (ConfigManager.class) {     // acquire lock only if null
                if (instance == null) {              // re-check after lock acquired
                    instance = new ConfigManager();
                }
            }
        }
        return instance;
    }

    // ── File loading ──────────────────────────────────────────────────────
    /**
     * Reads config.properties from the classpath into the Properties object.
     * getResourceAsStream() searches the test classpath first, then main,
     * so src/test/resources/config.properties takes precedence.
     */
    private void loadProperties() {
        // getClassLoader().getResourceAsStream() returns null when the file
        // is not found rather than throwing, so we handle that below.
        try (InputStream inputStream =
                     ConfigManager.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {

            if (inputStream == null) {
                // Fail loudly: a missing config file means ALL tests will break.
                throw new RuntimeException(
                        "Cannot find '" + CONFIG_FILE + "' on the classpath. " +
                        "Ensure it exists under src/test/resources/");
            }

            // Properties.load() parses the key=value / key: value format.
            properties.load(inputStream);
            log.info("Configuration loaded successfully from '{}'", CONFIG_FILE);

        } catch (IOException e) {
            // IOException here means the stream was opened but reading failed —
            // wrap in a RuntimeException so the test framework catches it and
            // marks the suite as failed rather than silently running with nulls.
            throw new RuntimeException("Failed to read " + CONFIG_FILE, e);
        }
    }

    // ── Typed getters ─────────────────────────────────────────────────────

    /**
     * Returns the raw string value for the given key.
     * Throws IllegalArgumentException when the key is absent so tests fail
     * with a clear message instead of an obscure NullPointerException later.
     */
    public String getString(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Property '" + key + "' not found in " + CONFIG_FILE);
        }
        return value.trim();   // trim() removes accidental leading/trailing spaces
    }

    /**
     * Parses and returns a property as an int.
     * NumberFormatException is propagated unchanged so the test fails clearly.
     */
    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    /**
     * Parses and returns a property as a long.
     */
    public long getLong(String key) {
        return Long.parseLong(getString(key));
    }

    // ── Convenience shortcut getters ──────────────────────────────────────

    /** Base URL for JSONPlaceholder (CRUD / schema / chaining tests). */
    public String getBaseUrl() {
        return getString("base.url");
    }

    /** Base URL for ReqRes (auth tests). */
    public String getAuthBaseUrl() {
        return getString("auth.base.url");
    }

    /** Port on which WireMock will listen during mock-server tests. */
    public int getMockServerPort() {
        return getInt("mock.server.port");
    }

    /** Maximum allowed API response time in milliseconds. */
    public long getMaxResponseTimeMs() {
        return getLong("performance.max.response.time.ms");
    }

    /** Demo bearer token for tests that skip the live login flow. */
    public String getDemoAuthToken() {
        return getString("auth.demo.token");
    }

    /**
     * Returns the mock server mode: "local" (embedded WireMock JVM) or
     * "remote" (WireMock Admin REST API on a cloud server).
     */
    public String getMockMode() {
        return getString("mock.mode").toLowerCase();
    }

    /**
     * Returns the remote WireMock base URL.
     * Only relevant when mock.mode=remote.
     */
    public String getMockRemoteUrl() {
        return getString("mock.remote.url");
    }

    /**
     * Returns the remote WireMock API key (WireMock Cloud).
     * Returns an empty string when no key is configured (open self-hosted servers).
     */
    public String getMockRemoteApiKey() {
        String key = properties.getProperty("mock.remote.api.key", "");
        return key == null ? "" : key.trim();
    }
}
