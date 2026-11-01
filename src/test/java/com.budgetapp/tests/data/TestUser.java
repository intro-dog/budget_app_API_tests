package com.budgetapp.tests.data;


import com.budgetapp.tests.api.UserApi;
import io.restassured.response.Response;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A freshly registered account, owned by whichever suite created it.
 *
 * <p>Suites that mutate credentials cannot share the seeded user: a test
 * that changes a password leaves every later test holding a credential the
 * account no longer accepts. Registering a dedicated account removes the
 * dependency on teardown succeeding — the account is disposable, so a test
 * that fails halfway through damages nothing but itself.
 *
 * <p>Accounts are not deleted afterwards. Self-deletion requires the current
 * password, the current version and an explicit confirmation, none of which
 * a failed test reliably knows; soft-deleted rows also scrub their email and
 * nickname, so abandoned accounts cost nothing but a row.
 */
public record TestUser (String email, String nickname, String password){

    /** Satisfies the registration policy: 8+ characters, uppercase, digit. */
    private static final String PASSWORD = "Testpassword123";

    /**
     * Registers an account whose identifiers cannot collide with a concurrent
     * run. The nickname pattern permits lowercase alphanumerics with dots,
     * underscores and dashes only, so the random suffix stays in that set.
     */
    public static TestUser register() {
        String suffix = Long.toString(
                ThreadLocalRandom.current().nextLong(1_000_000L), 36
        );

        String nickname = "qa." + suffix;
        String email = "qa." + suffix + "@example.com";

        Response response = UserApi.register(
                "QA Tester",
                nickname,
                email,
                PASSWORD
        );

        if (response.statusCode() != 201) {
            throw new IllegalStateException(
                    "Could not register a test account - HTTP " + response.statusCode() + ": "
                    + response.body().asString() + ". Registration is limited to three per hour per IP.)"
            );
        }
        return new TestUser(email, nickname, PASSWORD);
    }
}
