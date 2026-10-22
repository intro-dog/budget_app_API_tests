package tests.smoke;

import tests.config.Config;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke check confirming the API is reachable before any functional
 * suite runs.
 *
 * <p>A failure here means the environment is down, not that the product is
 * broken — which is exactly why it lives in its own package and runs first.
 */
class HealthCheckTest {

    @Test
    @DisplayName("API responds to a request on the OpenAPI schema endpoint")
    void apiIsReachable() {
        Response response = given()
                .baseUri(Config.baseUrl())
                .when()
                .get("/api/openapi.json");

        assertThat(response.statusCode())
                .as("API should return 200 for its OpenAPI schema")
                .isEqualTo(200);
    }
}
