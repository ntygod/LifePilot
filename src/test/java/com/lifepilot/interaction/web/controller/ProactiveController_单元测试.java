package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.proactive.QueuedActionRecord;
import com.lifepilot.agent.task.proactive.QueuedActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ProactiveController} 单元测试（MockMvc standalone）。
 *
 * @author zsg
 * @since 2026-04-15
 */
@ExtendWith(MockitoExtension.class)
class ProactiveController_单元测试 {

    @Mock private QueuedActionRepository queuedActionRepository;

    private MockMvc mockMvc;

    private static final Instant NOW = Instant.parse("2026-04-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        var controller = new ProactiveController(queuedActionRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private QueuedActionRecord testRecord(String id) {
        return new QueuedActionRecord(
                id, "default", "weather_report", "weather:daily",
                "今日天气", "晴，最高温度 25°C",
                0.85f, "{\"source\":\"openmeteo\"}", false, NOW, null
        );
    }

    // ── GET /api/proactive/queue ─────────────────────────────

    @Nested
    class 查询排队动作 {

        @Test
        void 默认参数查询排队动作() throws Exception {
            var records = List.of(testRecord("q-1"), testRecord("q-2"));
            when(queuedActionRepository.findPendingByUserId("default", 20))
                    .thenReturn(records);

            mockMvc.perform(get("/api/proactive/queue"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].id", is("q-1")))
                    .andExpect(jsonPath("$.data[0].behavior", is("weather_report")))
                    .andExpect(jsonPath("$.data[0].title", is("今日天气")))
                    .andExpect(jsonPath("$.data[0].metadata.source", is("openmeteo")));
        }

        @Test
        void 自定义userId和limit() throws Exception {
            when(queuedActionRepository.findPendingByUserId("user-1", 5))
                    .thenReturn(List.of(testRecord("q-1")));

            mockMvc.perform(get("/api/proactive/queue")
                            .param("userId", "user-1")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)));

            verify(queuedActionRepository).findPendingByUserId("user-1", 5);
        }

        @Test
        void limit超过50则截断为50() throws Exception {
            when(queuedActionRepository.findPendingByUserId("default", 50))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/proactive/queue")
                            .param("limit", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));

            verify(queuedActionRepository).findPendingByUserId("default", 50);
        }
    }

    // ── PUT /api/proactive/queue/{id}/shown ──────────────────

    @Nested
    class 标记已展示 {

        @Test
        void 标记排队动作为已展示() throws Exception {
            mockMvc.perform(put("/api/proactive/queue/q-1/shown"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)));

            verify(queuedActionRepository).markShown("q-1");
        }
    }

    // ── DELETE /api/proactive/queue/{id} ─────────────────────

    @Nested
    class 删除排队动作 {

        @Test
        void 删除指定排队动作() throws Exception {
            when(queuedActionRepository.deleteById("q-1")).thenReturn(1);

            mockMvc.perform(delete("/api/proactive/queue/q-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)));

            verify(queuedActionRepository).deleteById("q-1");
        }

        @Test
        void 删除不存在的排队动作仍返回200() throws Exception {
            when(queuedActionRepository.deleteById("not-exist")).thenReturn(0);

            mockMvc.perform(delete("/api/proactive/queue/not-exist"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(200)));
        }
    }
}
