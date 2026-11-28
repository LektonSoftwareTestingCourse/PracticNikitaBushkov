package com.processing.api;

import com.processing.api.support.Api.Card;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.processing.api.support.Api.createCard;
import static com.processing.api.support.Api.gateway;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.setStatus;
import static com.processing.api.support.Api.stan;
import static com.processing.api.support.Api.switchService;
import static com.processing.api.support.Transactions.transaction;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

class SwitchApiTest {

    @Test
    void shouldRouteThroughSwitchAndStoreAsynchronously() {
        Card card = createCard();
        String stan = stan();
        given().spec(switchService()).body(transaction(card.pan(), stan, 1_000))
                .when().post("/api/internal/route")
                .then().statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                .body("stan", equalTo(stan))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> given().spec(gateway())
                        .queryParam("pan", card.pan())
                        .when().get("/api/transactions/search")
                        .then().statusCode(200).body("transactions.stan", hasItem(stan)));

        given().spec(switchService()).body(transaction(pan("999999"), stan(), 1_000))
                .when().post("/api/internal/route")
                .then().statusCode(200)
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
        Card blocked = createCard();
        setStatus(blocked.pan(), "BLOCKED");
        given().spec(switchService()).body(transaction(blocked.pan(), stan(), 1_000))
                .when().post("/api/internal/route")
                .then().statusCode(200)
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }
}
