package com.lifepilot.observability.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TraceController 集成测试。
 *
 * <p>通过 MockMvc 验证统计、搜索、导出、Token 统计和评估端点的行为。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceController集成测试 {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TraceQuery traceQuery;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * 概览统计_默认时间窗口为7天().
     */
    @Test
    void 概览统计_默认时间窗口为7天() throws Exception {
        var mvcResult = mockMvc.perform(get("/api/traces/stats/overview")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTraces").exists())
                .andExpect(jsonPath("$.successCount").exists())
                .andExpect(jsonPath("$.failureCount").exists())
                .andExpect(jsonPath("$.successRate").exists())
                .andExpect(jsonPath("$.avgSteps").exists())
                .andExpect(jsonPath("$.avgDurationMs").exists())
                .andExpect(jsonPath("$.totalTokens").exists())
                .andExpect(jsonPath("$.avgTokens").exists())
                .andReturn();

        var body = mvcResult.getResponse().getContentAsString();
        var stats = objectMapper.readValue(body, OverviewStats.class);
        assertThat(stats.totalTraces()).isGreaterThanOrEqualTo(0);
        assertThat(stats.successCount()).isGreaterThanOrEqualTo(0);
        assertThat(stats.failureCount()).isGreaterThanOrEqualTo(0);
    }

    /**
     * 工具统计_返回列表结构().
     */
    @Test
    void 工具统计_返回列表结构() throws Exception {
        var mvcResult = mockMvc.perform(get("/api/traces/stats/tools")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        var body = mvcResult.getResponse().getContentAsString();
        var list = objectMapper.readValue(body,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ToolUsageStats.class));
        assertThat(list).isNotNull();
    }

    /**
     * 搜索关键词为空_返回400().
     */
    @Test
    void 搜索关键词为空_返回400() throws Exception {
        mockMvc.perform(get("/api/traces/search")
                        .param("keyword", "")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * Token统计_缺省时间使用默认值().
     */
    @Test
    void Token统计_缺省时间使用默认值() throws Exception {
        mockMvc.perform(get("/api/traces/stats/tokens")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceCount").exists())
                .andExpect(jsonPath("$.totalTokens").exists());
    }

    /**
     * Token统计_非法时间格式_返回400().
     */
    @Test
    void Token统计_非法时间格式_返回400() throws Exception {
        mockMvc.perform(get("/api/traces/stats/tokens")
                        .param("start", "not-an-instant")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 导出轨迹_不存在ID返回404().
     */
    @Test
    void 导出轨迹_不存在ID返回404() throws Exception {
        mockMvc.perform(get("/api/traces/non-exists-id/export")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 评估结果_不存在时返回404().
     */
    @Test
    void 评估结果_不存在时返回404() throws Exception {
        mockMvc.perform(get("/api/traces/non-exists-id/evaluation")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}

