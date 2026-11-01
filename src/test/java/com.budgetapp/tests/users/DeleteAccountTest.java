package com.budgetapp.tests.users;

import com.budgetapp.tests.ai.AiTriageExtension;
import com.budgetapp.tests.api.AuthApi;
import com.budgetapp.tests.api.UserApi;
import com.budgetapp.tests.data.TestUser;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code DELETE /users/me}.
 *
 * <p>Deletion is the only irreversible action in the API, and it is guarded
 * three times over: a permission, an optimistic-lock version, and the
 * account password. Each guard blocks a different route to an accidental
 * deletion — a stale client, a stolen token, a misclick — so none of them
 * is redundant.
 *
 * <p>Every test here consumes an account. Registration is capped at three
 * per hour per IP, which is the binding constraint on how many destructive
 * cases this class can hold. The negative cases are therefore grouped onto
 * one account and ordered so the successful deletion runs last.
 */
@Epic("Account security")
@Feature("Account deletion")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(AiTriageExtension.class)
public class DeleteAccountTest {

    private TestUser user;
    private String accessToken;
    private String refreshToken;

    @BeforeAll
    void registerDedicatedAccount() {
        user = TestUser.register();
    }

    @BeforeEach
    void authenticated() {
        Response login = AuthApi.login(
                user.email(),
                user.password()
        );

        assertThat(login.statusCode())
                .as("Setup requires a live account - an earlier test may have deleted it out of order")
                .isEqualTo(200);

        accessToken = login.jsonPath().getString("access_token");
        refreshToken = login.jsonPath().getString("refresh_token");
    }

    private int currentVersion() {
        return UserApi.getMe(accessToken).jsonPath().getInt("version");
    }

    private String deleteRequest(String password, boolean confirm) {
        return """
                {
                    "password":"%s",
                    "confirm":"%b"
                }
                """.formatted(password, confirm);
    }

    // ---- Guards that must hold --------------------------------------------

    @Test
    @Order(1)
    @Story("Deletion requires an explicit confirmation")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A request without confirm:true is refused")
    void unconfirmedDeletionIsRefused() {
        Response response = UserApi.deleteMe(
                accessToken,
                String.valueOf(currentVersion()),
                deleteRequest(user.password(), false)
        );

        assertThat(response.statusCode())
                .as("An explicit flag is what separates a deliberate deletion from a request a client issued by mistake")
                .isEqualTo(422);

        assertThat(UserApi.getMe(accessToken).statusCode())
                .as("A refused deletion must leave the account reachable")
                .isEqualTo(200);
    }

    @Test
    @Order(2)
    @Story("Deletion requires re-authentication")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("A wrong password is refused")
    void wrongPasswordIsRefused() {
        Response response = UserApi.deleteMe(
                accessToken,
                String.valueOf(currentVersion()),
                deleteRequest("NotARealPassword", true)
        );

        assertThat(response.statusCode())
                .as("A stolen access token alone must not be enough to destroy an account")
                .isEqualTo(401);

        assertThat(UserApi.getMe(accessToken).statusCode())
                .as("A refused deletion must leave the account reachable")
                .isEqualTo(200);
    }

    @Test
    @Order(3)
    @Story("Deletion participates in the optimistic lock")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A request without If-Match is refused")
    void deletionWithoutIfMatchIsRefused() {
        Response response = UserApi.deleteMe(
                accessToken,
                null,
                deleteRequest(user.password(), true)
        );

        assertThat(response.statusCode())
                .as("428 asks the client for a precondition, 400 should be claim")
                .isEqualTo(428);
    }

    @Test
    @Order(4)
    @Story("Deletion participates in the optimistic lock")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A stale version is refused")
    void staleVersionIsRefused() {
        Response response = UserApi.deleteMe(
                accessToken,
                String.valueOf(currentVersion() + 100),
                deleteRequest(user.password(), true)
        );

        assertThat(response.statusCode())
                .as("A client working from a version it no longer holds an outdated picture of the account it is destroying")
                .isEqualTo(412);
    }

    @Test
    @Order(5)
    @Story("Deletion requires re-authentication")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("An anonymous caller cannot delete the account")
    void anonymousCallerIsRefused() {
        Response response = UserApi.deleteMe(
                "not-a-valid-token",
                "1",
                deleteRequest(user.password(), true)
        );

        assertThat(response.statusCode()).isIn(400, 401);
    }

    // ---- The deletion itself --------------------------------------------

    /**
     * Runs last: it consumes the account every preceding test relies on.
     *
     * <p>Asserts the three consequences separately, because a deletion that
     * only half-applies is worse than one that fails outright — the user
     * believes they are gone while their session, or their identifier,
     * lives on.
     */
    @Test
    @Order(99)
    @Story("A confirmed deletion takes effect")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("A confirmed deletion revokes the session and frees the identifiers")
    void confirmedDeletionTakesEffect() throws InterruptedException {
        int version = currentVersion();

        // The delete stamps `use_token_since` with the current second, and the
        // cut-off comparison is inclusive. Without separating the two, the
        // token under test sits exactly on the boundary
        Thread.sleep(1100);

        Response deletion = UserApi.deleteMe(
                accessToken,
                String.valueOf(version),
                deleteRequest(
                        user.password(),
                        true
                )
        );

        assertThat(deletion.statusCode())
                .as("A fully-formed deletion request must succeed")
                .isEqualTo(204);

        assertThat(UserApi.getMe(accessToken).statusCode())
                .as("The session must die with the account, not outlive it")
                .isIn(401, 404);

        assertThat(AuthApi.refresh(refreshToken).statusCode())
                .as("A surviving refresh token would let a deleted account mint its way back in")
                .isIn(401, 404);

        assertThat(AuthApi.login(user.email(), user.password()).statusCode())
                .as("A deleted account must not authenticate")
                .isEqualTo(401);
    }


}
