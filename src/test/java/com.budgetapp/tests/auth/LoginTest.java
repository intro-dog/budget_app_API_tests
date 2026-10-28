package com.budgetapp.tests.auth;

import com.budgetapp.tests.config.Config;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Severity;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.budgetapp.tests.ai.AiTriageExtension;
import com.budgetapp.tests.api.AuthApi;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code POST /auth/login}.
 */
@Epic("Authentication")
@Feature("Login")
@ExtendWith(AiTriageExtension.class)
class LoginTest {

    @Test
    @Story("A registered user obtains a session")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("Valid credentials return an access and refresh token pair")
    void loginWithValidCredentialsReturnsTokenPair() {
        Response response = AuthApi.login(
                Config.seedUserEmail(),
                Config.seedUserPassword()
        );

        assertThat(response.statusCode())
                .as("Login should succeed for the seeded user")
                .isEqualTo(200);

        assertThat(response.jsonPath().getString("access_token"))
                .as("Access token must be present")
                .isNotBlank();

        assertThat(response.jsonPath().getString("refresh_token"))
                .as("Refresh token must be present")
                .isNotBlank();

        assertThat(response.jsonPath().getInt("expires_in"))
                .as("Token lifetime must be a positive number of seconds")
                .isPositive();
    }


    @Test
    @Story("A registered user obtains a session")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("The same user can authenticate with a nickname instead of an email")
    void loginAcceptsNicknameAsUsername(){
        Response response = AuthApi.login(
                Config.seedUserNickname(),
                Config.seedUserPassword()
        );

        assertThat(response.statusCode())
                .as("The username field accepts either identifier")
                .isEqualTo(200);

        assertThat(response.jsonPath().getString("access_token"))
                .isNotBlank();
    }

    @Test
    @Story("Invalid credentials are refused")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A wrong password is rejected without issuing tokens")
    void loginWithWrongPasswordIsRejected() {
        Response response = AuthApi.login(
                Config.seedUserEmail(),
                "BrotheringPassword!"
        );

        assertThat(response.statusCode())
                .as("No token may leak on a failed authentication")
                .isIn(401, 422);
    }


    /**
     * A known-good account and an account that does not exist must be
     * indistinguishable from the outside. Any difference in status code or
     * message lets an attacker enumerate valid users, so this asserts the
     * two responses are identical rather than merely both unsuccessful.
     */
    @Test
    @Story("Invalid credentials are refused")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("An unknown user is rejected identically to a wrong password")
    void loginDoesNotRevealWhetherAccountExists() {
        Response unknownUser = AuthApi.login(
                "notexistantuser@example.com",
                "SomePassword123"
        );

        Response wrongPassword = AuthApi.login(
                Config.seedUserEmail(),
                "IncorrectPassword123"
        );

        assertThat(unknownUser.statusCode())
                .as("Status code must not differ between the two cases")
                .isEqualTo(wrongPassword.statusCode());

        assertThat(unknownUser.jsonPath().getString("detail"))
                .as("Error message must not differ between the two cases")
                .isEqualTo(wrongPassword.jsonPath().getString("detail"));
    }


    @ParameterizedTest(name = "username=\"{0}\" password=\"{1}\"")
    @CsvSource({
            "'', Password123!",
            "example@example.com, ''",
            "'', ''"
    })
    @Story("Invalid credentials are refused")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Blank credentials are rejected before authentication runs")
    void loginRejectsBlankCredentials(String username, String password) {
        Response response = AuthApi.login(username, password);

        assertThat(response.statusCode())
                .as("Empty fields should fail validation or authentication")
                .isIn(401, 422);
    }
}
