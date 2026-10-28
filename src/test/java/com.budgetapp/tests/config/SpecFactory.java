package com.budgetapp.tests.config;


import com.budgetapp.tests.ai.HttpExchangeRecorder;
import io.qameta.allure.restassured.AllureRestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Central factory for request specifications.
 *
 * <p>Every call in the suite is built here, so the base URI, content type and
 * logging behaviour are configured exactly once. Switching the auth scheme or
 * adding a correlation header later touches this file alone instead of every
 * test class.
 */
public final class SpecFactory {

    private SpecFactory() {
    }

    private static RequestSpecBuilder base() {
        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(Config.baseUrl())
                .setBasePath(Config.basePath())
                .addHeader("Accept", "application/json")
                .addFilter(new HttpExchangeRecorder())
                // Captures the full request and response into the report, so a
                // failure can be diagnosed from the artefact alone — without
                // access to the machine that produced it
                .addFilter(new AllureRestAssured());

        if (Config.httpLogging()) {
            builder.log(LogDetail.ALL);
        }
        return builder;
    }

    /**
     * Unauthenticated JSON requests: registration, password reset, health.
     */
    public static RequestSpecification anonymous() {
        return base()
                .setContentType(ContentType.JSON)
                .build();
    }

    /**
     * The login endpoint implements an OAuth2 password flow and therefore
     * consumes {@code application/x-www-form-urlencoded}, not JSON. A separate
     * spec prevents the classic 422 caused by posting a JSON body to it.
     */
    public static RequestSpecification form() {
        return base()
                .setContentType(ContentType.URLENC)
                .build();
    }

    /**
     * Authenticated JSON requests.
     */
    public static RequestSpecification authenticated(String accessToken) {
        return base()
                .setContentType(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();
    }
}
