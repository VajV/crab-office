package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SandboxFileContentResponse {
    private long roomId;
    private String path;
    private String content;
    private long size;
}
