package test.com.budgetapp.tests.ai;

/**
 * The triage layer's reading of a single failure.
 *
 * <p>Advisory only. Nothing here affects whether a test passes — the verdict
 * is attached to the report so a human skimming a red CI run knows where to
 * look first, and that is its entire remit.
 *
 * @param category    the kind of problem the failure most likely represents
 * @param explanation one or two sentences a reviewer can act on
 */
public record TriageVerdict(Category category, String explanation) {

    /**
     * Why a test went red.
     *
     * <p>The distinction that matters is who owns the fix. A product defect
     * belongs to the developers, an environment problem to whoever runs the
     * stack, and a flaky test to the person who wrote it — routing a failure
     * to the wrong one of those is the main cost of manual triage.
     */
    public enum Category {
        /**
         * The API behaved differently than its contract promises.
         */
        PRODUCT_DEFECT,

        /**
         * The stack was unreachable, misconfigured or not seeded.
         */
        ENVIRONMENT,

        /**
         * Timing, ordering or shared state made the outcome non-deterministic.
         */
        FLAKY_TEST,

        /**
         * The assertion or request in the test itself is wrong.
         */
        TEST_DEFECT,

        /**
         * The model declined to commit, or was unavailable.
         */
        UNKNOWN
    }

    /**
     * Fallback used whenever triage cannot produce a real answer — the model
     * is offline, the call timed out, or the reply could not be parsed.
     *
     * <p>Returning a neutral verdict rather than throwing keeps the extension
     * silent on the failure path: a broken triage layer must never turn into
     * a second, unrelated error on top of the one being investigated.
     */
    public static TriageVerdict unavailable(String reason) {
        return new TriageVerdict(Category.UNKNOWN, reason);
    }

    /**
     * Renders the verdict for an Allure attachment.
     */
    public String toReport() {
        return """
                Category: %s
                %s
                """.formatted(category, explanation);
    }

}
