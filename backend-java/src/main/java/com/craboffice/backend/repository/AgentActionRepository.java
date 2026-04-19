package com.craboffice.backend.repository;

import com.craboffice.backend.entity.AgentActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentActionEntity, Long> {
    List<AgentActionEntity> findByRoomIdOrderByCreatedAtAsc(Long roomId);
}
