package com.budgetapp.tests.api;

import io.qameta.allure.Step;
import io.restassured.response.Response;
import com.budgetapp.tests.config.SpecFactory;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Client for the {@code /users/*} endpoints.
 */
public final class UserApi {

    private UserApi(){}

    /**
     * Returns the authenticated user's profile.
     *
     * <p>Beyond its own coverage, this call doubles as a liveliness probe for an
     * access token: suites that revoke credentials assert against it to prove
     * the revocation actually took effect.
     */
    @Step("Fetch the authenticated  user's profile")
    public static Response getMe(String accessToken) {
        return given()
                .spec(SpecFactory.authenticated(accessToken))
                .when()
                .get("/users/me");
    }

    /**
     * Updates the authenticated user's profile.
     *
     * <p>The endpoint guards writes with an optimistic lock: the caller echoes
     * the version it last read in {@code If-Match}, and the update only lands
     * if that version is still current. Omitting the header is refused rather
     * than treated as "I don't care" — a blind write is the failure mode the
     * lock exists to prevent.
     *
     * @param version value to send in {@code If-Match}, or {@code null} to
     *                omit the header entirely
     */
    @Step("Update the profile with If-Match: {version}")
    public static Response patchProfile(String accessToken, String version, String body) {
        RequestSpecification request = given()
                .spec(SpecFactory.authenticated(accessToken))
                .body(body);

        if (version != null) {
            request.header("If-Match", version);
        }
        return request.when().patch("/users/me");
    }
}
