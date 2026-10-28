package test.com.budgetapp.tests.auth;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import test.com.budgetapp.tests.api.AuthApi;
import test.com.budgetapp.tests.api.UserApi;
import test.com.budgetapp.tests.config.Config;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code POST /auth/force-logout}.
 *
 * <p>Force-logout is the "sign out everywhere" control a user reaches for
 * after losing a device. It records a cut-off timestamp on the account;
 * every token minted before that instant is refused from then on, without
 * the server having to track individual sessions.
 */
class ForceLogoutTest {

    /** A freshly issued token pair for the seeded user. */
    private record Session(String accessToken, String refreshToken) {}

    private Session login() {
        Response response = AuthApi.login(
                Config.seedUserEmail(),
                Config.seedUserPassword()
        );

        assertThat(response.statusCode())
                .as("Test setup requires a successful login")
                .isEqualTo(200);

        return new Session(
                response.jsonPath().getString("access_token"),
                response.jsonPath().getString("refresh_token")
        );
    }

    @Test
    @DisplayName("Force-logout is accepted for an authenticated caller")
    void forceLogoutSucceeds() {
        Session session = login();

        Response response = AuthApi.forceLogout(session.accessToken());

        assertThat(response.statusCode())
                .isIn(200, 204);
    }

    /**
     * The access token used to trigger the logout is itself older than the
     * cut-off, so it has to stop working too. A system that spared the
     * caller's own credential would leave the compromised session alive on
     * the very device the user was trying to protect.
     */
    @Test
    @DisplayName("The access token that triggered the logout stops working")
    void accessTokenIsInvalidatedAfterForceLogout() {
        Session session = login();

        assertThat(UserApi.getMe(session.accessToken()).statusCode())
                .as("Token must be valid before the logout to make the test meaningful")
                .isEqualTo(200);



        AuthApi.forceLogout(session.accessToken());

        Response afterLogout = UserApi.getMe(session.accessToken());

        assertThat(afterLogout.statusCode())
                .as("A token issued before the cut-off must be refused")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("A refresh token issued before the logout can no longer be exchanged")
    void refreshTokenIsInvalidatedAfterForceLogout() {
        Session session = login();

        AuthApi.forceLogout(session.accessToken());

        Response refresh = AuthApi.refresh(session.refreshToken());

        assertThat(refresh.statusCode())
                .as("Revocation must cover refresh tokens, otherwise the session survives")
                .isEqualTo(401);

        // Asserting on the raw body avoids JsonPath's lenient lookup, which can
        // resolve a missing key to an unrelated value elsewhere in the response.
        assertThat(refresh.body().asString())
                .as("No credential may be minted from a revoked token")
                .doesNotContain("access_token");
    }


    /**
     * Sessions opened on other devices share the same account and are
     * therefore older than the cut-off as well — which is the entire point
     * of the feature.
     */
    @Test
    @DisplayName("Concurrent sessions are revoked, not just the calling one")
    void allSessionsAreRevokedTogether() {
        Session firstDevice = login();
        Session secondDevice = login();

        AuthApi.forceLogout(firstDevice.accessToken());

        assertThat(UserApi.getMe(secondDevice.accessToken()).statusCode())
                .as("A session opened on another device must be revoked too")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("Logging in again after a force-logout yields a working session")
    void loginAfterForceLogoutRestoresAccess() {
        Session oldSession = login();
        AuthApi.forceLogout(oldSession.accessToken());

        Session newSession = login();

        assertThat(UserApi.getMe(newSession.accessToken()).statusCode())
                .as("The cut-off must not lock the user out permanently")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Force-logout requires authentication")
    void forceLogoutRejectsAnonymousCaller() {
        Response response = AuthApi.forceLogout("not-valid-token");

        assertThat(response.statusCode())
                .as("An unauthenticated caller must not be able to revoke sessions")
                .isIn(400, 401);
    }

}
