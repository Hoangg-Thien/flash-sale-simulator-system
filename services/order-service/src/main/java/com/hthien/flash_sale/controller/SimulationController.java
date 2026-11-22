package com.hthien.flash_sale.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.dto.response.SimulationRequestResponse;
import com.hthien.flash_sale.dto.response.SimulationRunResponse;
import com.hthien.flash_sale.service.SimulationService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/simulations")
@RequiredArgsConstructor
public class SimulationController {
   
    private final SimulationService simulationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Run a concurrent purchase simulation (synchronous, returns when all threads complete)")
    public SimulationRunResponse runSimulation(
        @Valid @RequestBody CreateSimulationRequest request
    ){
        return simulationService.runSimulation(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get simulation run details and aggregate statistics")
    public SimulationRunResponse getSimulation(
        @PathVariable Long id
    ){
        return simulationService.getSimulation(id);
    }

    @GetMapping("/{id}/requests")
    @Operation(summary = "Get individual request results for a simulation run")
    public List<SimulationRequestResponse> getSimulationRequests(
        @PathVariable Long id
    ){
        return simulationService.getSimulationRequests(id);
    }
}
