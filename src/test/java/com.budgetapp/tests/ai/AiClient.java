package com.budgetapp.tests.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


/**
 * Talks to a locally running Ollama instance to classify a test failure.
 *
 * <p>Everything about this class is built around one rule: triage must never
 * become a second problem on top of the failure it is explaining. No method
 * throws, no call blocks the run for long, and an unreachable model produces
 * a neutral verdict rather than an error.
 *
 * <p>The model runs on the developer's machine, so request and response
 * payloads from the system under test never leave it. That is what makes this
 * safe to point at an API whose data would be unacceptable to send to a
 * third-party service.
 */
public final class AiClient {

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL = "llama3.2:3b";

    /**
     * Generous enough for a small model on a cold start, short enough that a
     * hung service cannot stall a CI job. A developer waiting on a red build
     * would rather see no verdict than wait a minute for one.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private AiClient() {
    }


    /**
     * Returns the model's reading of the failure, or a neutral verdict when
     * triage is unavailable for any reason.
     */
    public static TriageVerdict triage(TriageContext context) {
        try {
            String reply = ask(buildPrompt(context));
            return parse(reply);
        } catch (Exception e) {
            // Deliberately broad: a triage layer that propagates its own
            // failures would obscure the test failure it exists to clarify.
            return TriageVerdict.unavailable(
                    "Triage unavailable: " + e.getClass().getSimpleName()
                            + "- is Ollama running on localhost:11434?"
            );
        }
    }

    private static String buildPrompt(TriageContext context) {
        String exchange = context.hasHttpExchange()
                ? """
                    Request: %s %s
                    Payload: %s
                    Status: %d
                    Response: %s
                """.formatted(
                context.httpMethod(),
                context.endpoint(),
                context.responseBody(),
                context.statusCode(),
                context.requestBody()
        ) : "No HTTP call was completed before the failure.\n";


        return """
                You are triaging a failed automated API test. Decide who most \
                likely owns the fix.
                               
                The test's expectation is not authoritative. It is a claim about \
                the contract that may itself be wrong. Judge it against what the \
                endpoint plausibly promises, not against the fact that it failed.
                               
                Categories:
                PRODUCT_DEFECT — the response contradicts what the endpoint \
                documents. Choose this only when the observed behaviour is \
                indefensible, not merely different from what the test wanted.
                ENVIRONMENT    — the stack was unreachable, misconfigured or unseeded
                FLAKY_TEST     — timing, ordering or shared state made the result unstable
                TEST_DEFECT    — the expectation or the request built by the test is \
                wrong. A successful operation that the test marked as failure \
                belongs here.
                               
                Test:    %s.%s
                Failure: %s
                               
                %s
                Answer in exactly this format, nothing else:
                CATEGORY: <one category from the list>
                REASON: <at most two sentences, concrete and specific>
                """.formatted(
                context.testClass(),
                context.testName(),
                context.failureMessage(),
                exchange);
    }

    private static String ask(String prompt) throws Exception {
        String body = """
                    {
                        "model": "%s",
                        "prompt": %s,
                        "stream": false,
                        "options": { "temperature": 0.1 }
                    }
                """.formatted(MODEL, quote(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Ollama returned HTTP " + response.statusCode()
            );
        }

        return extractField(response.body());
    }

    /**
     * Reads the verdict out of the model's reply.
     *
     * <p>Small models drift from any output format eventually, so this scans
     * for the category keyword anywhere in the text instead of insisting on
     * exact structure. A reply that mentions no category at all is treated as
     * no answer, which is more honest than defaulting to a guess.
     */
    private static TriageVerdict parse(String reply) {
        String[] lines = reply.split("\\R");

        TriageVerdict.Category category = TriageVerdict.Category.UNKNOWN;

        for (String line : lines) {
            String upper = line.toUpperCase();
            if (!upper.contains("CATEGORY")) {
                continue;
            }

            for (TriageVerdict.Category candidate : TriageVerdict.Category.values()) {
                if (candidate != TriageVerdict.Category.UNKNOWN
                        && upper.contains(candidate.name())) {
                    category = candidate;
                    break;
                }
            }
        }

        String reason = reply;
        int marker = reply.toUpperCase().indexOf("REASON");
        if (marker >= 0) {
            reason = reply.substring(marker + "REASON:".length());
        }

        return new TriageVerdict(category, reason.trim());
    }

    /**
     * Minimal JSON string escaping — enough for a prompt, no dependency needed.
     */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Pulls one top-level string value out of a flat JSON object.
     */
    private static String extractField(String json) {
        String key = "\"" + "response" + "\":";
        int start = json.indexOf(key);

        if (start < 0) {
            throw new IllegalStateException("Field '" + "response" + "' missing in reply");
        }
        start = json.indexOf('"', start + key.length()) + 1;

        StringBuilder value = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    default -> value.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }
}
