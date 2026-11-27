package com.processing.api;

import org.junit.jupiter.api.Test;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

class HealthApiTest {

    @Test
    void shouldReportHealthyGatewayAndServices() {
        given().spec(gateway()).when().get("/health")
                .then().statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("version", equalTo("1.0.0"))
                .body("services.switch", equalTo("ok"))
                .body("services.authorization", equalTo("ok"))
                .body("services.cardManagement", equalTo("ok"))
                .body("services.logger", equalTo("ok"));
    }
}
