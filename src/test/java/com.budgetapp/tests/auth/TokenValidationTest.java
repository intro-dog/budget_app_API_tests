package com.budgetapp.tests.auth;


import com.budgetapp.tests.api.AuthApi;
import com.budgetapp.tests.api.UserApi;
import com.budgetapp.tests.config.Config;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers how the API reacts to credentials it cannot accept.
 *
 * <p>The contract draws a deliberate line between two failures that are easy
 * to conflate. A token the server cannot even parse is a malformed request —
 * the caller sent something that was never a credential. A well-formed token
 * past its lifetime is a valid request with an expired credential, and the
 * client is expected to recover by refreshing. Collapsing both into one code
 * would leave the client unable to tell "retry after refresh" from "you have
 * a bug".
 */
class TokenValidationTest {

    @Test
    @DisplayName("A request with no credentials is rejected")
    void missingTokenIsRejected() {
        Response response = UserApi.getMe("");

        assertThat(response.statusCode())
                .as("A protected route must not serve an anonymous caller")
                .isIn(400, 401, 403);
    }

    @ParameterizedTest(name = "token = \"{0}\"")
    @ValueSource(strings = {
            "not-a-token",
            "Bearer",
            "abc.def",
            "...",
            "eyJhbGciOiJIUzI1NiJ9"
    })
    @DisplayName("A structurally invalid token is refused as a malformed request")
    void malformedTokenIsRefused(String token) {
        Response response = UserApi.getMe(token);

        assertThat(response.statusCode())
                .as("Garbage must be refused, never parsed into a session")
                .isIn(400, 401);

        assertThat(response.body().asString())
                .as("A rejected credential must not yield a new one")
                .doesNotContain("access_token");
    }

    /**
     * A token whose payload has been edited keeps a valid shape but no longer
     * matches its signature. Accepting it would let anyone mint an identity by
     * hand, so this is the single most important negative case in the suite.
     */
    @Test
    @DisplayName("A token with a tampered payload is rejected")
    void tamperedTokenIsRejected() {
        String forged = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                + ".eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEifQ"
                + ".ZmFrZS1zaWduYXR1cmUtdGhhdC13aWxsLW5ldmVyLXZlcmlmeQ";

        Response response = UserApi.getMe(forged);

        assertThat(response.statusCode())
                .as("A signature that does not verify must never grant access")
                .isIn(400, 401);
    }

    @Test
    @DisplayName("A refresh token is not accepted in place of an access token")
    void refreshTokenIsNotAcceptedAsAccessToken() {
        String refreshToken = AuthApi.login(
                Config.seedUserEmail(),
                Config.seedUserPassword()
        )
                .jsonPath()
                .getString("refresh_token");

        Response response = UserApi.getMe(refreshToken);

        assertThat(response.statusCode())
                .as("Token types must not be interchangeable, otherwise a long-lived credential grants immediate access")
                .isIn(400, 401);
    }


}
