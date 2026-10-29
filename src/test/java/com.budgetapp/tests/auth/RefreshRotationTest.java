package com.budgetapp.tests.auth;


import com.budgetapp.tests.ai.AiTriageExtension;
import com.budgetapp.tests.config.Config;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.budgetapp.tests.api.AuthApi;
import org.junit.jupiter.api.extension.ExtendWith;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code POST /auth/refresh}.
 *
 * <p>The endpoint implements single-use refresh-token rotation: each call
 * consumes the presented token and issues a brand-new pair. That design
 * limits the damage of a stolen token — the moment either party uses it,
 * the other one's copy becomes worthless.
 */
@Epic("Authentication")
@Feature("Refresh token rotation")
@ExtendWith(AiTriageExtension.class)
class RefreshRotationTest {

    /** Authenticates the seeded user and returns the issued refresh token. */
    private String loginAndGetRefreshToken() {
        Response login = AuthApi.login(
                Config.seedUserEmail(),
                Config.seedUserPassword()
        );

        assertThat(login.statusCode())
                .as("Test setup requires a successful login")
                .isEqualTo(200);

        return login.jsonPath().getString("refresh_token");
    }

    @Test
    @DisplayName("A refresh issues a new pair whose refresh token differs from the old one")
    void refreshIssuesNewTokenPair() {
        String originalRefresh = loginAndGetRefreshToken();

        Response response = AuthApi.refresh(originalRefresh);

        assertThat(response.statusCode())
                .isEqualTo(200);

        assertThat(response.jsonPath().getString("access_token"))
                .as("A refresh must return a usable access token")
                .isNotBlank();

        assertThat(response.jsonPath().getString("refresh_token"))
                .as("Rotation means the client never keeps the same refresh token twice")
                .isNotBlank()
                .isNotEqualTo(originalRefresh);
    }

    /**
     * The core guarantee of rotation. If replaying a spent token still
     * worked, an attacker who copied it once could keep minting sessions
     * indefinitely — rotation would be cosmetic.
     */
    @Test
    @Story("A spent token cannot be replayed")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Replaying a spent refresh token is rejected")
    void spentRefreshTokenCannotBeReused() {
        String originalRefresh = loginAndGetRefreshToken();

        Response firstUse = AuthApi.refresh(originalRefresh);

        assertThat(firstUse.statusCode())
                .as("The first use must succeed before replay can be tested")
                .isEqualTo(200);



        Response replay = AuthApi.refresh(originalRefresh);

        assertThat(replay.statusCode())
                .as("A consumed refresh token must not be accepted again")
                .isEqualTo(401);

        assertThat(replay.jsonPath().getString("access_token"))
                .as("No token may be issued in response to a replayed credentials")
                .isNull();
    }


    @Test
    @DisplayName("The token issued by a refresh can itself be refreshed")
    void rotatedTokenRemainsUsable() {
        String firstRefresh = loginAndGetRefreshToken();

        String secondRefresh = AuthApi.refresh(firstRefresh)
                .jsonPath()
                .getString("refresh_token");

        Response response = AuthApi.refresh(secondRefresh);

        assertThat(response.statusCode())
                .as("Rotation must sustain an ongoing session, not just one hop")
                .isEqualTo(200);
    }


    @Test
    @DisplayName("A structurally invalid refresh token is rejected")
    void malformedRefreshTokenIsRejected() {
        Response response = AuthApi.refresh("not-a-jwt");

        assertThat(response.statusCode())
                .as("Garbage input must be refused, not parsed")
                .isIn(400, 401);
    }


    @Test
    @DisplayName("A request without the refresh header is rejected")
    void missingRefreshTokenIsRejected() {
        Response response = AuthApi.refresh("");

        assertThat(response.statusCode())
                .isIn(400, 401, 422);
    }
}
