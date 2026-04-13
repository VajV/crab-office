package com.craboffice.backend.repository;

import com.craboffice.backend.entity.ContainerLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ContainerLogRepository extends JpaRepository<ContainerLogEntity, Long> {
    List<ContainerLogEntity> findByRoomIdOrderByCreatedAtAsc(Long roomId);
}
