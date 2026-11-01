package com.budgetapp.tests.users;

import com.budgetapp.tests.ai.AiTriageExtension;
import com.budgetapp.tests.api.AuthApi;
import com.budgetapp.tests.api.UserApi;
import com.budgetapp.tests.data.TestUser;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code POST /users/me/password}.
 *
 * <p>A password change is three operations at once: it re-authenticates the
 * caller, invalidates every outstanding token, and hands the current device a
 * fresh pair so it alone survives. Getting any one of those wrong is a
 * security problem — a change that left old sessions alive would be the whole
 * point of changing a password, undone.
 *
 * <p>Tests here mutate shared state, so each one restores the seeded password
 * afterward. A failure mid-flight would otherwise lock every later test out
 * of the account.
 */
@Epic("Account security")
@Feature("Password change")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(AiTriageExtension.class)
public class ChangePasswordTest {

    private static final String NEW_PASSWORD = "RotatePassword456";

    private TestUser user;

    /** The password the account currently has, tracked across tests. */
    private String currentPassword;

    private String accessToken;
    private String refreshToken;


    @BeforeAll
    void registerDedicatedAccount(){
        user = TestUser.register();
        currentPassword = user.password();
    }

    @BeforeEach
    void authenticate() {
        Response login = AuthApi.login(
                user.email(),
                currentPassword
        );

        assertThat(login.statusCode())
                .as("Setup could not authenticate with the password the previous test left behind - teardown did not restore it")
                .isEqualTo(200);

        accessToken = login.jsonPath().getString("access_token");
        refreshToken = login.jsonPath().getString("refresh_token");
    }

    /**
     * Returns the account to its registered password so the next test can log
     * in. A convenience rather than a correctness requirement: the account
     * belongs to this class alone, so a failure here cannot strand anything
     * outside it.
     */
    @AfterEach
    void normalisePassword()    {
        if (currentPassword.equals(user.password())) return;

        Response login = AuthApi.login(
                user.email(),
                currentPassword
        );
        if (login.statusCode() != 200) return;

        Response restore = UserApi.changePassword(
                login.jsonPath().getString("access_token"),
                changeRequest(currentPassword, user.password())
        );

        if (restore.statusCode() == 200) currentPassword = user.password();
    }


    private String changeRequest(String oldPassword, String newPassword) {
        return """
                {"old_password":"%s", "new_password":"%s"}
                """.formatted(oldPassword, newPassword);
    }

    /**
     * Performs the standard change and confirms it landed before any
     * assertion depends on its effects. Without the guard a refused change
     * yields a null token, and the test fails three steps later on a symptom
     * that says nothing about the cause.
     */
    private Response changePasswordAndConfirm() {
        Response change = UserApi.changePassword(
                accessToken,
                changeRequest(currentPassword, NEW_PASSWORD)
        );

        assertThat(change.statusCode())
                .as("The change must land before its consequences can be observed")
                .isEqualTo(200);

        currentPassword = NEW_PASSWORD;
        return change;
    }

    // ---- The happy path --------------------------------------------

    @Test
    @Story("A verified caller mat rotate the password")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A valid change returns a fresh token pair")
    void successfulChangeReturnsNewTokens() {
        Response change = changePasswordAndConfirm();

        assertThat(change.jsonPath().getString("access_token"))
                .as("The caller must be handed a working session, not asked to log in again on the device they just used")
                .isNotBlank();

        assertThat(change.jsonPath().getString("refresh_token")).isNotBlank();
    }

    @Test
    @Story("A verified caller may rotate the password")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("The new password works and the old one no longer does")
    void credentialsAreaSwapped() {
       String oldPassword = currentPassword;
        changePasswordAndConfirm();

        assertThat(AuthApi.login(
                user.email(),
                NEW_PASSWORD
        ).statusCode())
                .as("The new credential must be live immediately")
                .isEqualTo(200);

        assertThat(AuthApi.login(
               user.email(),
                oldPassword
        ).statusCode())
                .as("A password that still works after being replaced defeats the purpose of replacing it")
                .isEqualTo(401);
    }


    // ---- The happy path --------------------------------------------

    /**
     * The reason anyone changes a password: a session somewhere else must die.
     * Leaving it alive would mean the user locked the door while the intruder
     * was already inside.
     */
    @Test
    @Story("Other sessions are terminated")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("A session on another device is terminated by the change")
    void otherSessionsAreInvalidated() throws InterruptedException {
        String otherDeviceToken = AuthApi.login(
                user.email(),
                currentPassword
        ).jsonPath().getString("access_token");

        assertThat(UserApi.getMe(otherDeviceToken).statusCode())
                .as("The second session must be live before the change or the assertion below proves nothing")
                .isEqualTo(200);

        // The cut-off is compared against a token's `iat`, which JWT records in
        // whole seconds
        Thread.sleep(1100);

        changePasswordAndConfirm();

        assertThat(UserApi.getMe(otherDeviceToken).statusCode())
                .as("Every session but the caller's own must be cut off")
                .isEqualTo(401);
    }


    @Test
    @Story("Other sessions are terminated")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A refresh token issued before the change can no longer be exchanged")
    void oldRefreshTokenIsInvalidated() throws InterruptedException {

        Thread.sleep(1100);

        changePasswordAndConfirm();

        assertThat(AuthApi.refresh(refreshToken).statusCode())
                .as("A surviving refresh token would let a revoked session mint its way back in")
                .isEqualTo(401);
    }


    @Test
    @Story("Other sessions are terminated")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("The token pair returned by the change is immediately usable")
    void returnedTokensSurviveTheirOwnCutOff() {
        Response change = changePasswordAndConfirm();

        String issuedToken = change.jsonPath().getString("access_token");

        assertThat(issuedToken)
                .as("A successful change must return a usable access token")
                        .isNotBlank();

        assertThat(UserApi.getMe(issuedToken).statusCode())
                .as("Tokens minted by the change must postdate the cut-off it sets or the caller " +
                        "signs themselves out")
                .isEqualTo(200);
    }

    // ---- Re-authentication --------------------------------------------

    @Test
    @Story("The caller must prove they know the current password")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("A wrong current password is refused")
    void wrongCurrentPasswordIsRefused() {
        Response response = UserApi.changePassword(
                accessToken,
                changeRequest("NotTheCurrentPassword9", NEW_PASSWORD)
        );

        assertThat(response.statusCode())
                .as("An access token alone must be enough to seize an account")
                .isEqualTo(401);

        assertThat(AuthApi.login(
                user.email(),
                currentPassword
        ).statusCode())
                .as("A refused change must leave the credential untouched")
                .isEqualTo(200);
    }

    @Test
    @Story("The caller must prove they know the current password")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("An anonymous caller cannot change the password")
    void anonymousCallerIsRefuse() {
        Response response = UserApi.changePassword(
                "not-a-valid-token",
                changeRequest(currentPassword, NEW_PASSWORD)
        );

        assertThat(response.statusCode())
                .isIn(400, 401);
    }

    // ---- Password policy --------------------------------------------

    @Test
    @Story("The new password must satisfy the policy")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Reusing the current password is refused")
    void reusingTheCurrentPasswordIsRefused() {
        Response response = UserApi.changePassword(
                accessToken,
                changeRequest(currentPassword, currentPassword)
        );

        assertThat(response.statusCode())
                .as("A change that changed nothing still revokes every other session")
                .isEqualTo(422);
    }


    @ParameterizedTest(name = "new password = \"{0}\"")
    @ValueSource(strings = {"Short1", "alllowercase123", "NoDigitsAtAll", "12345678"})
    @Story("The new password must satisfy the policy")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A weak new password is refused")
    void weakPasswordIsRefused(String weakPassword) {
        Response response = UserApi.changePassword(
                accessToken,
                changeRequest(currentPassword, weakPassword)
        );

        assertThat(response.statusCode())
                .as("The registration policy must hold here too or the change-password route becomes a way around it")
                .isEqualTo(422);
    }


    @Test
    @Story("The new password must satisfy the policy")
    @Severity(SeverityLevel.MINOR)
    @DisplayName("An  unexpected field is refused rather than ignored")
    void unknownFieldIsRefused() {
        Response response = UserApi.changePassword(
                accessToken,
                """
                        {
                             "old_password":"%s",
                             "new_password":"%s",
                             "is_admin":true
                        }
                        """.formatted(
                        currentPassword,
                        NEW_PASSWORD
                )
        );

        assertThat(response.statusCode())
                .as("Silently dropping unknown fields would let a client believe a privilege excalation had taken effect")
                .isEqualTo(422);
    }
}
