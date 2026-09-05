package com.hthien.flash_sale.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.hthien.flash_sale.enums.LockMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "simulation_runs")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SimulationRun {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "initial_stock", nullable = false)
    private Integer initialStock;

    @Column(name = "concurrent_users", nullable = false)
    private Integer concurrentUsers; // so thread chay dong thoi

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_mode", nullable = false, length = 20)
    private LockMode lockMode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "final_stock")
    private Integer finalStock; // stock sau khi chay

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "failed_count")
    private Integer failedCount;

    @Column(name = "overselling_detected", nullable = false)
    @Builder.Default
    private Boolean oversellingDetected = false; // phat hien oversell

    @Column(name = "throughput_rps", precision = 10, scale = 2)
    private BigDecimal throughputRps;

    @Column(name = "avg_latency_ms", precision = 10, scale = 2)
    private BigDecimal avgLatencyMs;

    @Column(name = "p95_latency_ms", precision = 10, scale = 2)
    private BigDecimal p95LatencyMs;

    @OneToMany(mappedBy = "simulationRun", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SimulationRequest> requests = new ArrayList<>();
}
