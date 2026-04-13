package com.craboffice.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "container_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContainerLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private String status;

    private String command;

    private Integer exitCode;

    @Column(columnDefinition = "TEXT")
    private String stdout;

    @Column(columnDefinition = "TEXT")
    private String stderr;

    private Boolean timedOut;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
