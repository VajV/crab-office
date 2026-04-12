package com.craboffice.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    /** null for user messages */
    private String agentExternalId;

    @Column(nullable = false)
    private String senderType; // USER or AGENT

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
