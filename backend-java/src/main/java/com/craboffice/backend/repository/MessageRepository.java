package com.craboffice.backend.repository;

import com.craboffice.backend.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByRoomIdOrderByCreatedAtAsc(Long roomId);
}
