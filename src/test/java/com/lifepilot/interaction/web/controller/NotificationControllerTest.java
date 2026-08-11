package com.lifepilot.interaction.web.controller;

import com.lifepilot.notification.NotificationRecord;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.config.NotificationProperties;
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
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link NotificationController} 集成测试（MockMvc standalone）。
 *
 * <p>主动提醒反馈端点随旧提醒决策栈一并移除，故不再覆盖。</p>
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private NotificationRepository notificationRepository;

    private NotificationProperties properties;
    private MockMvc mockMvc;

    private static final Instant NOW = Instant.parse("2026-03-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        var controller = new NotificationController(notificationRepository, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private NotificationRecord testRecord(String id) {
        return new NotificationRecord(id, "user-1", "alert",
                "{\"text\":\"test\"}", "WEB", "UNREAD", "SENT",
                null, NOW, NOW, NOW);
    }

    // ── GET /api/notifications ────────────────────────────

    @Nested
    class 分页查询 {

        @Test
        void 分页查询通知历史() throws Exception {
            var records = List.of(testRecord("n-1"), testRecord("n-2"));
            when(notificationRepository.findByUserId(eq("user-1"), eq(0), anyInt()))
                    .thenReturn(records);
            when(notificationRepository.countByUserId("user-1")).thenReturn(2L);

            mockMvc.perform(get("/api/notifications")
                            .param("userId", "user-1")
                            .param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(2)))
                    .andExpect(jsonPath("$.data.items[0].id", is("n-1")))
                    .andExpect(jsonPath("$.data.total", is(2)))
                    .andExpect(jsonPath("$.data.page", is(0)));
        }
    }

    // ── PUT /api/notifications/{id}/read ──────────────────

    @Nested
    class 标记已读 {

        @Test
        void 标记单条通知已读() throws Exception {
            var record = testRecord("n-1");
            var updated = new NotificationRecord(
                    "n-1", "user-1", "alert", "{\"text\":\"test\"}", "WEB", "READ", "SENT",
                    null, NOW, NOW, NOW.plusSeconds(60)
            );
            when(notificationRepository.findById("n-1")).thenReturn(Optional.of(record), Optional.of(updated));

            mockMvc.perform(put("/api/notifications/n-1/read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id", is("n-1")))
                    .andExpect(jsonPath("$.data.readStatus", is("READ")));

            verify(notificationRepository).markAsRead("n-1");
        }

        @Test
        void 通知不存在返回404() throws Exception {
            when(notificationRepository.findById("not-exist")).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/notifications/not-exist/read"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── PUT /api/notifications/read-all ───────────────────

    @Nested
    class 批量标记已读 {

        @Test
        void 标记所有通知已读_返回updatedCount() throws Exception {
            when(notificationRepository.markAllAsRead("user-1")).thenReturn(5);

            mockMvc.perform(put("/api/notifications/read-all")
                            .param("userId", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.updatedCount", is(5)));
        }
    }
}
