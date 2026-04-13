package com.craboffice.backend.controller;

import com.craboffice.backend.dto.SandboxFileContentResponse;
import com.craboffice.backend.dto.SandboxFileEntryDto;
import com.craboffice.backend.dto.SandboxFileListResponse;
import com.craboffice.backend.service.SandboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SandboxController.class)
class SandboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SandboxService sandboxService;

    @Test
    void returnsRoomFileList() throws Exception {
        when(sandboxService.listFiles(4L)).thenReturn(
                new SandboxFileListResponse(4L, List.of(new SandboxFileEntryDto("src/App.java", "App.java", 12L)))
        );

        mockMvc.perform(get("/api/rooms/4/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].path").value("src/App.java"));
    }

    @Test
    void returnsRoomFileContent() throws Exception {
        when(sandboxService.readFile(4L, "README.md")).thenReturn(
                new SandboxFileContentResponse(4L, "README.md", "hello", 5L)
        );

        mockMvc.perform(get("/api/rooms/4/files/content").param("path", "README.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello"));
    }
}
