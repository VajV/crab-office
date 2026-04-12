package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageDto {
    private Long id;
    private Long roomId;
    private String agentExternalId;
    private String senderType;
    private String content;
    private String createdAt;
}
