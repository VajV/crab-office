package com.craboffice.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SandboxFileEntryDto {
    private String path;
    private String name;
    private long size;
}
