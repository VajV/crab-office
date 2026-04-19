package com.craboffice.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "agent_actions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    private String agentExternalId;

    @Column(nullable = false)
    private String actionType;

    private String toolName;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String params;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
