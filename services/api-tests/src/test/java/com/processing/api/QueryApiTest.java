package com.processing.api;

import com.processing.api.support.Transactions.Seed;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import static com.processing.api.support.Api.BIN;
import static com.processing.api.support.Api.gateway;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.reason;
import static com.processing.api.support.Transactions.seedApproved;
import static com.processing.api.support.Transactions.seedDeclined;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryApiTest {

    @Test
    void shouldFilterAndPaginateSearchResults() {
        String pan = pan(BIN);
        Seed first = seedApproved(pan, Instant.now());
        seedApproved(pan, Instant.now());
        seedDeclined(pan, reason(), Instant.now());

        given().spec(gateway())
                .queryParam("pan", pan)
                .when().get("/api/transactions/search")
                .then().statusCode(200)
                .body("total", equalTo(3))
                .body("transactions.status", org.hamcrest.Matchers.hasItem("DECLINED"));

        given().spec(gateway())
                .queryParam("pan", pan).queryParam("merchantId", first.merchantId())
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].id", equalTo(first.id().toString()));
        given().spec(gateway())
                .queryParam("pan", pan).queryParam("mcc", first.mcc())
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1));
        given().spec(gateway())
                .queryParam("pan", pan).queryParam("issuerId", first.issuerId())
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1));
        given().spec(gateway())
                .queryParam("pan", pan).queryParam("status", "DECLINED")
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions.status", everyItem(equalTo("DECLINED")));

        given().spec(gateway())
                .queryParam("pan", pan).queryParam("limit", 2).queryParam("offset", 0)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(3)).body("transactions", hasSize(2));
        given().spec(gateway())
                .queryParam("pan", pan).queryParam("limit", 2).queryParam("offset", 2)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(3)).body("transactions", hasSize(1));
        given().spec(gateway())
                .queryParam("pan", pan).queryParam("limit", 2).queryParam("offset", 10)
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(3)).body("transactions", empty());

        given().spec(gateway()).queryParam("pan", pan(BIN))
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", empty());
    }

    @Test
    void shouldFilterByDateAndDeclineReason() {
        String pan = pan(BIN);
        seedApproved(pan, Instant.parse("2021-03-15T12:00:00Z"));
        seedApproved(pan, Instant.now());
        String declineReason = reason();
        Seed declined = seedDeclined(pan, declineReason, Instant.now());

        given().spec(gateway())
                .queryParam("pan", pan)
                .queryParam("dateFrom", "2021-03-15").queryParam("dateTo", "2021-03-15")
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].transmissionDateTime", containsString("2021-03-15"));
        given().spec(gateway())
                .queryParam("pan", pan)
                .queryParam("declineReason", declineReason.replace("_", " ").toLowerCase())
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].id", equalTo(declined.id().toString()));
        given().spec(gateway())
                .queryParam("pan", pan)
                .queryParam("dateFrom", LocalDate.now(ZoneOffset.UTC).toString())
                .when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(2));
    }

    @ParameterizedTest
    @MethodSource("invalidSearchParameters")
    void shouldRejectInvalidSearchParameters(String parameter, String value, String message) {
        given().spec(gateway()).queryParam(parameter, value)
                .when().get("/api/transactions/search")
                .then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", containsString(message))
                .body("serviceName", equalTo("transaction-logger"));
    }

    static Stream<Arguments> invalidSearchParameters() {
        return Stream.of(
                Arguments.of("limit", "0", "limit"),
                Arguments.of("limit", "501", "limit"),
                Arguments.of("offset", "-1", "offset"),
                Arguments.of("dateFrom", "not-a-date", "Failed to convert")
        );
    }

    @Test
    void shouldRejectInvertedDateRange() {
        given().spec(gateway())
                .queryParam("dateFrom", "2026-06-02").queryParam("dateTo", "2026-06-01")
                .when().get("/api/transactions/search")
                .then().statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo(""));
    }

    @Test
    void shouldExportFilteredTransactionsToCsv() {
        String pan = pan(BIN);
        seedApproved(pan, Instant.now());

        Response response = given().spec(gateway()).queryParam("pan", pan)
                .when().get("/api/transactions/export")
                .then().statusCode(200)
                .header("Content-Type", containsString("text/csv"))
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .extract().response();

        String csv = response.asString();
        assertTrue(csv.startsWith("id,mti,stan,rrn,pan,processingCode,amount,currencyCode,terminalId,"));
        assertTrue(csv.contains(pan));
        assertEquals(2, csv.split("\r\n").length);
    }

    @Test
    void shouldReturnDashboardStatsAndRecentTransactions() {
        seedApproved(pan(BIN), Instant.now());
        seedDeclined(pan(BIN), reason(), Instant.now());

        Response stats = given().spec(gateway()).when().get("/api/dashboard/stats")
                .then().statusCode(200)
                .body("approvedCount", greaterThanOrEqualTo(1))
                .body("declinedCount", greaterThanOrEqualTo(1))
                .body("totalTransactions", greaterThanOrEqualTo(2))
                .extract().response();
        long total = stats.jsonPath().getLong("totalTransactions");
        assertEquals(total, stats.jsonPath().getLong("approvedCount") + stats.jsonPath().getLong("declinedCount"));

        given().spec(gateway()).queryParam("limit", 1)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", notNullValue())
                .body("[0].pan", notNullValue())
                .body("[0].createdAt", notNullValue());

        given().spec(gateway()).queryParam("limit", 0)
                .when().get("/api/dashboard/recent")
                .then().statusCode(400).body("error", equalTo("Validation error"));
    }

    @Test
    void shouldReturnChartsAndValidateGranularity() {
        seedApproved(pan(BIN), Instant.now());
        seedDeclined(pan(BIN), reason(), Instant.now());
        Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);

        given().spec(gateway())
                .queryParam("granularity", "day")
                .queryParam("from", from.toString()).queryParam("to", to.toString())
                .when().get("/api/dashboard/charts")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].total", notNullValue())
                .body("[0].approved", notNullValue())
                .body("[0].declined", notNullValue());

        given().spec(gateway()).queryParam("granularity", "week")
                .when().get("/api/dashboard/charts")
                .then().statusCode(400).body("error", equalTo("Validation error"));
        given().spec(gateway())
                .queryParam("from", "2026-12-31T00:00:00Z").queryParam("to", "2026-01-01T00:00:00Z")
                .when().get("/api/dashboard/charts")
                .then().statusCode(400).body("error", equalTo("Validation error"));
    }

    @Test
    void shouldRejectInvalidExportLimit() {
        given().spec(gateway()).queryParam("limit", 0)
                .when().get("/api/transactions/export")
                .then().statusCode(400).body("error", equalTo("Validation error"));
    }
}
