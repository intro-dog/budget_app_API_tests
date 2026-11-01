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
     * Registers a new account.
     *
     * <p>Rate limited to three per hour per IP, which is why suites create
     * one account per class rather than one per test.
     */
    @Step("Register {email}")
    public static Response register(
            String name,
            String nickname,
            String email,
            String password
    ) {
        return given()
                .spec(SpecFactory.anonymous())
                .body("""
                      {
                        "name": "%s",
                        "nickname": "%s",
                        "email": "%s",
                        "password": "%s",
                        "default_currency": "EUR"
                      }
                      """.formatted(name, nickname, email, password))
                .when()
                .post("/users/create");
    }


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

    /**
     * Changes the authenticated user's password.
     *
     * <p>Re-authentication with the current password stands in for the
     * optimistic lock here: a stolen access token alone must not be enough to
     * seize an account, and knowing the old password is a stronger guarantee
     * than echoing a version number.
     */
    @Step("Change the password")
    public static Response changePassword(String accessToken, String body) {
        return given()
                .spec(SpecFactory.authenticated(accessToken))
                .body(body)
                .when()
                .post("/users/me/password");
    }

    /**
     * Soft-deletes the authenticated account.
     *
     * <p>Three independent guards apply at once: the caller needs the
     * {@code CAN_SELF_DELETE} permission, must echo the current version in
     * {@code If-Match}, and must re-authenticate with their password inside
     * the body. Deletion is the one action a user cannot undo, so each guard
     * covers a different way of arriving here by accident or by theft.
     *
     * @param version value for {@code If-Match}, or {@code null} to omit it
     */
    @Step("Delete the account with If-Match: {version}")
    public static Response deleteMe(String accessToken, String version, String body) {
        RequestSpecification request = given()
                .spec(SpecFactory.authenticated(accessToken))
                .body(body);

        if (version != null) {
            request.header("If-Match", version);
        }

        return request.when().delete("/users/me");
    }
}
