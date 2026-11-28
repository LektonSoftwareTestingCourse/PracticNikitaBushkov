package com.processing.api;

import com.processing.api.support.Api.Card;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.processing.api.support.Api.BIN;
import static com.processing.api.support.Api.authorization;
import static com.processing.api.support.Api.cardManagement;
import static com.processing.api.support.Api.createCard;
import static com.processing.api.support.Api.issuerId;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.rollback;
import static com.processing.api.support.Api.rrn;
import static com.processing.api.support.Api.setStatus;
import static com.processing.api.support.Api.stan;
import static com.processing.api.support.Transactions.internalTransaction;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class AuthorizationApiTest {

    @Test
    void shouldAuthorizeDirectlyAndValidateRequest() {
        Card card = createCard();
        String stan = stan();
        given().spec(authorization())
                .body(internalTransaction(card.pan(), stan, 1_000, card.issuerId()))
                .when().post("/api/internal/authorize")
                .then().statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(stan))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());

        given().spec(authorization()).body("{\"pan\":\"4000001234567890\"}")
                .when().post("/api/internal/authorize")
                .then().statusCode(400).body("status", equalTo(400)).body("error", equalTo("Bad Request"));
        given().spec(authorization()).when().get("/api/internal/authorize").then().statusCode(405);
    }

    @Test
    void shouldReturnNotFoundForUnknownCardDirectly() {
        given().spec(authorization())
                .body(internalTransaction(pan(BIN), stan(), 1_000, issuerId()))
                .when().post("/api/internal/authorize")
                .then().statusCode(404)
                .body("responseCode", equalTo("14"))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("directDeclines")
    void shouldMapDirectAuthorizationFailures(
            String cardStatus, long dailyLimit, long balance, long amount,
            int httpStatus, String code, String reason
    ) {
        Card card = createCard(dailyLimit, 10_000_000, balance);
        if (!"ACTIVE".equals(cardStatus)) {
            setStatus(card.pan(), cardStatus);
        }

        given().spec(authorization())
                .body(internalTransaction(card.pan(), stan(), amount, card.issuerId()))
                .when().post("/api/internal/authorize")
                .then().statusCode(httpStatus)
                .body("responseCode", equalTo(code))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo(reason));
    }

    static Stream<Arguments> directDeclines() {
        return Stream.of(
                Arguments.of("BLOCKED", 10_000_000L, 10_000_000L, 1_000L, 403, "05", "CARD_BLOCKED"),
                Arguments.of("ACTIVE", 1_000_000L, 500L, 1_000L, 422, "51", "INSUFFICIENT_FUNDS"),
                Arguments.of("ACTIVE", 1_000L, 1_000_000L, 5_000L, 400, "61", "EXCEEDS_AMOUNT_LIMIT")
        );
    }

    @Test
    void shouldRollbackApprovedTransactionAndRejectRepeat() {
        Card card = createCard();
        Response approved = given().spec(authorization())
                .body(internalTransaction(card.pan(), stan(), 1_000, card.issuerId()))
                .when().post("/api/internal/authorize")
                .then().statusCode(200).extract().response();
        String rrn = approved.path("rrn");

        given().spec(cardManagement()).get("/api/cards/{pan}", card.pan())
                .then().statusCode(200).body("availableBalance", equalTo(9_999_000));
        given().spec(authorization()).body(rollback(card.pan(), 1_000, rrn))
                .when().post("/api/internal/rollback")
                .then().statusCode(200)
                .body("rrn", equalTo(rrn))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());
        given().spec(cardManagement()).get("/api/cards/{pan}", card.pan())
                .then().statusCode(200).body("availableBalance", equalTo(10_000_000));

        given().spec(authorization()).body(rollback(card.pan(), 1_000, rrn))
                .when().post("/api/internal/rollback")
                .then().statusCode(409)
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
        given().spec(authorization()).body(rollback(card.pan(), 1_000, rrn()))
                .when().post("/api/internal/rollback")
                .then().statusCode(404)
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
        given().spec(authorization()).body(rollback(card.pan(), 0, rrn()))
                .when().post("/api/internal/rollback")
                .then().statusCode(400).body("status", equalTo(400));
    }
}
