package com.hthien.flash_sale.entity;

import com.hthien.flash_sale.enums.SimulationOutcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "simulation_requests",
    indexes = @Index(name = "idx_sim_requests_run_id",
    columnList = "simulation_run_id"
    )
)
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SimulationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulation_run_id", nullable = false)
    private SimulationRun simulationRun;

    // Thread nào trong số N thread đã chạy (0-indexed)
    @Column(name = "thread_index", nullable = false)
    @ToString.Include
    private Integer threadIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimulationOutcome status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @ToString.Include
    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = true)
    private Order order;
}
