package tests.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of truth for test configuration.
 *
 * <p>Values are read once at class-load time from {@code config.properties}
 * and can be overridden with a JVM system property — so CI can point the
 * suite at another environment via {@code -Dapi.base.url=...} without
 * touching a tracked file.
 *
 * <p>Reading the file eagerly means a typo in a key fails immediately with
 * a clear message, rather than surfacing later as a confusing 404.
 */
public final class Config {

    private static final Properties PROPERTIES = load();

    private Config(){}

    private static Properties load() {
        Properties properties = new Properties();

        try (InputStream stream = Config.class.getClassLoader()
                .getResourceAsStream("config.properties")) {

            if (stream == null) {
                throw new IllegalStateException(
                        "config.properties not found on the test classpath. "
                        + "Expected it in src/test/resources.");
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config.properties", e);
        }

        return properties;
    }

    /**
     * Returns the value for {@code key}, preferring a JVM system property
     * when one is set.
     *
     * @throws IllegalStateException if the key is defined nowhere — a missing
     *                               setting is a configuration bug, not a
     *                               reason to silently fall back to null
     */

    private static String get(String key) {
        String value = System.getProperty(key, PROPERTIES.getProperty(key));
        if (value == null || value.isBlank() ) {
            throw new IllegalStateException("Missing configuration key: " + key);
        }
        return value.trim();
    }


    // -------------- BASE URL, PATH, LOGGING
    public static String baseUrl() {
        return get("api.base.url");
    }

    public static String basePath() {
        return get("api.base.path");
    }

    public static boolean httpLogging() {
        return Boolean.parseBoolean(get("http.logging"));
    }

    // -------------- SEED USER
    public static String seedUserEmail() {
        return get("seed.user.email");
    }

    public static String seedUserNickname() {
        return get("seed.user.nickname");
    }

    public static String seedUserPassword() {
        return get("seed.user.password");
    }
}
