package com.budgetapp.tests.ai;

/**
 * Everything the triage layer knows about a single failed test.
 *
 * <p>Deliberately narrow: only the fields a human would actually read when
 * deciding whether a red test means a product defect, a broken environment
 * or a flake. Sending more context would cost tokens without improving the
 * verdict, and sending less would leave the model guessing.
 *
 * <p>No credentials are captured. The request body is recorded, but
 * {@code Authorization} headers never are — a triage prompt travels to a
 * third-party service and must not carry anything that grants access.
 *
 * @param testClass     simple name of the failing test class
 * @param testName      the {@code @DisplayName}, falling back to the method name
 * @param failureMessage the assertion error text as the runner reported it
 * @param httpMethod    HTTP verb of the last call, or {@code null} if the
 *                      failure happened before any request was made
 * @param endpoint      path of the last call, without the base URI
 * @param requestBody   payload that was sent, already stripped of secrets
 * @param statusCode    status actually returned, or {@code -1} when unknown
 * @param responseBody  raw response text, truncated to stay within budget
 */
public record TriageContext(
        String testClass,
        String testName,
        String failureMessage,
        String httpMethod,
        String endpoint,
        String requestBody,
        int statusCode,
        String responseBody
) {
    /**
     * Upper bound on any single captured payload.
     *
     * <p>Chosen so a verbose validation error survives intact while a
     * runaway list response cannot inflate the prompt. Truncation is marked
     * explicitly so the model never mistakes a cut-off body for a complete one.
     */
    private static final int MAX_BODY_LENGTH = 2_000;

    public TriageContext {
        requestBody = truncate(requestBody);
        responseBody = truncate(responseBody);
    }


    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_LENGTH ) {
            return body;
        }
        return body.substring(0, MAX_BODY_LENGTH) + "\n...[truncated]";
    }

    /** True when the failure carries no HTTP exchange to reason about. */
    public boolean hasHttpExchange() {
        return statusCode > 0;
    }
}
