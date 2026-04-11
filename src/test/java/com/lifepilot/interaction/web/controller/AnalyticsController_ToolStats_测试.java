package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.repository.AnalyticsRepository;
import com.lifepilot.interaction.web.repository.AnalyticsRepository.ToolCallStepRow;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.multiagent.registry.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AnalyticsController Tool 调用统计端点单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsController_ToolStats_测试 {

    @Mock
    private AnalyticsRepository analyticsRepository;

    @Mock
    private KnowledgeBaseManager knowledgeBaseManager;

    @Mock
    private com.lifepilot.tool.registry.DynamicToolRegistry toolRegistry;

    @Mock
    private AgentRegistry agentRegistry;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var controller = new AnalyticsController(
                analyticsRepository,
                knowledgeBaseManager,
                objectMapper,
                toolRegistry,
                agentRegistry
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 有数据时_返回按调用次数降序的统计和趋势() throws Exception {
        // 模拟 trace_steps 查询结果
        String timestamp1 = "2026-03-01T10:00:00Z";
        String timestamp2 = "2026-03-01T11:00:00Z";
        String timestamp3 = "2026-03-02T09:00:00Z";

        List<ToolCallStepRow> rows = List.of(
                new ToolCallStepRow("{\"toolId\":\"todo-add\",\"success\":true}", 100L, timestamp1),
                new ToolCallStepRow("{\"toolId\":\"todo-add\",\"success\":true}", 200L, timestamp2),
                new ToolCallStepRow("{\"toolId\":\"todo-add\",\"success\":false}", 300L, timestamp3),
                new ToolCallStepRow("{\"toolId\":\"schedule-query\",\"success\":true}", 50L, timestamp1)
        );

        when(analyticsRepository.queryToolCallSteps(anyString(), anyString())).thenReturn(rows);

        mockMvc.perform(get("/api/analytics/tools")
                        .param("from", "2026-03-01T00:00:00Z")
                        .param("to", "2026-03-03T00:00:00Z"))
                .andExpect(status().isOk())
                // todo-add 有 3 次调用，排在前面
                .andExpect(jsonPath("$.data.toolStats", hasSize(2)))
                .andExpect(jsonPath("$.data.toolStats[0].toolId").value("todo-add"))
                .andExpect(jsonPath("$.data.toolStats[0].callCount").value(3))
                .andExpect(jsonPath("$.data.toolStats[0].successCount").value(2))
                .andExpect(jsonPath("$.data.toolStats[0].failureCount").value(1))
                .andExpect(jsonPath("$.data.toolStats[0].avgLatencyMs").value(200)) // (100+200+300)/3
                .andExpect(jsonPath("$.data.toolStats[1].toolId").value("schedule-query"))
                .andExpect(jsonPath("$.data.toolStats[1].callCount").value(1))
                // 每日趋势按日期排序
                .andExpect(jsonPath("$.data.dailyTrend", hasSize(2)))
                .andExpect(jsonPath("$.data.dailyTrend[0].date").value("2026-03-01"))
                .andExpect(jsonPath("$.data.dailyTrend[0].callCount").value(3))
                .andExpect(jsonPath("$.data.dailyTrend[0].successCount").value(3))
                .andExpect(jsonPath("$.data.dailyTrend[1].date").value("2026-03-02"))
                .andExpect(jsonPath("$.data.dailyTrend[1].callCount").value(1))
                .andExpect(jsonPath("$.data.dailyTrend[1].failureCount").value(1));
    }

    @Test
    void 无数据时_返回空列表() throws Exception {
        when(analyticsRepository.queryToolCallSteps(anyString(), anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/analytics/tools")
                        .param("from", "2026-03-01T00:00:00Z")
                        .param("to", "2026-03-03T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolStats", hasSize(0)))
                .andExpect(jsonPath("$.data.dailyTrend", hasSize(0)));
    }

    @Test
    void 缺失时间参数时_默认最近30天() throws Exception {
        when(analyticsRepository.queryToolCallSteps(anyString(), anyString())).thenReturn(List.of());

        // 不传 from/to 参数，应该不报错并返回空结果
        mockMvc.perform(get("/api/analytics/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolStats", hasSize(0)))
                .andExpect(jsonPath("$.data.dailyTrend", hasSize(0)));
    }
}
