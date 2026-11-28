package com.hthien.flash_sale.client;

import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StockClient {
    private final InventoryHttp http;

    public StockClient(InventoryHttp http) {
        this.http = http;
    }

    public record Stock(Long productId, Integer stock, Long version) {}

    public Stock initialize(long id, int stock) {
        return call(id, HttpMethod.POST, "/initialize", Map.of("stock", stock));
    }

    public Stock get(long id) {
        return call(id, HttpMethod.GET, "", null);
    }

    public Stock reset(long id, int stock) {
        return call(id, HttpMethod.PUT, "", Map.of("stock", stock));
    }

    private Stock call(long id, HttpMethod method, String suffix, Object body) {
        try {
            Stock s = http.exchange(method, "/internal/v1/inventory/" + id + suffix, body, Stock.class);
            if (s == null || s.productId() == null || s.productId() != id || s.stock() == null
                    || s.stock() < 0 || s.version() == null || s.version() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid inventory response");
            }
            return s;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Inventory command conflicts with current stock", e);
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable", e);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable", e);
        }
    }
}
