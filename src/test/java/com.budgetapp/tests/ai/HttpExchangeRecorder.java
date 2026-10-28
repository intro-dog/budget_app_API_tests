package com.budgetapp.tests.ai;


import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the most recent HTTP exchange of the current thread so a failing
 * test can be explained in terms of what the API actually returned.
 *
 * <p>Without this the triage layer sees only an assertion message, which
 * says what was expected but never what arrived — and a model given that
 * much will fill the gap by guessing.
 *
 * <p>State is thread-local because tests may run in parallel, and an
 * exchange recorded by one thread must never surface in another's verdict.
 */
public final class HttpExchangeRecorder implements Filter {

    private static final ThreadLocal<Exchange> LAST = new ThreadLocal<>();

    private static final Set<String> SENSITIVE_KEYS =
            Set.of("password", "token", "secret", "refresh_token");

    /** A single request/response pair, stripped of anything sensitive. */
    public record Exchange(
            String method,
            String path,
            String requestBody,
            int statusCode,
            String responseBody
    ) { }

    @Override
    public Response filter(
            FilterableRequestSpecification request,
            FilterableResponseSpecification responseSpec,
            FilterContext context
    ) {
        // Everything below is best-effort. A recorder that throws would break
        // the very tests it exists to explain — which is exactly what happened
        // when String.valueOf resolved to the char[] overload on a null body
        Response response = context.next(request, responseSpec);

        try {
            LAST.set(new Exchange(
                    request.getMethod(),
                    request.getDerivedPath(),
                    // Headers are never captured: the Authorization header would
                    // otherwise travel into a triage prompt as a usable credential
                    describeBody(request),
                    response.statusCode(),
                    readBody(response)
            ));
        } catch (Exception ignored) {
            LAST.remove();
        }

        return response;
    }

    /**
     * Renders the outgoing payload, covering the form-encoded case.
     *
     * <p>A form submission carries nobody at all — the fields live in the
     * request's parameter map — so reading only the body would describe a
     * login attempt as empty.
     */
    private static String describeBody(FilterableRequestSpecification request) {
        Object body = request.getBody();
        if (body != null) {
            return body.toString();
        }
        if (request.getFormParams() != null
                && !request.getFormParams().isEmpty()
        ) {

            return redact(request.getFormParams());
        }
        return "";
    }

    private static String readBody(Response response) {
        try {
            String body = response.getBody().asString();
            return body == null ? "" : body;
        } catch (Exception e) {
            return "";
        }
    }

    /** The last exchange on this thread, or {@code null} if none was made. */
    public static Exchange last() {
        return LAST.get();
    }

    /** Replaces credential values so they never reach a triage prompt. */
    private static String redact(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> SENSITIVE_KEYS.contains(entry.getKey().toLowerCase())
                ? entry.getKey() + "=***"
                        : entry.getKey() + "=" + entry.getValue()
                ).collect(Collectors.joining(", ", "{", "}"));
    }

    /** Drops the recorded exchange so it cannot leak into the next test. */
    public static void clear() {
        LAST.remove();
    }
}
