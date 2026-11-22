package com.hthien.flash_sale.dto.response;

import com.hthien.flash_sale.entity.SimulationRequest;
import com.hthien.flash_sale.enums.OrderStatus;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SimulationRequestResponse {

    private Long id;
    private Integer threadIndex;
    private OrderStatus orderStatus;
    private Long latencyMs;
    private Integer httpStatus;
    private String errorMessage;
    private Long orderId;

    public static SimulationRequestResponse from(SimulationRequest request) {
        return SimulationRequestResponse.builder()
        .id(request.getId())
        .threadIndex(request.getThreadIndex())
        .orderStatus(request.getStatus())
        .latencyMs(request.getLatencyMs())
        .httpStatus(request.getHttpStatus())
        .errorMessage(request.getErrorMessage())
        .orderId(request.getOrder() != null ? request.getOrder().getId() : null)
        .build();
    }
}
