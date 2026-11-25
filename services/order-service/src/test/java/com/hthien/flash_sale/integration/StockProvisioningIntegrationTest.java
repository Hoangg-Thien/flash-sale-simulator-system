package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import com.hthien.flash_sale.client.StockClient;
import com.hthien.flash_sale.dto.request.CreateProductRequest;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@TestPropertySource(properties = "inventory.provision-delay-ms=3600000")
class StockProvisioningIntegrationTest extends BaseIntegrationTest {
    @Autowired ProductService catalog;
    @Autowired ProductRepository products;
    @MockitoBean StockClient inventory;

    @Test
    void durableSeedSurvivesLostAcknowledgementAndWorkerRestart() {
        var calls = new AtomicInteger();
        when(inventory.initialize(anyLong(), eq(12))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == 1) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "lost initialization response");
            }
            return new StockClient.Stock(invocation.getArgument(0), 12, 0L);
        });

        var request = new CreateProductRequest();
        request.setName("Provisioning recovery");
        request.setPrice(BigDecimal.TEN);
        request.setInitialStock(12);
        var pending = catalog.createProduct(request);
        assertThat(pending.getStock()).isNull();
        assertThat(products.findById(pending.getId()).orElseThrow().getPendingInitialStock()).isEqualTo(12);

        var restarted = new ProductService(products, inventory);
        restarted.resumeInitialization();
        assertThat(products.findById(pending.getId()).orElseThrow().getPendingInitialStock()).isNull();
        restarted.resumeInitialization();
        assertThat(calls.get()).isEqualTo(2);
    }
}
