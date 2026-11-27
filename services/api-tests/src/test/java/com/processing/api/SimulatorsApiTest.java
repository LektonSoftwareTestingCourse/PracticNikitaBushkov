package com.processing.api;

import com.processing.api.support.Api.Card;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.processing.api.support.Api.createCard;
import static com.processing.api.support.Api.gateway;
import static com.processing.api.support.Api.merchant;
import static com.processing.api.support.Api.pan;
import static com.processing.api.support.Api.setStatus;
import static com.processing.api.support.Api.stan;
import static com.processing.api.support.Api.terminalId;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorsApiTest {

    @Test
    void shouldRunTerminalSimulationAndValidateRequest() {
        createCard();
        createCard();
        given().spec(gateway()).body(runRequest(2, "normal", 100))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body("totalSubmitted", equalTo(2))
                .body("transactions", hasSize(2));

        given().spec(gateway()).body(runRequest(0, "normal", 100))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(400).body("error", equalTo("Validation failed"));
        given().spec(gateway()).body(runRequest(1, "normal", 0))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(400).body("error", equalTo("Validation failed"));
        given().spec(gateway()).body(runRequest(1, "unknown_scenario", 100))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(400);
    }

    @Test
    void shouldRunDeclinesScenarioAndReturnDeclineCodes() {
        createCard();
        Card blocked = createCard();
        setStatus(blocked.pan(), "BLOCKED");

        Response response = given().spec(gateway()).body(runRequest(50, "declines_test", 100))
                .when().post("/api/simulator/terminal/run")
                .then().statusCode(200)
                .body("totalSubmitted", equalTo(50))
                .body("transactions", hasSize(50))
                .extract().response();

        List<String> codes = response.jsonPath().getList("transactions.responseCode");
        assertTrue(codes.containsAll(List.of("14", "05", "51")), "Expected 14/05/51, got: " + codes);
        assertEquals(50, response.jsonPath().getInt("approved") + response.jsonPath().getInt("declined"));
    }

    @Test
    void shouldStartAndStopContinuousSimulation() {
        given().spec(gateway()).body(Map.of("tps", 0, "transactionType", "NORMAL"))
                .when().post("/api/simulator/terminal/start-continuous")
                .then().statusCode(400).body("error", equalTo("Validation failed"));
        given().spec(gateway()).body(Map.of("tps", 1))
                .when().post("/api/simulator/terminal/start-continuous")
                .then().statusCode(400);

        given().spec(gateway()).body(Map.of("tps", 5, "transactionType", "NORMAL"))
                .when().post("/api/simulator/terminal/start-continuous")
                .then().statusCode(200);
        given().spec(gateway()).when().post("/api/simulator/terminal/stop")
                .then().statusCode(200);
    }

    @Test
    void shouldRunMerchantSimulationAndReturnFee() {
        createCard();
        createCard();
        createCard();
        Response run = given().spec(gateway()).body(runRequest(1, "grocery", 100))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1))
                .extract().response();
        String stan = run.jsonPath().getString("transactions[0].stan");

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<Map<String, Object>> matches = recentMatching(stan);
                    assertFalse(matches.isEmpty(), "Транзакция симулятора не попала в логгер");
                });

        Map<String, Object> transaction = recentMatching(stan).stream().findFirst().orElseThrow();
        Map<String, Object> feeRequest = new LinkedHashMap<>();
        feeRequest.put("transmissionDateTime", transaction.get("transmissionDateTime"));
        feeRequest.put("pan", transaction.get("pan"));
        feeRequest.put("stan", stan);
        feeRequest.put("terminalId", transaction.get("terminalId"));
        feeRequest.put("amount", transaction.get("amount"));
        given().spec(gateway()).body(feeRequest)
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(200).body("acquirerFee", notNullValue());
    }

    @Test
    void shouldValidateMerchantRequests() {
        given().spec(gateway()).body(runRequest(0, "grocery", 100))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(400).body("error", equalTo("Invalid request"));
        given().spec(gateway()).body(runRequest(1, "unknown_scenario", 100))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(400);
        given().spec(gateway()).body(Map.of())
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(400).body("error", equalTo("Invalid request"));

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("transmissionDateTime", "2020-01-01T00:00:00Z");
        unknown.put("pan", pan("400000"));
        unknown.put("stan", stan());
        unknown.put("terminalId", terminalId());
        unknown.put("amount", 1000);
        given().spec(gateway()).body(unknown)
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(404)
                .body("error", equalTo("Resource not found"))
                .body("message", containsString("Acquirer fee not found"));
    }

    @Test
    void shouldReturnMerchantDirectoryDirectly() {
        given().spec(merchant()).when().get("/api/simulator/merchants")
                .then().statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].id", notNullValue())
                .body("[0].mcc", notNullValue())
                .body("[0].acquiringFee", notNullValue());
    }

    private static List<Map<String, Object>> recentMatching(String stan) {
        return given().spec(gateway()).queryParam("limit", 50)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200)
                .extract().jsonPath()
                .getList("findAll { it.stan == '" + stan + "' }");
    }

    private static Map<String, Object> runRequest(int count, String scenario, int tps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        body.put("scenario", scenario);
        body.put("tps", tps);
        return body;
    }
}
