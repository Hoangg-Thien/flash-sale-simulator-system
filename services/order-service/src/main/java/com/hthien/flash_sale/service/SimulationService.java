package com.hthien.flash_sale.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.hthien.flash_sale.client.StockClient;
import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.dto.request.ResetStockRequest;
import com.hthien.flash_sale.dto.response.SimulationRequestResponse;
import com.hthien.flash_sale.dto.response.SimulationRunResponse;
import com.hthien.flash_sale.entity.Order;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.entity.SimulationRequest;
import com.hthien.flash_sale.entity.SimulationRun;
import com.hthien.flash_sale.enums.LockMode;
import com.hthien.flash_sale.enums.SimulationOutcome;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.exception.SimulationNotFoundException;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.repository.SimulationRequestRepository;
import com.hthien.flash_sale.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("lab")
@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationService {
    
    private final ProductRepository productRepository;
    private final StockClient inventory;
    private final ProductService productService;
    private final OrderRepository orderRepository;
    private final SimulationRequestRepository simulationRequestRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final OrderService orderService;
    @Value("${inventory.lock-mode:ATOMIC}")
    private LockMode configuredMode;

    public SimulationRunResponse runSimulation(CreateSimulationRequest request){
        // validate product và lockmode 
        Product product = productRepository.findById(request.getProductId())
        .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        LockMode lockMode = request.getLockMode() == null ? configuredMode
            : LockMode.valueOf(request.getLockMode().toUpperCase(Locale.ROOT));
        if (lockMode != configuredMode) {
            throw new IllegalArgumentException("Set the simulation mode to the deployed Inventory lock mode: " + configuredMode);
        }

        // reset stock về initialStock
        ResetStockRequest reset = new ResetStockRequest();
        reset.setNewStock(request.getInitialStock());
        productService.resetStock(request.getProductId(), reset);
        log.info("Simulation starting: productId={}, initialStock={}, concurrentUsers={}, lockMode={}",
        product.getId(), request.getInitialStock(), request.getConcurrentUsers(), lockMode);

        // tạo SimulationRun record (lưu vào db trước khi bắn thread)
        SimulationRun run = createSimulationRun(product, request, lockMode);

        // bắn N thread đồng thời
        List<SimulationRequest> simRequests = runConcurrentPurchases(run, product, request.getConcurrentUsers(), lockMode);

        // tính kết quả tổng hợp và lưu
        SimulationRun completedRun = completeSimulationRun(run, simRequests, request.getProductId());
        completedRun.setProduct(product);

        // map sang response dto
        List<SimulationRequestResponse> requestResponses = simRequests.stream()
        .map(SimulationRequestResponse::from)
        .toList();

        return SimulationRunResponse.from(completedRun, requestResponses);
    }

    /**
    * Core logic: bắn N thread đồng thời, dùng CountDownLatch để sync.
    *
    * THIẾT KẾ QUAN TRỌNG — CountDownLatch:
    *
    *   startLatch (count=1):  tất cả N thread TẬP HỢP tại đây, chờ 1 tín hiệu "bắt đầu"
    *                          → đảm bảo N thread THỰC SỰ chạy cùng lúc (không phải lần lượt)
    *
    *   doneLatch (count=N):   main thread chờ tất cả N thread hoàn thành
    *                          → đảm bảo collect đủ kết quả trước khi tính toán
    *
    * Nếu không có startLatch: thread 1 có thể hoàn thành trước khi thread N bắt đầu → không phải concurrent test
    * Nếu không có doneLatch: main thread collect kết quả khi thread vẫn đang chạy → thiếu data
    */
    private List<SimulationRequest> runConcurrentPurchases(SimulationRun run, Product product, int concurrentUsers, LockMode lockMode){
        List<SimulationRequest> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLatch = new CountDownLatch(1); // tín hiệu bắt đầu
        CountDownLatch doneLatch = new CountDownLatch(concurrentUsers); // xong

        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        Map<String, String> requestContext = MDC.getCopyOfContextMap();

        for(int i = 0; i < concurrentUsers; i++){
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    if (requestContext != null) {
                        MDC.setContextMap(requestContext);
                    }
                    startLatch.await();

                    SimulationRequest simRequest = executePurchase(run, product, threadIndex, lockMode);
                    results.add(simRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread {} interrupted", threadIndex);
                } catch (Throwable t) {
                    log.error("Thread {} unexpected failure: {}", threadIndex, t.getMessage(), t);
                } finally {
                    MDC.clear();
                    doneLatch.countDown();
                }
            });
        }

        log.info("Firing {} concurrent threads for productId={}", concurrentUsers, product.getId());
        startLatch.countDown();

        try {
            doneLatch.await();
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulation interrupted", e);
        } finally {
            executor.shutdown();
        }
        if (results.size() != concurrentUsers) {
            throw new IllegalStateException("A simulation worker failed before recording its result");
        }
        return results;
    }

    private SimulationRun completeSimulationRun(SimulationRun run, List<SimulationRequest> simRequests, Long productId){
        Instant finishedAt = Instant.now();

        long successCount = simRequests.stream()
        .filter(r -> r.getStatus() == SimulationOutcome.CONFIRMED)
        .count();

        long failedCount = simRequests.stream()
            .filter(r -> r.getStatus() == SimulationOutcome.REJECTED || r.getStatus() == SimulationOutcome.ERROR)
            .count();
        
        int finalStock = inventory.get(productId).stock();

        // phát hiện oversell
        boolean oversellingDetected = finalStock < 0 || successCount > run.getInitialStock();

        List<Long> latencies = simRequests.stream()
        .map(SimulationRequest::getLatencyMs)
        .filter(l -> l != null)
        .sorted()
        .toList();

        BigDecimal avgLatency = latencies.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(latencies.stream().mapToLong(Long::longValue).average().orElse(0))
        .setScale(2, RoundingMode.HALF_UP);

        // p95: phần tử ở vị trí thứ 95% trong list đã sort
        BigDecimal p95Latency = BigDecimal.ZERO;
        if(!latencies.isEmpty()){
            int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
            p95Latency = BigDecimal.valueOf(latencies.get(Math.max(0, p95Index)));
        }

        // Throughput: số request / số giây
        long executionMs = finishedAt.toEpochMilli() - run.getStartedAt().toEpochMilli();
        BigDecimal throughput = executionMs > 0
        ? BigDecimal.valueOf(simRequests.size())
        .divide(BigDecimal.valueOf(executionMs).divide(BigDecimal.valueOf(1000),
        4, RoundingMode.HALF_UP), 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;

        run.setFinishedAt(finishedAt);
        run.setFinalStock(finalStock);
        run.setSuccessCount((int) successCount);
        run.setFailedCount((int) failedCount);
        run.setOversellingDetected(oversellingDetected);
        run.setAvgLatencyMs(avgLatency);
        run.setP95LatencyMs(p95Latency);
        run.setThroughputRps(throughput);

        SimulationRun saved = simulationRunRepository.save(run);

        log.info("Simulation completed: id={}, successCount={}, failedCount={}, " +
        "finalStock={}, oversellingDetected={}, avgLatencyMs={}",
        saved.getId(), successCount, failedCount,
        finalStock, oversellingDetected, avgLatency);

        return saved;
    }

    // Thực thi 1 purchase request trong 1 thread, ghi nhận kết quả.
    private SimulationRequest executePurchase(SimulationRun run, Product product, int threadIndex, LockMode lockMode){
        long startTime = System.currentTimeMillis();
        Order successOrder = null;
        SimulationOutcome status = SimulationOutcome.ERROR;
        String errorMessage = null;
        int httpStatus = 409;

        try {
            CreateOrderRequest orderRequest = new CreateOrderRequest();
            orderRequest.setProductId(product.getId());
            orderRequest.setQuantity(1);
            orderRequest.setLockMode(lockMode.name());

            var orderResponse = orderService.createOrder(orderRequest, UUID.randomUUID().toString());

            status = SimulationOutcome.valueOf(orderResponse.getOrderStatus().name());
            httpStatus = switch (orderResponse.getOrderStatus()) {
                case PENDING -> 202;
                case CONFIRMED -> 201;
                case REJECTED -> 409;
            };

            successOrder = orderRepository.getReferenceById(orderResponse.getId());
        } catch(Exception e){
            status = SimulationOutcome.ERROR;
            errorMessage = e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(),255))
            : "Unknown Error";
            httpStatus = 500;
            log.error("Thread {} unexpected error: {}", threadIndex, e.getMessage());
        }

        long latencyMs = System.currentTimeMillis() - startTime;

        SimulationRequest simRequest = SimulationRequest.builder()
        .simulationRun(run)
        .threadIndex(threadIndex)
        .status(status)
        .latencyMs(latencyMs)
        .httpStatus(httpStatus)
        .errorMessage(errorMessage)
        .order(successOrder)
        .build();

        return simulationRequestRepository.save(simRequest);
    }

    private SimulationRun createSimulationRun(Product product, CreateSimulationRequest request, LockMode lockMode){
        SimulationRun run = SimulationRun.builder()
        .product(product)
        .initialStock(request.getInitialStock())
        .concurrentUsers(request.getConcurrentUsers())
        .lockMode(lockMode)
        .startedAt(Instant.now())
        .build();
        return simulationRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public SimulationRunResponse getSimulation(Long simulationRunId){
        SimulationRun run = simulationRunRepository.findById(simulationRunId)
        .orElseThrow(() -> new SimulationNotFoundException(simulationRunId));

        List<SimulationRequest> requests = simulationRequestRepository.findBySimulationRunId(simulationRunId);

        List<SimulationRequestResponse> requestResponses = requests.stream()
        .map(SimulationRequestResponse::from)
        .toList();

        return SimulationRunResponse.from(run, requestResponses);
    }

    @Transactional(readOnly = true)
    public List<SimulationRequestResponse> getSimulationRequests(Long simulationRunId){
        if(!simulationRunRepository.existsById(simulationRunId)){
            throw new SimulationNotFoundException(simulationRunId);
        }
        return simulationRequestRepository.findBySimulationRunId(simulationRunId).stream()
        .map(SimulationRequestResponse::from)
        .toList();
    }

}
