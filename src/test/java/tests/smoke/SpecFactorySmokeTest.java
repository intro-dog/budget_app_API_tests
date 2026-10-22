package tests.smoke;


import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tests.config.SpecFactory;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the shared request specification resolves the versioned
 * API prefix correctly.
 *
 * <p>Uses an endpoint that requires authentication: a 401 proves the request
 * reached the right route and was rejected by the auth layer, which is
 * exactly what an anonymous caller should get. A 404 here would mean the
 * base path is wrong.
 */
class SpecFactorySmokeTest {

    @Test
    @DisplayName("Anonymous  request to a protected route is rejected, not lost")
    void protectedRouteRejectsAnonymousCaller() {

        Response response = given()
                .spec(SpecFactory.anonymous())
                .when()
                .get("/users/me");

        assertThat(response.statusCode())
                .as("Expected an auth rejection, not a routing miss")
                .isIn(401, 403);
    }
}
