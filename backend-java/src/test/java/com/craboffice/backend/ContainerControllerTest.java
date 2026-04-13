package com.craboffice.backend;

import com.craboffice.backend.controller.ContainerController;
import com.craboffice.backend.dto.ContainerEventDto;
import com.craboffice.backend.service.ContainerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContainerController.class)
class ContainerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContainerService containerService;

    @Test
    void getLogs_returnsContainerEvents() throws Exception {
        ContainerEventDto event = ContainerEventDto.builder()
                .roomId(1L)
                .status("stopped")
                .command("python hello.py")
                .exitCode(0)
                .stdout("Hello!")
                .stderr("")
                .timedOut(false)
                .timestamp("2026-04-13T10:00:00Z")
                .build();

        when(containerService.getLogs(1L)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/rooms/1/container/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomId").value(1))
                .andExpect(jsonPath("$[0].status").value("stopped"))
                .andExpect(jsonPath("$[0].command").value("python hello.py"))
                .andExpect(jsonPath("$[0].exitCode").value(0))
                .andExpect(jsonPath("$[0].stdout").value("Hello!"));
    }

    @Test
    void getLogs_emptyList() throws Exception {
        when(containerService.getLogs(99L)).thenReturn(List.of());

        mockMvc.perform(get("/api/rooms/99/container/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
