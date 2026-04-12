package com.craboffice.backend.repository;

import com.craboffice.backend.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {
    List<TaskEntity> findByRoomIdOrderByCreatedAtDesc(Long roomId);
}
