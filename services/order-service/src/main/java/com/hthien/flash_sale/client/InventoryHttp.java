package com.hthien.flash_sale.client;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

@Component
public class InventoryHttp {
    private final List<RestClient> clients;
    private final AtomicInteger next = new AtomicInteger();

    public InventoryHttp(RestClient.Builder builder,
            @Value("${inventory.base-urls:http://localhost:8081}") String urls,
            @Value("${inventory.connect-timeout-ms:500}") int connectMs,
            @Value("${inventory.read-timeout-ms:2000}") int readMs) {
        if (connectMs <= 0 || readMs <= 0) throw new IllegalArgumentException("Positive HTTP timeouts required");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        clients = Arrays.stream(urls.split(",")).map(String::trim).filter(url -> !url.isEmpty())
            .map(url -> builder.clone().baseUrl(url).requestFactory(factory).build()).toList();
        if (clients.isEmpty()) throw new IllegalArgumentException("Inventory URL required");
    }

    public <T> T exchange(HttpMethod method, String path, Object body, Class<T> type) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inventory HTTP call inside an Order transaction");
        }
        var request = clients.get(Math.floorMod(next.getAndIncrement(), clients.size())).method(method).uri(path);
        String requestId = MDC.get("requestId");
        if (requestId != null) request.header("X-Request-Id", requestId);
        if (body != null) request.body(body);
        var response = request.retrieve().toEntity(type);
        if (response.getStatusCode().value() != 200) {
            throw new org.springframework.web.client.RestClientException("Unexpected Inventory HTTP status " + response.getStatusCode());
        }
        return response.getBody();
    }
}
