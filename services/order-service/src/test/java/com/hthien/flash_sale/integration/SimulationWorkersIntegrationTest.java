package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.hthien.flash_sale.client.ReservationClient;
import com.hthien.flash_sale.client.StockClient;
import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.service.SimulationService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("lab")
class SimulationWorkersIntegrationTest extends BaseIntegrationTest {
    @Autowired SimulationService simulations;
    @Autowired ProductRepository products;
    @MockitoBean StockClient stock;
    @MockitoBean ReservationClient remote;

    @Test
    void workersPropagateMdcAndPersistEachImmediateOutcome() {
        long pid = products.save(Product.builder().name("workers").price(BigDecimal.TEN).build()).getId();
        var calls = new AtomicInteger();
        var contexts = new ConcurrentLinkedQueue<String>();
        when(stock.reset(pid, 1)).thenReturn(new StockClient.Stock(pid, 1, 0L));
        when(stock.get(pid)).thenReturn(new StockClient.Stock(pid, 0, 1L));
        when(remote.reserve(any())).thenAnswer(call -> {
            contexts.add(MDC.get("requestId") + "/" + MDC.get("instanceId"));
            return switch (calls.incrementAndGet()) {
                case 1 -> ReservationClient.State.RESERVED;
                case 2 -> ReservationClient.State.REJECTED;
                default -> ReservationClient.State.UNKNOWN;
            };
        });
        var request = new CreateSimulationRequest();
        request.setProductId(pid);
        request.setInitialStock(1);
        request.setConcurrentUsers(3);
        try (var requestId = MDC.putCloseable("requestId", "simulation");
             var instance = MDC.putCloseable("instanceId", "workers")) {
            var result = simulations.runSimulation(request);
            var saved = simulations.getSimulation(result.getSimulationRunId());
            assertThat(saved.getSuccessCount()).isEqualTo(1);
            assertThat(saved.getFailedCount()).isEqualTo(1);
            assertThat(saved.getPendingCount()).isEqualTo(1);
            assertThat(saved.getRequests()).hasSize(3);
            assertThat(saved.getFinalStock()).isZero();
            assertThat(saved.getOversellingDetected()).isFalse();
            assertThat(contexts).containsExactly("simulation/workers", "simulation/workers", "simulation/workers");
            assertThat(MDC.get("requestId")).isEqualTo("simulation");
        }
    }
}
