package com.processing.api;

import com.processing.api.support.Api.Card;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.BIN;
import static com.processing.api.support.Api.cardManagement;
import static com.processing.api.support.Api.createCard;
import static com.processing.api.support.Api.gateway;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.setStatus;
import static com.processing.api.support.Api.stan;
import static com.processing.api.support.Transactions.transaction;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

class TransactionsApiTest {

    @Test
    void shouldApproveStoreAndDecreaseBalance() {
        Card card = createCard();
        String stan = stan();

        Response approved = given().spec(gateway()).body(transaction(card.pan(), stan, 1_000))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(stan))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0))
                .extract().response();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> given().spec(gateway())
                        .queryParam("pan", card.pan())
                        .when().get("/api/transactions/search")
                        .then().statusCode(200)
                        .body("transactions.stan", hasItem(stan))
                        .body("transactions.find { it.stan == '" + stan + "' }.status",
                                equalTo(approved.path("status")))
                        .body("transactions.find { it.stan == '" + stan + "' }.rrn",
                                equalTo(approved.path("rrn"))));

        given().spec(cardManagement()).get("/api/cards/{pan}", card.pan())
                .then().statusCode(200).body("availableBalance", equalTo(9_999_000));
    }

    @Test
    void shouldDeclineUnknownBinAndMissingCard() {
        String unknownBinStan = stan();
        given().spec(gateway()).body(transaction(pan("999999"), unknownBinStan, 1_000))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("mti", equalTo("0110"))
                .body("stan", equalTo(unknownBinStan))
                .body("rrn", nullValue())
                .body("authCode", nullValue())
                .body("responseCode", equalTo("14"))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));

        given().spec(gateway()).body(transaction(pan(BIN), stan(), 1_000))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("responseCode", equalTo("14"))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("declines")
    void shouldDeclineByCardStateOrLimits(
            String status, long dailyLimit, long balance, long amount, String code, String reason
    ) {
        Card card = createCard(dailyLimit, 10_000_000, balance);
        if (!"ACTIVE".equals(status)) {
            setStatus(card.pan(), status);
        }

        given().spec(gateway()).body(transaction(card.pan(), stan(), amount))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("responseCode", equalTo(code))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo(reason));
    }

    static Stream<Arguments> declines() {
        return Stream.of(
                Arguments.of("BLOCKED", 10_000_000L, 10_000_000L, 1_000L, "05", "CARD_BLOCKED"),
                Arguments.of("INACTIVE", 10_000_000L, 10_000_000L, 1_000L, "05", "CARD_INACTIVE"),
                Arguments.of("EXPIRED", 10_000_000L, 10_000_000L, 1_000L, "54", "CARD_EXPIRED"),
                Arguments.of("ACTIVE", 1_000L, 10_000_000L, 5_000L, "61", "EXCEEDS_AMOUNT_LIMIT"),
                Arguments.of("ACTIVE", 1_000_000L, 500L, 1_000L, "51", "INSUFFICIENT_FUNDS")
        );
    }

    @Test
    void shouldDeclineByMonthlyLimitAndExpiredDate() {
        Card monthly = createCard(10_000_000, 1_000, 10_000_000);
        given().spec(gateway()).body(transaction(monthly.pan(), stan(), 5_000))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("responseCode", equalTo("61"))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));

        Card expired = createCard();
        Map<String, Object> futureRequest = transaction(expired.pan(), stan(), 1_000);
        futureRequest.put("transmissionDateTime",
                Instant.now().plus(4 * 365, ChronoUnit.DAYS).toString());
        given().spec(gateway()).body(futureRequest)
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("responseCode", equalTo("54"))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void shouldValidateGatewayRequest(String field, Object value, String message) {
        Map<String, Object> body = transaction(pan(BIN), stan(), 1_000);
        if (value == null) {
            body.remove(field);
        } else {
            body.put(field, value);
        }

        given().spec(gateway()).body(body)
                .when().post("/api/transactions")
                .then().statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("gateway"))
                .body("timestamp", containsString("T"));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("mti", null, "Field 'mti' is required"),
                Arguments.of("stan", null, "Field 'stan' is required"),
                Arguments.of("pan", null, "Field 'pan' is required"),
                Arguments.of("processingCode", null, "Field 'processingCode' is required"),
                Arguments.of("amount", null, "Field 'amount' is required"),
                Arguments.of("currencyCode", null, "Field 'currencyCode' is required"),
                Arguments.of("transmissionDateTime", null, "Field 'transmissionDateTime' is required"),
                Arguments.of("terminalId", null, "Field 'terminalId' is required"),
                Arguments.of("merchantId", null, "Field 'merchantId' is required"),
                Arguments.of("mcc", null, "Field 'mcc' is required"),
                Arguments.of("acquirerId", null, "Field 'acquirerId' is required"),
                Arguments.of("mti", "0200", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000123456789", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "40000012345678AB", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("amount", -1, "Field 'amount' must be > 0"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "54111", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54A1", "Field 'mcc' must be exactly 4 digits")
        );
    }

    @Test
    void shouldRejectMalformedJson() {
        given().spec(gateway()).body("{\"mti\":")
                .when().post("/api/transactions")
                .then().statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Request body must be valid JSON"))
                .body("serviceName", equalTo("gateway"));
    }

    @Test
    void shouldNotStoreTransactionRejectedByGateway() {
        String pan = pan(BIN);
        given().spec(gateway()).body(transaction(pan, stan(), 0))
                .when().post("/api/transactions")
                .then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> given().spec(gateway())
                        .queryParam("pan", pan)
                        .when().get("/api/transactions/search")
                        .then().statusCode(200)
                        .body("total", equalTo(0))
                        .body("transactions", empty()));
    }
}
