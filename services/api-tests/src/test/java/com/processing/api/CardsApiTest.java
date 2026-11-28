package com.processing.api;

import com.processing.api.support.Api.Card;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.BIN;
import static com.processing.api.support.Api.bulk;
import static com.processing.api.support.Api.cardBody;
import static com.processing.api.support.Api.cardManagement;
import static com.processing.api.support.Api.createCard;
import static com.processing.api.support.Api.gateway;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.reserve;
import static com.processing.api.support.Api.rollback;
import static com.processing.api.support.Api.rrn;
import static com.processing.api.support.Api.setStatus;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardsApiTest {

    @Test
    void shouldCreateReadAndDeleteCard() {
        Response created = given()
                .spec(gateway())
                .body(cardBody(BIN, 1_000_000, 10_000_000, 5_000_000))
                .when()
                .post("/api/cards")
                .then()
                .statusCode(201)
                .header("Content-Type", containsString("application/json"))
                .body("pan", matchesPattern("400000\\d{10}"))
                .body("status", equalTo("ACTIVE"))
                .body("dailyLimit", equalTo(1_000_000))
                .body("availableBalance", equalTo(5_000_000))
                .body("expiryDate", equalTo(YearMonth.now().plusYears(3).toString()))
                .extract().response();
        String pan = created.path("pan");
        assertTrue(isLuhnValid(pan), "PAN must pass the Luhn check");

        given().spec(gateway()).get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .header("X-Cache", equalTo("MISS"))
                .body("id", equalTo(created.path("id")))
                .body("pan", equalTo(pan));
        given().spec(gateway()).get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .header("X-Cache", equalTo("HIT"));

        given().spec(gateway()).get("/api/cards/{pan}", pan("400000"))
                .then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"));
        given().spec(gateway()).get("/api/cards/{pan}", "123")
                .then().statusCode(400)
                .body("error", equalTo("ConstraintViolationException"));

        given().spec(gateway()).delete("/api/cards/{pan}", "123")
                .then().statusCode(400)
                .body("error", equalTo("ConstraintViolationException"));

        given().spec(gateway()).delete("/api/cards/{pan}", pan).then().statusCode(204);
        given().spec(gateway()).delete("/api/cards/{pan}", pan)
                .then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));

        given().spec(gateway()).body(cardBody("999999", 1_000_000, 10_000_000, 5_000_000))
                .when().post("/api/cards")
                .then().statusCode(404).body("error", equalTo("BinNotFoundException"));
    }

    @Test
    void shouldUpdateCardAndReturnEffect() {
        Card card = createCard();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "BLOCKED");
        patch.put("dailyLimit", 5_000);
        patch.put("monthlyLimit", 50_000);
        patch.put("availableBalance", 7_000);

        given().spec(gateway()).body(patch)
                .when().patch("/api/cards/{pan}", card.pan())
                .then().statusCode(200)
                .body("pan", equalTo(card.pan()))
                .body("status", equalTo("BLOCKED"))
                .body("dailyLimit", equalTo(5_000))
                .body("availableBalance", equalTo(7_000));

        given().spec(cardManagement()).get("/api/cards/{pan}", card.pan())
                .then().statusCode(200)
                .body("status", equalTo("BLOCKED"))
                .body("availableBalance", equalTo(7_000));

        given().spec(gateway()).body(Map.of("dailyLimit", -1))
                .when().patch("/api/cards/{pan}", card.pan())
                .then().statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"));
        given().spec(gateway()).body(Map.of("status", "BLOCKED"))
                .when().patch("/api/cards/{pan}", pan("400000"))
                .then().statusCode(404).body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void shouldListCardsByBinStatusAndPeriod() {
        Card card = createCard();
        given().spec(gateway())
                .queryParam("bin", BIN)
                .queryParam("status", "ACTIVE")
                .queryParam("limit", 100)
                .when().get("/api/cards")
                .then().statusCode(200)
                .body("cards.bin", everyItem(equalTo(BIN)))
                .body("cards.status", everyItem(equalTo("ACTIVE")))
                .body("cards.size()", greaterThanOrEqualTo(1));

        Instant created = Instant.parse(card.createdAt());
        given().spec(gateway())
                .queryParam("issuerId", card.issuerId())
                .queryParam("startDate", created.minusSeconds(1).toString())
                .queryParam("endDate", created.plusSeconds(1).toString())
                .queryParam("limit", 100)
                .when().get("/api/cards")
                .then().statusCode(200)
                .body("cards.id", hasItem(card.id()));

        given().spec(gateway()).queryParam("offset", 100_000)
                .when().get("/api/cards")
                .then().statusCode(200).body("cards", hasSize(0));
    }

    @ParameterizedTest
    @MethodSource("invalidCardQueries")
    void shouldRejectInvalidCardQuery(String name, String value, int status, String error, String message) {
        given().spec(gateway()).queryParam(name, value)
                .when().get("/api/cards")
                .then().statusCode(status)
                .body("error", equalTo(error))
                .body("message", containsString(message))
                .body("serviceName", equalTo("card-management"));
    }

    static Stream<Arguments> invalidCardQueries() {
        return Stream.of(
                Arguments.of("limit", "0", 400, "ConstraintViolationException", "greater than 0"),
                Arguments.of("limit", "10001", 413, "TooLargeLimitException", "10000"),
                Arguments.of("offset", "-1", 400, "ConstraintViolationException", "negative"),
                Arguments.of("bin", "123", 400, "ConstraintViolationException", "BIN number")
        );
    }

    @Test
    void shouldGenerateCardsAndValidateRequest() {
        given().spec(gateway()).body(Map.of("count", 2, "bins", List.of("400002")))
                .when().post("/api/cards/generate")
                .then().statusCode(201)
                .body("generated", equalTo(2))
                .body("cards", hasSize(2))
                .body("cards.bin", everyItem(equalTo("400002")))
                .body("cards.status", everyItem(oneOf("ACTIVE", "INACTIVE", "BLOCKED", "EXPIRED")));

        given().spec(gateway()).body(Map.of("count", 0, "bins", List.of("400002")))
                .when().post("/api/cards/generate")
                .then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"));
        given().spec(gateway()).body(Map.of("count", 10_001, "bins", List.of("400002")))
                .when().post("/api/cards/generate")
                .then().statusCode(400).body("error", equalTo("CardGenerationLimitException"));
        given().spec(gateway()).body(Map.of("count", 1, "bins", List.of("999999")))
                .when().post("/api/cards/generate")
                .then().statusCode(404).body("error", equalTo("BinNotFoundException"));
    }

    @Test
    void shouldReserveAndRollbackFunds() {
        Card card = createCard();
        String rrn = rrn();

        given().spec(gateway()).body(reserve(1_000, rrn))
                .when().post("/api/cards/{pan}/reserve", card.pan())
                .then().statusCode(200).body("availableBalance", equalTo(9_999_000));
        given().spec(gateway()).body(rollback(card.pan(), 1_000, rrn))
                .when().post("/api/cards/{pan}/rollback", card.pan())
                .then().statusCode(200).body("availableBalance", equalTo(10_000_000));
        given().spec(gateway()).body(rollback(card.pan(), 1_000, rrn))
                .when().post("/api/cards/{pan}/rollback", card.pan())
                .then().statusCode(409)
                .body("error", equalTo("RollbackAlreadySatisfiedException"))
                .body("serviceName", equalTo("card-management"));

        given().spec(gateway()).body(rollback(card.pan(), 1_000, rrn()))
                .when().post("/api/cards/{pan}/rollback", card.pan())
                .then().statusCode(404).body("error", equalTo("ReservationNotFoundException"));
        given().spec(gateway()).body(rollback(card.pan(), 0, rrn()))
                .when().post("/api/cards/{pan}/rollback", card.pan())
                .then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"));
        given().spec(gateway()).body(reserve(1_000, rrn()))
                .when().post("/api/cards/{pan}/reserve", pan("400000"))
                .then().statusCode(404).body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void shouldRejectReserveWithoutFundsOrDuplicateRrn() {
        Card poor = createCard(1_000_000, 10_000_000, 500);
        given().spec(gateway()).body(reserve(1_000, rrn()))
                .when().post("/api/cards/{pan}/reserve", poor.pan())
                .then().statusCode(402)
                .body("error", equalTo("InsufficientFundsException"))
                .body("message", equalTo("Not enough funds on this account"));
        given().spec(cardManagement()).get("/api/cards/{pan}", poor.pan())
                .then().statusCode(200).body("availableBalance", equalTo(500));

        Card card = createCard();
        String rrn = rrn();
        given().spec(gateway()).body(reserve(1_000, rrn))
                .when().post("/api/cards/{pan}/reserve", card.pan())
                .then().statusCode(200);
        given().spec(gateway()).body(reserve(1_000, rrn))
                .when().post("/api/cards/{pan}/reserve", card.pan())
                .then().statusCode(409)
                .body("error", equalTo("ReservationAlreadyExistsException"));
        given().spec(cardManagement()).get("/api/cards/{pan}", card.pan())
                .then().statusCode(200).body("availableBalance", equalTo(9_999_000));
    }

    @ParameterizedTest
    @MethodSource("invalidReserveRequests")
    void shouldRejectInvalidReserveRequest(long amount, String rrn, String message) {
        Card card = createCard();
        given().spec(gateway()).body(reserve(amount, rrn))
                .when().post("/api/cards/{pan}/reserve", card.pan())
                .then().statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString(message));
    }

    static Stream<Arguments> invalidReserveRequests() {
        return Stream.of(
                Arguments.of(0, "123456789012", "must be greater than 0"),
                Arguments.of(-1, "123456789012", "must be greater than 0"),
                Arguments.of(100, "123", "RRN number must contain exactly 12 digits"),
                Arguments.of(100, "12345678901A", "RRN number must contain exactly 12 digits")
        );
    }

    @Test
    void shouldBulkUpdateByPansAndRejectAmbiguousRequest() {
        Card first = createCard();
        Card second = createCard();
        given().spec(gateway()).body(bulk(List.of(first.pan(), second.pan()), "BLOCKED"))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(200).body("updated", equalTo(2));
        given().spec(cardManagement()).get("/api/cards/{pan}", first.pan())
                .then().statusCode(200).body("status", equalTo("BLOCKED"));
        given().spec(cardManagement()).get("/api/cards/{pan}", second.pan())
                .then().statusCode(200).body("status", equalTo("BLOCKED"));

        given().spec(gateway()).body(Map.of(
                        "pans", List.of(first.pan()), "bins", List.of(BIN), "status", "BLOCKED"))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(400).body("message", equalTo("Only one of bin or pans must be provided"));
        given().spec(gateway()).body(Map.of("status", "BLOCKED"))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(400).body("message", equalTo("Either bin or pans must be provided"));
    }

    @ParameterizedTest
    @MethodSource("invalidCreateRequests")
    void shouldRejectInvalidCardCreate(String field, Object value, String message) {
        Map<String, Object> body = cardBody(BIN, 1_000_000, 10_000_000, 5_000_000);
        body.put(field, value);
        given().spec(gateway()).body(body)
                .when().post("/api/cards")
                .then().statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString(message));
    }

    static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                Arguments.of("bin", "12345", "BIN number must contain exactly 6 digits"),
                Arguments.of("cardholderName", "ivan", "Cardholder name"),
                Arguments.of("cardholderName", "IVAN1", "Cardholder name"),
                Arguments.of("currencyCode", "64", "The length must be exactly 3 symbols"),
                Arguments.of("currencyCode", "64A", "Value can contain digits only"),
                Arguments.of("dailyLimit", -1, "Value can not be negative"),
                Arguments.of("monthlyLimit", -1, "Value can not be negative")
        );
    }

    @Test
    void shouldRejectBlockedCardReserve() {
        Card card = createCard();
        setStatus(card.pan(), "BLOCKED");
        given().spec(gateway()).body(reserve(1_000, rrn()))
                .when().post("/api/cards/{pan}/reserve", card.pan())
                .then().statusCode(409)
                .body("error", equalTo("IllegalStateException"))
                .body("message", containsString("BLOCKED"));
    }

    private static boolean isLuhnValid(String pan) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
