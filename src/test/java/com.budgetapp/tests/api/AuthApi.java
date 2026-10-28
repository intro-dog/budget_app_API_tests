package com.budgetapp.tests.api;


import com.budgetapp.tests.config.SpecFactory;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

/**
 * Client for the {@code /auth/*} endpoints.
 *
 * <p>Methods return the raw {@link Response} rather than a parsed model.
 * A large share of this suite asserts on rejection codes and error payloads,
 * so returning a happy-path type would force every negative test to work
 * around the abstraction.
 */
public final class AuthApi {
    private AuthApi(){}


    /**
     * Authenticates via the OAuth2 password flow.
     *
     * <p>The {@code username} form field accepts either an email address or a
     * nickname, which is why the parameter is named neutrally.
     */
    public static Response login(String login, String password){

        return given()
                .spec(SpecFactory.form())
                .formParam("username", login)
                .formParam("password", password)
                .when()
                .post("/auth/login");
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>The token travels in a custom header rather than the body, matching
     * the endpoint's contract.
     */
    public static Response refresh(String refreshToken) {
        return given()
                .spec(SpecFactory.anonymous())
                .header("X-Refresh-Token", refreshToken)
                .when()
                .post("/auth/refresh");
    }

    /** Ends the current session. */
    public static Response logout(String accessToken, String refreshToken) {
        return given()
                .spec(SpecFactory.authenticated(accessToken))
                .header("X-Refresh-Token", refreshToken)
                .when()
                .post("/auth/logout");
    }

    /** Invalidates every token issued before the moment of the call. */
    public static Response forceLogout(String accessToken) {
        return given()
                .spec(SpecFactory.authenticated(accessToken))
                .when()
                .post("/auth/force-logout");
    }
}
