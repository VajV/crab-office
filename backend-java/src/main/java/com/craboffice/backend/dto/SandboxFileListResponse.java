package com.craboffice.backend.dto;

import lombok.*;

import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SandboxFileListResponse {
    private long roomId;
    private List<SandboxFileEntryDto> files;
}
