package com.hthien.flash_sale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.hthien.flash_sale.entity.SimulationRequest;
import com.hthien.flash_sale.entity.SimulationRun;

@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun ,Long>{
    
}
