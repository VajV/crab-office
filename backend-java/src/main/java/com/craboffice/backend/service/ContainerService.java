package com.craboffice.backend.service;

import com.craboffice.backend.dto.ContainerEventDto;
import com.craboffice.backend.entity.ContainerLogEntity;
import com.craboffice.backend.repository.ContainerLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContainerService {

    private final ContainerLogRepository containerLogRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public void saveAndBroadcast(ContainerEventDto event) {
        ContainerLogEntity entity = ContainerLogEntity.builder()
                .roomId(event.getRoomId())
                .status(event.getStatus())
                .command(event.getCommand())
                .exitCode(event.getExitCode())
                .stdout(event.getStdout())
                .stderr(event.getStderr())
                .timedOut(event.getTimedOut())
                .createdAt(Instant.now())
                .build();
        containerLogRepository.save(entity);

        String destination = "/topic/rooms/" + event.getRoomId() + "/container";
        messagingTemplate.convertAndSend(destination, event);
        log.info("Container event saved & broadcast for room {}: {}", event.getRoomId(), event.getStatus());
    }

    public List<ContainerEventDto> getLogs(Long roomId) {
        return containerLogRepository.findByRoomIdOrderByCreatedAtAsc(roomId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private ContainerEventDto toDto(ContainerLogEntity entity) {
        return ContainerEventDto.builder()
                .roomId(entity.getRoomId())
                .status(entity.getStatus())
                .command(entity.getCommand())
                .exitCode(entity.getExitCode())
                .stdout(entity.getStdout())
                .stderr(entity.getStderr())
                .timedOut(entity.getTimedOut())
                .timestamp(entity.getCreatedAt().toString())
                .build();
    }
}
