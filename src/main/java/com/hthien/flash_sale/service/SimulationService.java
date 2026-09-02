package com.hthien.flash_sale.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.dto.response.SimulationRequestResponse;
import com.hthien.flash_sale.dto.response.SimulationRunResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Order;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.entity.SimulationRequest;
import com.hthien.flash_sale.entity.SimulationRun;
import com.hthien.flash_sale.enums.LockMode;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.exception.SimulationNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.repository.SimulationRequestRepository;
import com.hthien.flash_sale.repository.SimulationRunRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimulationService {
    
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final SimulationRequestRepository simulationRequestRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final OrderService orderService;
    private final EntityManager entityManager;

    public SimulationRunResponse runSimulation(CreateSimulationRequest request){
        // validate product và lockmode 
        Product product = productRepository.findById(request.getProductId())
        .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        LockMode lockMode = parseLockMode(request.getLockMode());

        // reset stock về initialStock
        resetStock(request.getProductId(), request.getInitialStock());
        log.info("Simulation starting: productId={}, initialStock={}, concurrentUsers={}, lockMode={}",
        product.getId(), request.getInitialStock(), request.getConcurrentUsers(), lockMode);

        // tạo SimulationRun record (lưu vào db trước khi bắn thread)
        SimulationRun run = createSimulationRun(product, request, lockMode);

        // bắn N thread đồng thời
        List<SimulationRequest> simRequests = runConcurrentPurchases(run, product, request.getConcurrentUsers(), lockMode);

        // tính kết quả tổng hợp và lưu
        SimulationRun completedRun = completeSimulationRun(run, simRequests, request.getProductId());

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

        for(int i = 0; i < concurrentUsers; i++){
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    SimulationRequest simRequest = executePurchase(run, product, threadIndex, lockMode);
                    results.add(simRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread {} interrupted", threadIndex);
                } catch (Throwable t) {
                    log.error("Thread {} unexpected failure: {}", threadIndex, t.getMessage(), t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        log.info("Firing {} concurrent threads for productId={}", concurrentUsers, product.getId());
        startLatch.countDown();

        try {
            boolean completed = doneLatch.await(60, TimeUnit.SECONDS); // timeout 60s
            if(!completed){
                log.error("Simulation timeout: not all threads completed within 60s");
            }
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
            log.error("Main thread interrupted while waiting for simulation");
        } finally {
            executor.shutdown();
        }
        return results;
    }

    @Transactional
    protected SimulationRun completeSimulationRun(SimulationRun run, List<SimulationRequest> simRequests, Long productId){
        Instant finishedAt = Instant.now();

        long successCount = simRequests.stream()
        .filter(r -> r.getStatus() == OrderStatus.SUCCESS)
        .count();

        long failedCount = simRequests.size() - successCount;
        
        entityManager.clear(); // Xóa L1 cache để đọc giá trị mới nhất từ DB
        int finalStock = inventoryRepository.findByProductId(productId)
        .map(inv -> inv.getStock())
        .orElse(-1);

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
        OrderStatus status = OrderStatus.FAILED;
        String errorMessage = null;
        int httpStatus = 409;

        try {
            CreateOrderRequest orderRequest = new CreateOrderRequest();
            orderRequest.setProductId(product.getId());
            orderRequest.setQuantity(1);
            orderRequest.setLockMode(lockMode.name());

            var orderResponse = orderService.createOrder(orderRequest, null);

            status = OrderStatus.SUCCESS;
            httpStatus = 201;

            successOrder = orderRepository.getReferenceById(orderResponse.getId());
        } catch (InsufficientStockException e) {
            status = OrderStatus.FAILED;
            errorMessage = "Insufficient stock";
            httpStatus = 409;
        } catch(Exception e){
            status = OrderStatus.FAILED;
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

    @Transactional
    protected SimulationRun createSimulationRun(Product product, CreateSimulationRequest request, LockMode lockMode){
        SimulationRun run = SimulationRun.builder()
        .product(product)
        .initialStock(request.getInitialStock())
        .concurrentUsers(request.getConcurrentUsers())
        .lockMode(lockMode)
        .startedAt(Instant.now())
        .build();
        return simulationRunRepository.save(run);
    }

    // tạo SimulationRun record ban đầu
    @Transactional
    protected void resetStock(Long productId, Integer newStock){
        Inventory inventory = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));

        inventory.setStock(newStock);
        inventoryRepository.save(inventory);
        log.info("Stock reset to {} for productId={}", newStock, productId);
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

    private LockMode parseLockMode(String lockModeStr){
        try {
            return LockMode.valueOf(lockModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
            "Invalid lockMode: " + lockModeStr +
            ". Valid values: NONE, OPTIMISTIC, PESSIMISTIC, REDIS");
        }
    }
}