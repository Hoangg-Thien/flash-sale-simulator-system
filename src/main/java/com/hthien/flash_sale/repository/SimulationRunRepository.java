package com.hthien.flash_sale.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.hthien.flash_sale.entity.SimulationRun;

@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long>{
    
    @EntityGraph(attributePaths = {"product"})
    Optional<SimulationRun> findById(Long id);
}
