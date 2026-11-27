package com.processing.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.processing.api.support.Api.BIN;
import static com.processing.api.support.Api.gateway;
import static com.processing.api.support.Api.issuerId;
import static com.processing.api.support.Api.logger;
import static com.processing.api.support.Api.mcc;
import static com.processing.api.support.Api.merchantId;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.stan;
import static com.processing.api.support.Transactions.storedApproved;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

class LoggerApiTest {

    @Test
    void shouldStoreLogIdempotently() {
        String pan = pan(BIN);
        String stan = stan();
        UUID id = UUID.randomUUID();
        Map<String, Object> first = storedApproved(
                id, pan, stan, merchantId(), mcc(), issuerId(), Instant.now());

        given().spec(logger()).body(first)
                .when().post("/api/internal/log")
                .then().statusCode(201)
                .body("id", equalTo(id.toString()))
                .body("status", equalTo("stored"));
        given().spec(logger()).body(first)
                .when().post("/api/internal/log")
                .then().statusCode(200)
                .body("id", equalTo(id.toString()))
                .body("status", equalTo("APPROVED"));

        Map<String, Object> retry = new LinkedHashMap<>(first);
        retry.put("createdAt", Instant.now().plusSeconds(10).toString());
        given().spec(logger()).body(retry)
                .when().post("/api/internal/log")
                .then().statusCode(200)
                .body("createdAt", equalTo(first.get("createdAt")));

        Map<String, Object> conflict = new LinkedHashMap<>(first);
        conflict.put("amount", 999_999);
        given().spec(logger()).body(conflict)
                .when().post("/api/internal/log")
                .then().statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("message", containsString(id.toString()));

        given().spec(gateway()).queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].id", equalTo(id.toString()));
    }

    @Test
    void shouldValidateInternalLogRequest() {
        Map<String, Object> body = storedApproved(
                UUID.randomUUID(), pan(BIN), stan(), merchantId(), mcc(), issuerId(), Instant.now());
        body.remove("stan");
        given().spec(logger()).body(body)
                .when().post("/api/internal/log")
                .then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", containsString("stan"));

        body.put("stan", stan());
        body.put("pan", "123");
        given().spec(logger()).body(body)
                .when().post("/api/internal/log")
                .then().statusCode(400).body("message", containsString("pan"));

        given().spec(logger()).body("{\"id\":")
                .when().post("/api/internal/log")
                .then().statusCode(400)
                .body("error", equalTo("Invalid request body"));
        given().spec(logger()).when().get("/api/internal/log")
                .then().statusCode(405).body("error", equalTo("Method not allowed"));
    }
}
