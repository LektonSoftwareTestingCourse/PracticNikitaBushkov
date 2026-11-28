package com.processing.api.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.processing.api.support.Api.issuerId;
import static com.processing.api.support.Api.logger;
import static com.processing.api.support.Api.mcc;
import static com.processing.api.support.Api.merchantId;
import static com.processing.api.support.Api.rrn;
import static com.processing.api.support.Api.stan;
import static io.restassured.RestAssured.given;

public final class Transactions {

    private Transactions() {
    }

    public record Seed(UUID id, String pan, String stan, String merchantId, String mcc, String issuerId, String status) {
    }

    public static Map<String, Object> transaction(String pan, String stan, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("stan", stan);
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", Instant.now().toString());
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        return body;
    }

    public static Map<String, Object> internalTransaction(String pan, String stan, long amount, String issuerId) {
        Map<String, Object> body = transaction(pan, stan, amount);
        body.put("issuerId", issuerId);
        return body;
    }

    public static Seed seedApproved(String pan, Instant time) {
        return seed(pan, "APPROVED", null, time);
    }

    public static Seed seedDeclined(String pan, String reason, Instant time) {
        return seed(pan, "DECLINED", reason, time);
    }

    public static Map<String, Object> storedApproved(
            UUID id, String pan, String stan, String merchantId, String mcc, String issuerId, Instant time
    ) {
        return stored(id, pan, stan, "APPROVED", null, merchantId, mcc, issuerId, time);
    }

    private static Seed seed(String pan, String status, String reason, Instant time) {
        UUID id = UUID.randomUUID();
        String stan = stan();
        String merchantId = merchantId();
        String mcc = mcc();
        String issuerId = issuerId();
        Map<String, Object> body = stored(id, pan, stan, status, reason, merchantId, mcc, issuerId, time);

        given().spec(logger()).body(body)
                .when().post("/api/internal/log")
                .then().statusCode(201);

        return new Seed(id, pan, stan, merchantId, mcc, issuerId, status);
    }

    public static Map<String, Object> stored(
            UUID id, String pan, String stan, String status, String reason,
            String merchantId, String mcc, String issuerId, Instant time
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("mti", "0100");
        body.put("stan", stan);
        body.put("rrn", rrn());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", 1500);
        body.put("currencyCode", "643");
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", merchantId);
        body.put("mcc", mcc);
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", issuerId);
        body.put("acquiringFee", 15);
        body.put("status", status);
        body.put("declineReason", reason);
        body.put("authCode", "APPROVED".equals(status) ? "A1B2C3" : null);
        body.put("processingTimeMs", 10);
        body.put("transmissionDateTime", time.truncatedTo(ChronoUnit.MICROS).toString());
        body.put("createdAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        return body;
    }
}
