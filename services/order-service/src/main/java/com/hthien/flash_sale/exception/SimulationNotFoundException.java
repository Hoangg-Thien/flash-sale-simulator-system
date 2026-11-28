package com.hthien.flash_sale.exception;

public class SimulationNotFoundException extends RuntimeException{
    public SimulationNotFoundException(Long simulationRunId){
        super("Simulation run not found: id=" + simulationRunId);
    }
}
