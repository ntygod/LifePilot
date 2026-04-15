package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.reminder.ReminderFeedbackRecord;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.agent.task.reminder.ReminderFeedbackType;
import com.lifepilot.agent.task.reminder.ReminderNotificationFeedbackView;
import com.lifepilot.agent.task.reminder.ReminderTopicPreferenceRecord;
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
import java.util.Map;
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
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private ReminderFeedbackRepository reminderFeedbackRepository;

    private NotificationProperties properties;
    private MockMvc mockMvc;

    private static final Instant NOW = Instant.parse("2026-03-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        var controller = new NotificationController(notificationRepository, properties, reminderFeedbackRepository, null, null);
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
            when(reminderFeedbackRepository.findFeedbackViewsByNotificationIds(List.of("n-1", "n-2")))
                    .thenReturn(Map.of(
                            "n-1",
                            new ReminderNotificationFeedbackView(
                                    "n-1",
                                    "conversation:web:conv-1",
                                    ReminderFeedbackType.ACTED,
                                    "这次提醒刚好",
                                    NOW.plusSeconds(120),
                                    true
                            )
                    ));

            mockMvc.perform(get("/api/notifications")
                            .param("userId", "user-1")
                            .param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(2)))
                    .andExpect(jsonPath("$.data.items[0].feedbackType", is("ACTED")))
                    .andExpect(jsonPath("$.data.items[0].topicMuted", is(true)))
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
            when(reminderFeedbackRepository.findFeedbackViewByNotificationId("n-1")).thenReturn(Optional.empty());

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

    @Nested
    class 主动提醒反馈 {

        @Test
        void 提交反馈后返回更新后的通知() throws Exception {
            String metadataJson = "{\"topicKey\":\"conversation:web:conv-1\"}";
            var record = new NotificationRecord(
                    "n-1", "user-1", "proactive_reminder", "{\"text\":\"test\"}", "WEB", "UNREAD", "SENT",
                    metadataJson, NOW, NOW, NOW
            );
            var updated = new NotificationRecord(
                    "n-1", "user-1", "proactive_reminder", "{\"text\":\"test\"}", "WEB", "READ", "SENT",
                    metadataJson, NOW, NOW, NOW.plusSeconds(30)
            );

            when(notificationRepository.findById("n-1")).thenReturn(Optional.of(record), Optional.of(updated));
            when(reminderFeedbackRepository.findFeedbackViewByNotificationId("n-1"))
                    .thenReturn(Optional.of(new ReminderNotificationFeedbackView(
                            "n-1",
                            "conversation:web:conv-1",
                            ReminderFeedbackType.ACTED,
                            "已经处理",
                            NOW.plusSeconds(30),
                            true
                    )));

            mockMvc.perform(post("/api/notifications/n-1/feedback")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "feedbackType": "acted",
                                      "comment": "已经处理",
                                      "muteTopic": true
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id", is("n-1")))
                    .andExpect(jsonPath("$.data.readStatus", is("READ")))
                    .andExpect(jsonPath("$.data.feedbackType", is("ACTED")))
                    .andExpect(jsonPath("$.data.topicMuted", is(true)));

            verify(reminderFeedbackRepository).saveFeedback(org.mockito.ArgumentMatchers.any(ReminderFeedbackRecord.class));
            verify(reminderFeedbackRepository).upsertTopicPreference(org.mockito.ArgumentMatchers.any(ReminderTopicPreferenceRecord.class));
            verify(notificationRepository).markAsRead("n-1");
        }

        @Test
        void 反馈类型无效返回400() throws Exception {
            var record = new NotificationRecord(
                    "n-1", "user-1", "proactive_reminder", "{\"text\":\"test\"}", "WEB", "UNREAD", "SENT",
                    "{\"topicKey\":\"conversation:web:conv-1\"}", NOW, NOW, NOW
            );
            when(notificationRepository.findById("n-1")).thenReturn(Optional.of(record));

            mockMvc.perform(post("/api/notifications/n-1/feedback")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "feedbackType": "unknown"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void 非主动提醒不支持反馈() throws Exception {
            when(notificationRepository.findById("n-1")).thenReturn(Optional.of(testRecord("n-1")));

            mockMvc.perform(post("/api/notifications/n-1/feedback")
                            .contentType("application/json")
                            .content("""
                                    {
                                      "feedbackType": "dismissed"
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }
}
