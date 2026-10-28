package com.budgetapp.tests.ai;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Attaches an advisory verdict to every failed test.
 *
 * <p>Implemented as a {@link TestWatcher} rather than an exception handler on
 * purpose: a watcher observes the outcome after the runner has already
 * recorded it, so nothing this class does — including failing outright —
 * can change whether a test passed. Triage stays strictly downstream of the
 * result it describes.
 *
 * <p>Enabled by setting {@code ai.triage=true}. It is off by default because
 * a local model adds seconds to every red test, which is welcome while
 * investigating a build and unwelcome while iterating on a single assertion.
 */
public class AiTriageExtension implements TestWatcher {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("ai.triage", "false"));

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        if (!ENABLED) {
            return;
        }

        try {
            TriageVerdict verdict = AiClient.triage(buildContext(context, cause));
            publish(context, verdict);
        } catch (Exception ignored) {
            // A triage layer that throws would add a second, unrelated error
            // on top of the failure under investigation. Silence is the only
            // acceptable behaviour here.
        }
    }

    private TriageContext buildContext(ExtensionContext context, Throwable cause) {

        HttpExchangeRecorder.Exchange exchange = HttpExchangeRecorder.last();

        return new TriageContext(
                context.getTestClass()
                        .map(Class::getSimpleName).orElse("unknown"),
                displayNameOf(context),
                summarise(cause),
                exchange == null ? null : exchange.method(),
                exchange == null ? null : exchange.path(),
                exchange == null ? null : exchange.requestBody(),
                exchange == null ? -1 : exchange.statusCode(),
                exchange == null ? null : exchange.responseBody()
        );
    }

    /**
     * Prefers the human-written display name, since that is what a reviewer
     * reads in the report and what tells the model what the test intended.
     */
    private String displayNameOf(ExtensionContext context) {
        String displayName = context.getDisplayName();
        return Optional.of(displayName)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> context.getTestMethod()
                        .map(Method::getName)
                        .orElse("unknown"));
    }

    /**
     * Keeps the assertion message and the exception type, drops the stack
     * trace. Frames from the test framework describe how the error surfaced,
     * not why the API misbehaved, and they crowd out the part that matters.
     */
    private String summarise(Throwable cause) {
        if (cause == null) {
            return "No throwable was recorded.";
        }

        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null ? "" : ": " + message);
    }

    /**
     * Writes the verdict where a reviewer will actually see it.
     *
     * <p>{@code publishReportEntry} lands in the JUnit XML that every CI
     * server and IDE already renders, so the verdict travels with the result
     * without this project taking on a reporting dependency.
     */
    private void publish(ExtensionContext context, TriageVerdict verdict) {
        context.publishReportEntry("ai-triage", verdict.toReport());

        System.out.printf(
                """
                                
                        ┌─ AI Triage ─────────────────────────────────
                        │ %s
                        │ Category: %s
                        │ %s
                        └─────────────────────────────────────────────
                        %n""", context.getDisplayName(),
                verdict.category(),
                verdict.explanation());
    }

}
