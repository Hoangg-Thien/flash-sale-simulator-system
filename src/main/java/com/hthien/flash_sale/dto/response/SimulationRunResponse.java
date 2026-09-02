package com.hthien.flash_sale.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hthien.flash_sale.entity.SimulationRun;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SimulationRunResponse {

    private Long simulationRunId;
    private Long productId;
    private String productName;
    private String lockMode;
    private Integer initialStock;
    private Integer concurrentUsers;

    // ket qua
    private Integer finalStock;
    private Integer successCount;
    private Integer failedCount;
    private Boolean oversellingDetected;

    // thoi gian
    private Instant startedAt;
    private Instant finishedAt;
    private Long executionTimeMs;
    
    // thong ke
    private BigDecimal throughputRps;
    private BigDecimal avgLatencyMs;
    private BigDecimal p95LatencyMs;

    private List<SimulationRequestResponse> requests;

    public static SimulationRunResponse from(SimulationRun run, List<SimulationRequestResponse> requests){
        long execMs = 0;
        if(run.getStartedAt() != null && run.getFinishedAt() != null){
            execMs = run.getFinishedAt().toEpochMilli() - run.getStartedAt().toEpochMilli();
        }
        return SimulationRunResponse.builder()
        .simulationRunId(run.getId())
        .productId(run.getProduct().getId())
        .productName(run.getProduct().getName())
        .lockMode(run.getLockMode().name())
        .initialStock(run.getInitialStock())
        .concurrentUsers(run.getConcurrentUsers())
        .finalStock(run.getFinalStock())
        .successCount(run.getSuccessCount())
        .failedCount(run.getFailedCount())
        .oversellingDetected(run.getOversellingDetected())
        .startedAt(run.getStartedAt())
        .finishedAt(run.getFinishedAt())
        .executionTimeMs(execMs)
        .throughputRps(run.getThroughputRps())
        .avgLatencyMs(run.getAvgLatencyMs())
        .p95LatencyMs(run.getP95LatencyMs())
        .requests(requests)
        .build();
    }
}
