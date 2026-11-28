package com.processing.api.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;

public final class Api {

    public static final String BIN = "400000";
    private static final AtomicLong SEQ = new AtomicLong(ThreadLocalRandom.current().nextInt(100_000, 900_000));

    private static final RequestSpecification GATEWAY = jsonSpec(value("GATEWAY_URL", "http://localhost:8080"));
    private static final RequestSpecification CARD_MANAGEMENT = jsonSpec(value("CARD_MANAGEMENT_URL", "http://localhost:8081"));
    private static final RequestSpecification AUTHORIZATION = jsonSpec(value("AUTHORIZATION_URL", "http://localhost:8083"));
    private static final RequestSpecification LOGGER = jsonSpec(value("TRANSACTION_LOGGER_URL", "http://localhost:8088"));
    private static final RequestSpecification SWITCH = jsonSpec(value("SWITCH_URL", "http://localhost:8082"));
    private static final RequestSpecification MERCHANT = jsonSpec(value("MERCHANT_ACQUIRER_URL", "http://localhost:8084"));

    private Api() {
    }

    public static RequestSpecification gateway() {
        return GATEWAY;
    }

    public static RequestSpecification cardManagement() {
        return CARD_MANAGEMENT;
    }

    public static RequestSpecification authorization() {
        return AUTHORIZATION;
    }

    public static RequestSpecification logger() {
        return LOGGER;
    }

    public static RequestSpecification switchService() {
        return SWITCH;
    }

    public static RequestSpecification merchant() {
        return MERCHANT;
    }

    public record Card(String id, String pan, String issuerId, String createdAt) {
    }

    public static Card createCard() {
        return createCard(BIN, 10_000_000, 100_000_000, 10_000_000);
    }

    public static Card createCard(long dailyLimit, long monthlyLimit, long balance) {
        return createCard(BIN, dailyLimit, monthlyLimit, balance);
    }

    public static Card createCard(String bin, long dailyLimit, long monthlyLimit, long balance) {
        Response response = given()
                .spec(gateway())
                .body(cardBody(bin, dailyLimit, monthlyLimit, balance))
                .when()
                .post("/api/cards")
                .then()
                .statusCode(201)
                .extract()
                .response();
        return new Card(response.path("id"), response.path("pan"), response.path("issuerId"),
                response.path("createdAt"));
    }

    public static Map<String, Object> cardBody(String bin, long dailyLimit, long monthlyLimit, long balance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bin", bin);
        body.put("cardholderName", cardholder());
        body.put("currencyCode", "643");
        body.put("dailyLimit", dailyLimit);
        body.put("monthlyLimit", monthlyLimit);
        body.put("initialBalance", balance);
        return body;
    }

    public static void setStatus(String pan, String status) {
        given().spec(gateway()).body(Map.of("status", status))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);
    }

    public static Map<String, Object> reserve(long amount, String rrn) {
        return Map.of("amount", amount, "rrn", rrn);
    }

    public static Map<String, Object> rollback(String pan, long amount, String rrn) {
        return Map.of("pan", pan, "amount", amount, "rrn", rrn);
    }

    public static Map<String, Object> bulk(List<String> pans, String status) {
        return Map.of("pans", pans, "status", status);
    }

    public static String pan(String bin) {
        return bin + String.format("%010d", SEQ.getAndIncrement());
    }

    public static String stan() {
        return String.format("%06d", SEQ.getAndIncrement() % 1_000_000);
    }

    public static String rrn() {
        return String.format("%012d", SEQ.getAndIncrement());
    }

    public static String merchantId() {
        return "M" + digits(14);
    }

    public static String issuerId() {
        return "I" + digits(9);
    }

    public static String mcc() {
        return digits(4);
    }

    public static String terminalId() {
        return "T" + digits(7);
    }

    public static String cardholder() {
        return "API TEST " + letters(6);
    }

    public static String reason() {
        return "UNIQUE_" + digits(10) + "_REASON";
    }

    public static String digits(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(ThreadLocalRandom.current().nextInt(10));
        }
        return result.toString();
    }

    public static String letters(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private static RequestSpecification jsonSpec(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    private static String value(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
