package com.lifepilot.interaction.web.controller;

import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationSettingRecord;
import com.lifepilot.notification.Urgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link NotificationSettingsController} 集成测试（MockMvc standalone）。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class NotificationSettingsControllerTest {

    @Mock private NotificationRepository notificationRepository;

    private MockMvc mockMvc;

    private static final Instant NOW = Instant.parse("2026-03-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        var controller = new NotificationSettingsController(notificationRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── GET /api/notification-settings ────────────────────

    @Nested
    class 查询设置 {

        @Test
        void 返回用户所有通知设置() throws Exception {
            var settings = List.of(
                    new NotificationSettingRecord("s-1", "user-1", "alert", true,
                            List.of("WEB", "FEISHU"), Urgency.LOW, NOW, NOW),
                    new NotificationSettingRecord("s-2", "user-1", "reminder", false,
                            List.of("WEB"), Urgency.MEDIUM, NOW, NOW)
            );
            when(notificationRepository.findSettingsByUserId("user-1")).thenReturn(settings);

            mockMvc.perform(get("/api/notification-settings")
                            .param("userId", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].typeId", is("alert")))
                    .andExpect(jsonPath("$[0].enabled", is(true)))
                    .andExpect(jsonPath("$[1].typeId", is("reminder")))
                    .andExpect(jsonPath("$[1].enabled", is(false)));
        }
    }

    // ── PUT /api/notification-settings/{typeId} ───────────

    @Nested
    class 更新设置 {

        @Test
        void 新建设置_UPSERT行为() throws Exception {
            when(notificationRepository.findSettingByUserIdAndTypeId("user-1", "alert"))
                    .thenReturn(Optional.empty());

            mockMvc.perform(put("/api/notification-settings/alert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "userId": "user-1",
                                        "enabled": true,
                                        "channels": ["WEB", "FEISHU"],
                                        "minUrgency": "LOW"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.typeId", is("alert")))
                    .andExpect(jsonPath("$.enabled", is(true)))
                    .andExpect(jsonPath("$.channels", hasSize(2)));

            verify(notificationRepository).saveSetting(any(NotificationSettingRecord.class));
        }

        @Test
        void 更新已有设置_保留原ID() throws Exception {
            var existing = new NotificationSettingRecord("s-1", "user-1", "alert", true,
                    List.of("WEB"), Urgency.LOW, NOW, NOW);
            when(notificationRepository.findSettingByUserIdAndTypeId("user-1", "alert"))
                    .thenReturn(Optional.of(existing));

            mockMvc.perform(put("/api/notification-settings/alert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "userId": "user-1",
                                        "enabled": false,
                                        "channels": ["WEB", "FEISHU", "WECOM"],
                                        "minUrgency": "HIGH"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.typeId", is("alert")))
                    .andExpect(jsonPath("$.enabled", is(false)))
                    .andExpect(jsonPath("$.minUrgency", is("HIGH")));

            verify(notificationRepository).saveSetting(any(NotificationSettingRecord.class));
        }
    }
}
