package com.craboffice.backend.repository;

import com.craboffice.backend.entity.SimulationEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulationEventRepository extends JpaRepository<SimulationEventEntity, Long> {
    List<SimulationEventEntity> findTop30ByRoomIdOrderByCreatedAtDesc(Long roomId);
}
