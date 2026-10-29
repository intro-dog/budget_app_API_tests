package com.budgetapp.tests.users;
import com.budgetapp.tests.ai.AiTriageExtension;
import com.budgetapp.tests.api.AuthApi;
import com.budgetapp.tests.api.UserApi;
import com.budgetapp.tests.config.Config;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the optimistic lock on {@code PATCH /users/me}.
 *
 * <p>Without a lock, two clients that read the same profile and both write
 * produce a silent lost update: the second write overwrites the first, and
 * neither client learns anything went wrong. The version counter turns that
 * into a visible 412, which the client can resolve by re-reading.
 */
@Epic("Data integrity")
@Feature("Optimistic locking")
@ExtendWith(AiTriageExtension.class)
public class OptimisticLockTest {
    private String accessToken;

    @BeforeEach
    void authenticate() {
        Response login = AuthApi.login(
                Config.seedUserEmail(),
                Config.seedUserPassword()
        );

        assertThat(login.statusCode())
                .as("Test setup requires a successful login")
                .isEqualTo(200);

        accessToken = login.jsonPath().getString("access_token");
    }

    private int currentVersion() {
        return UserApi.getMe(accessToken).jsonPath().getInt("version");
    }

    private String renameTo(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    @Test
    @Story("A client reads the version it must echo back")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("The profile carries the version a write will need")
    void profileExposeItsVersion() {
        Response profile = UserApi.getMe(accessToken);

        assertThat(profile.statusCode())
                .isEqualTo(200);
        assertThat(profile.jsonPath().getInt("version"))
                .as("A client cannot participate in the lock without this value")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Story("A current version is accepted")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A write with the current version succeeds and advances it by one")
    void successfulUpdateAdvancesTheVersion() {
        int before = currentVersion();

        Response response = UserApi.patchProfile(
                accessToken,
                String.valueOf(before),
                renameTo("Version Probe")
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("version"))
                .as("each accepted write must invalidate the version it consumed")
                .isEqualTo(before + 1);
    }

    /**
     * The scenario the lock exists for. Both callers read the same version,
     * so the second one is operating on a profile that no longer exists in
     * the form it saw. Serving that write would discard the first caller's
     * change without either party noticing.
     */
    @Test
    @Story("A stale version is refused")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A second writer holding a stale version is refused")
    void concurrentWriteWithStaleVersionIsRejected() {
        int sharedVersion = currentVersion();

        Response firstWriter = UserApi.patchProfile(
                accessToken,
                String.valueOf(sharedVersion),
                renameTo("First Writer")
        );

        Response secondWriter = UserApi.patchProfile(
                accessToken,
                String.valueOf(sharedVersion),
                renameTo("Second Writer")
        );

        assertThat(firstWriter.statusCode())
                .as("The first writer held a current version")
                .isEqualTo(200);

        assertThat(secondWriter.statusCode())
                .as("The lost-update window must be closed, not merely narrowed")
                .isEqualTo(412);

        assertThat(secondWriter.jsonPath().getString("detail"))
                .as("The client needs to be told to re-read, not just that it failed")
                .containsIgnoringCase("refresh");
    }

    @Test
    @Story("A missing precondition is refused")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("A writer without If-Match is refused as a missing precondition")
    void writeWithoutIfMatchIsRefused() {
        Response response = UserApi.patchProfile(
                accessToken,
                null,
                renameTo("Unconditional")
        );

        assertThat(response.statusCode())
                .as("428 tells the client to supply a precondition; " +
                        "400 would suggest the request itself was malformed")
                .isEqualTo(428);
    }

    @ParameterizedTest(name = "If-Match: \"{0}\"")
    @ValueSource(strings = {"not-a-number", "1.5", "", "v1", "\"\""})
    @Story("A malformed precondition is refused")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("A version that is not an integer is refused as malformed")
    void malformedIfMatchIsRefused(String ifMatch) {
        Response response = UserApi.patchProfile(
                accessToken,
                ifMatch,
                renameTo("Malformed")
        );

        assertThat(response.statusCode())
                .as("An unparseable precondition is a client error, distinct from a stale one")
                .isIn(400, 428);
    }

    @Test
    @Story("A stale version is refused")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("A version from the future is refused like any other mismatch")
    void futureVersionIsRejected() {
        int response = currentVersion();

        Response result = UserApi.patchProfile(
                accessToken,
                String.valueOf(response + 500),
                renameTo("Time Traveller")
        );

        assertThat(result.statusCode())
                .as("The check is equality, not ordering - a version that was never issued is as one already spent")
                .isEqualTo(412);
    }

    @Test
    @Story("A current version is accepted")
    @Severity(SeverityLevel.MINOR)
    @DisplayName("A quoted version is accepted, matching the If-Match syntax")
    void quotedVersionIsAccepted() {
        Response response = UserApi.patchProfile(
                accessToken,
                "\"" + currentVersion() + "\"",
                renameTo("Quoted Form")
        );

        assertThat(response.statusCode())
                .as("A quoted version is accepted")
                .isEqualTo(200);
    }

    /**
     * Force-logout is an authentication cut-off, tracked separately from the
     * optimistic-lock counter. Bumping both would mean signing out of a lost
     * phone silently invalidated an edit in progress on a laptop — two
     * unrelated concerns colliding.
     */
    @Test
    @Story("Authentication events do not disturb the lock")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Forcing a logout does not advance the profile version")
    void forceLogoutLeavesTheVersionUntouched() throws InterruptedException {
        int before = currentVersion();

        AuthApi.forceLogout(accessToken);
        Thread.sleep(1100);

        // The old token is dead by design, so observe through a fresh session.
        authenticate();

        assertThat(currentVersion())
                .as("The auth cut-off and the lock counter are deliberately independent")
                .isEqualTo(before);
    }
}






















