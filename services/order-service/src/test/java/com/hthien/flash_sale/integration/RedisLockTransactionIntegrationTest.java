package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hthien.flash_sale.concurrency.RedisLockStrategy;
import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.service.OrderService;

class RedisLockTransactionIntegrationTest extends BaseIntegrationTest {

    @Autowired private OrderService orderService;
    @SpyBean private RedissonClient redissonClient;
    @SpyBean private RedisLockStrategy redisLockStrategy;

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "WORK_FAILURE", "COMMIT_FAILURE"})
    void redisLock_mustCoverTransactionCompletion(String outcome) throws Exception {
        cleanDatabase();
        Product product = productRepository.save(Product.builder()
            .name("Lock boundary test").price(BigDecimal.TEN).build());
        inventoryRepository.save(Inventory.builder().product(product).stock(10).build());

        List<String> events = new ArrayList<>();
        String key = "lock:inventory:" + product.getId();
        RLock realLock = redissonClient.getLock(key);
        RLock recordingLock = mock(RLock.class, delegatesTo(realLock));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            boolean acquired = realLock.tryLock(invocation.getArgument(0), invocation.getArgument(1));
            events.add("LOCKED");
            return acquired;
        }).when(recordingLock).tryLock(anyLong(), any(TimeUnit.class));
        doAnswer(invocation -> {
            realLock.unlock();
            events.add("UNLOCKED");
            return null;
        }).when(recordingLock).unlock();
        doReturn(recordingLock).when(redissonClient).getLock(key);

        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (outcome.equals("COMMIT_FAILURE")) {
                        throw new IllegalStateException("Injected commit failure");
                    }
                }

                @Override
                public void afterCommit() {
                    events.add("COMMITTED");
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        events.add("ROLLED_BACK");
                    }
                }
            });
            Object result = invocation.callRealMethod();
            if (outcome.equals("WORK_FAILURE")) {
                throw new IllegalStateException("Injected failure after deduction");
            }
            return result;
        }).when(redisLockStrategy).deductStock(product.getId(), 1);

        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(product.getId());
        request.setQuantity(1);
        request.setLockMode("REDIS");
        if (outcome.equals("COMMIT")) {
            orderService.createOrder(request, null);
        } else {
            assertThatThrownBy(() -> orderService.createOrder(request, null))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        assertThat(events).containsExactly("LOCKED",
            outcome.equals("COMMIT") ? "COMMITTED" : "ROLLED_BACK", "UNLOCKED");
        assertThat(realLock.isLocked()).isFalse();
        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getStock())
            .isEqualTo(outcome.equals("COMMIT") ? 9 : 10);
        assertThat(orderRepository.count()).isEqualTo(outcome.equals("COMMIT") ? 1 : 0);
    }
}
