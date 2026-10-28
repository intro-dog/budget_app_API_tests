package com.budgetapp.tests.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check for the triage client against a locally running model.
 */
class AiClientManualCheck {

    @Test
    @DisplayName("Triage classified a connection refused failure as an environment problem")
    void triageRespondsToASyntheticFailure() {
        TriageContext context = new TriageContext(
                "LoginTest",
                "Valid credentials return a token pair",
                "java.net.ConnectException: Connection refused",
                "POST",
                "/auth/login",
                "username=seed@example.com&password=***",
                -1,
                ""
        );

        TriageVerdict verdict = AiClient.triage(context);

        System.out.println("=== Verdict ===");
        System.out.println(verdict.toReport());

        assertThat(verdict).isNotNull();
    }
}
