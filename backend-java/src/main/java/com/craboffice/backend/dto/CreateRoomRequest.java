package com.craboffice.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class CreateRoomRequest {
    @NotBlank
    private String prompt;
    private String preset = "tech";
}
