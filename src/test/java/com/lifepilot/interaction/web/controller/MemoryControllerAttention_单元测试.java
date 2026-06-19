package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.agent.learning.forgetting.ForgettingLogRepository;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionItem;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionKind;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MemoryController 注意力端点测试。
 *
 * @author zsg
 * @since 2026-06-07
 */
class MemoryControllerAttention_单元测试 {

    private SemanticMemory semanticMemory;
    private MemoryAttentionService attentionService;
    private ForgettingLogRepository forgettingLogRepository;
    private MemoryProvenanceRepository provenanceRepository;
    private ProjectContextResolver projectContextResolver;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        attentionService = mock(MemoryAttentionService.class);
        forgettingLogRepository = mock(ForgettingLogRepository.class);
        provenanceRepository = mock(MemoryProvenanceRepository.class);
        projectContextResolver = mock(ProjectContextResolver.class);
        when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));
    }

    private MemoryController controller(SemanticMemory sm, MemoryAttentionService svc) {
        return new MemoryController(
                sm, null, null, null, null, null, null, null,
                forgettingLogRepository, provenanceRepository, projectContextResolver,
                new MemoryAccessPolicy(), null, svc);
    }

    @Test
    void attention端点返回注意力清单() throws Exception {
        when(attentionService.computeAttention(any(), anyInt())).thenReturn(List.of(
                new AttentionItem("g1", "学小提琴", "GOAL", AttentionKind.EXPIRING,
                        0.9f, "「学小提琴」将在 3 天后到期", Instant.parse("2026-06-10T00:00:00Z"), null, null)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(semanticMemory, attentionService)).build();

        mvc.perform(get("/api/memories/attention").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].entityId").value("g1"))
                .andExpect(jsonPath("$.data[0].kind").value("EXPIRING"))
                .andExpect(jsonPath("$.data[0].reason").value("「学小提琴」将在 3 天后到期"));
    }

    @Test
    void 记忆未启用返回503() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(null, attentionService)).build();
        mvc.perform(get("/api/memories/attention"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void limit非法返回400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(semanticMemory, attentionService)).build();
        mvc.perform(get("/api/memories/attention").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 服务未注入返回空清单() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(semanticMemory, null)).build();
        mvc.perform(get("/api/memories/attention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
