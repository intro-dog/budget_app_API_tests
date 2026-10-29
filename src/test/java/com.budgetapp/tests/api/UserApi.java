package com.budgetapp.tests.api;

import io.qameta.allure.Step;
import io.restassured.response.Response;
import com.budgetapp.tests.config.SpecFactory;

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
}
