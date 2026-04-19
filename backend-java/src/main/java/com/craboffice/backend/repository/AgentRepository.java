package com.craboffice.backend.repository;

import com.craboffice.backend.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {
    Optional<AgentEntity> findByRoomIdAndExternalId(Long roomId, String externalId);
    List<AgentEntity> findByRoomIdOrderByIdAsc(Long roomId);
}
