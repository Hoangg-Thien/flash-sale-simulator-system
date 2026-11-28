package com.hthien.flash_sale.client;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ReservationClient {
    public enum State { RESERVED, RELEASED, REJECTED, MISSING, UNKNOWN }
    public record Command(long orderId, long productId, int quantity) {}
    private static final String PATH = "/internal/v1/reservations";
    private static final Logger log = LoggerFactory.getLogger(ReservationClient.class);
    private final InventoryHttp http;
    private final int retries;

    ReservationClient(InventoryHttp http, @Value("${inventory.retries:2}") int retries) {
        if (retries < 0 || retries > 2) throw new IllegalArgumentException("inventory.retries must be 0..2");
        this.http = http;
        this.retries = retries;
    }

    public State reserve(Command c) { return call(c, HttpMethod.POST, PATH, c); }
    public State query(Command c) { return call(c, HttpMethod.GET, PATH + "/" + c.orderId(), null); }
    public State release(Command c) {
        return call(c, HttpMethod.POST, PATH + "/" + c.orderId() + "/release",
            Map.of("productId", c.productId(), "quantity", c.quantity()));
    }

    private static boolean matches(JsonNode value, long expected) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == expected;
    }

    private State call(Command c, HttpMethod method, String path, Object body) {
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                JsonNode reply = http.exchange(method, path, body, JsonNode.class);
                if (reply == null || !matches(reply.path("orderId"), c.orderId())
                        || !matches(reply.path("productId"), c.productId())
                        || !matches(reply.path("quantity"), c.quantity())) {
                    log.warn("Invalid reservation response for order {}", c.orderId());
                    return State.UNKNOWN;
                }
                return switch (reply.path("status").asText("")) {
                    case "RESERVED" -> State.RESERVED;
                    case "RELEASED" -> State.RELEASED;
                    case "REJECTED" -> State.REJECTED;
                    default -> State.UNKNOWN;
                };
            } catch (RestClientResponseException ex) {
                if (method == HttpMethod.GET && ex.getStatusCode().value() == 404) return State.MISSING;
                if (!ex.getStatusCode().is5xxServerError()) return State.UNKNOWN;
            } catch (RestClientException ex) {
                log.debug("Inventory call failed for order {}", c.orderId(), ex);
            }
            if (attempt < retries) {
                try { Thread.sleep(50L * (attempt + 1)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
        }
        return State.UNKNOWN;
    }
}
