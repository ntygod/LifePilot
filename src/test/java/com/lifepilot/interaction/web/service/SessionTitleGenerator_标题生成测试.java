package com.lifepilot.interaction.web.service;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionTitleGenerator 单元测试。
 *
 * <p>覆盖 cleanTitle 边界情况、generateIfNeeded 跳过条件、正常生成路径和异常容错。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
@ExtendWith(MockitoExtension.class)
class SessionTitleGenerator_标题生成测试 {

    @Mock
    private ChatSessionRepository sessionRepository;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    @Mock
    private SseSessionManager sseSessionManager;

    private SessionTitleGenerator generator;

    @BeforeEach
    void 初始化() {
        generator = new SessionTitleGenerator(
                sessionRepository,
                generationRouter,
                promptRegistry,
                sseSessionManager,
                30
        );
    }

    // ===== cleanTitle 边界测试（通过 generateIfNeeded 间接触发） =====

    @Nested
    class CleanTitle清理逻辑 {

        /**
         * 通过完整生成路径间接测试 cleanTitle，mock LLM 返回指定原始文本。
         */
        private void 模拟生成并验证标题更新(String llmOutput, String expectedTitle) {
            String sessionId = "test-session-id";
            String userMessage = "你好世界";

            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse(llmOutput, 10, 5, "p1", "m1", 100, false));

            generator.generateIfNeeded(sessionId, userMessage);

            if (expectedTitle.isEmpty()) {
                verify(sessionRepository, never()).updateTitle(anyString(), anyString());
            } else {
                verify(sessionRepository).updateTitle(sessionId, expectedTitle);
            }
        }

        @Test
        void 去除首尾双引号() {
            模拟生成并验证标题更新("\"智能助手对话\"", "智能助手对话");
        }

        @Test
        void 去除首尾书名号() {
            模拟生成并验证标题更新("《天气查询》", "天气查询");
        }

        @Test
        void 去除首尾单引号() {
            模拟生成并验证标题更新("'每日新闻'", "每日新闻");
        }

        @Test
        void 去除中文引号() {
            模拟生成并验证标题更新("「日程安排」", "日程安排");
        }

        @Test
        void 嵌套引号全部去除_正则贪婪匹配() {
            // 正则 [\"'《「]+ 贪婪匹配所有连续引号字符，《" 和 "》 都被去除
            模拟生成并验证标题更新("《\"核心功能\"》", "核心功能");
        }

        @Test
        void 内部引号不影响_仅去首尾() {
            // 中间的引号不受影响，只有首尾匹配的引号被移除
            模拟生成并验证标题更新("数据\"分析\"报告", "数据\"分析\"报告");
        }

        @Test
        void 去除末尾中文标点() {
            模拟生成并验证标题更新("会议记录。", "会议记录");
        }

        @Test
        void 去除末尾多个标点() {
            模拟生成并验证标题更新("项目讨论！！！", "项目讨论");
        }

        @Test
        void 去除末尾英文标点() {
            模拟生成并验证标题更新("meeting notes...", "meeting notes");
        }

        @Test
        void 超长标题截断到20字符加省略号() {
            // 21个字符的标题 → 截断为前20个 + "…"
            String longTitle = "这是一个非常非常非常长的会话标题内容超过限制";  // 19字符不够
            String veryLongTitle = "一二三四五六七八九十壹贰叁肆伍陆柒捌玖廿额外"; // 22字符
            模拟生成并验证标题更新(veryLongTitle, "一二三四五六七八九十壹贰叁肆伍陆柒捌玖廿…");
        }

        @Test
        void 恰好20字符不截断() {
            String exactly20 = "一二三四五六七八九十壹贰叁肆伍陆柒捌玖廿"; // 20字符
            模拟生成并验证标题更新(exactly20, exactly20);
        }

        @Test
        void null返回空字符串_不更新标题() {
            String sessionId = "test-session-id";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse(null, 10, 5, "p1", "m1", 100, false));

            generator.generateIfNeeded(sessionId, "你好");

            verify(sessionRepository, never()).updateTitle(anyString(), anyString());
        }

        @Test
        void 纯标点清理后为空_不更新标题() {
            模拟生成并验证标题更新("。！？", "");
        }

        @Test
        void 前后空白被trim() {
            模拟生成并验证标题更新("  代码审查  ", "代码审查");
        }

        @Test
        void 清理后等于默认标题_不更新() {
            模拟生成并验证标题更新("\"新对话\"", "");
        }

        @Test
        void 清理后等于英文默认标题_不更新() {
            模拟生成并验证标题更新("New Chat", "");
        }
    }

    // ===== generateIfNeeded 跳过条件 =====

    @Nested
    class 跳过条件 {

        @Test
        void 路由器为null时直接返回() {
            var gen = new SessionTitleGenerator(sessionRepository, null, promptRegistry, sseSessionManager, 30);
            gen.generateIfNeeded("session-1", "你好");
            verify(sessionRepository, never()).findById(anyString());
        }

        @Test
        void 提示注册器为null时直接返回() {
            var gen = new SessionTitleGenerator(sessionRepository, generationRouter, null, sseSessionManager, 30);
            gen.generateIfNeeded("session-1", "你好");
            verify(sessionRepository, never()).findById(anyString());
        }

        @Test
        void sessionId为null时跳过() {
            generator.generateIfNeeded(null, "你好");
            verify(sessionRepository, never()).findById(anyString());
        }

        @Test
        void channel会话不调LLM_走固定标题组装() {
            String sessionId = "oc_bb66280a31f2a8cd8e42aba47966181e";  // 飞书真实格式
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            generator.generateIfNeeded(sessionId, "帮我筛选一下炊事员", "feishu");

            // 不调用 LLM
            verify(generationRouter, never()).call(anyString(), anyString(), any(), any(), any(), any(), any());
            // 但会更新标题为 "飞书 · 帮我筛选一下炊事员"
            verify(sessionRepository).updateTitle(eq(sessionId), eq("飞书 · 帮我筛选一下炊事员"));
        }

        @Test
        void channel会话userMessage为空时退化为平台名对话() {
            String sessionId = "oc_channel_session";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            generator.generateIfNeeded(sessionId, "", "feishu");

            verify(sessionRepository).updateTitle(eq(sessionId), eq("飞书对话"));
            verify(generationRouter, never()).call(anyString(), anyString(), any(), any(), any(), any(), any());
        }

        @Test
        void channel会话userMessage是connector技术串时过滤() {
            String sessionId = "oc_file_session";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            // 飞书纯文件消息，content 是 connector 塞的 file_key + fileName
            generator.generateIfNeeded(sessionId,
                    "file_v3_00110_abc123\n16392090_xxx.xlsx", "feishu");

            // 识别出 file_v3_ 前缀是技术串，退化为 "飞书对话"
            verify(sessionRepository).updateTitle(eq(sessionId), eq("飞书对话"));
        }

        @Test
        void channel会话userMessage超长时截断() {
            String sessionId = "oc_long_session";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            generator.generateIfNeeded(sessionId,
                    "这是一条非常非常非常非常非常非常非常长的用户消息", "feishu");

            // 截断到 MAX_TITLE_LENGTH（20 字）+ …
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(sessionRepository).updateTitle(eq(sessionId), captor.capture());
            String title = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(title).startsWith("飞书 · ");
            org.assertj.core.api.Assertions.assertThat(title.length()).isLessThanOrEqualTo(21);  // 20 + "…"
        }

        @Test
        void 钉钉企微QQ平台名映射到中文() {
            java.util.Map<String, String> expected = java.util.Map.of(
                    "dingtalk", "钉钉对话",
                    "wecom", "企业微信对话",
                    "qq", "QQ对话"
            );
            expected.forEach((platform, expectedTitle) -> {
                String sessionId = "ch_" + platform;
                var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                        Instant.now(), Instant.now(), Instant.now());
                when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
                generator.generateIfNeeded(sessionId, "", platform);
                verify(sessionRepository).updateTitle(eq(sessionId), eq(expectedTitle));
            });
        }

        @ParameterizedTest(name = "userMessage=\"{0}\"时跳过")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        void userMessage为空或空白时跳过(String userMessage) {
            generator.generateIfNeeded("valid-session-id", userMessage);
            verify(sessionRepository, never()).findById(anyString());
        }

        @Test
        void 会话不存在时跳过() {
            when(sessionRepository.findById("non-exist")).thenReturn(Optional.empty());
            generator.generateIfNeeded("non-exist", "你好");
            verify(generationRouter, never()).call(anyString(), anyString(), any(), any(), any(), any(), any());
        }

        @Test
        void 当前标题不是默认值时跳过() {
            String sessionId = "session-with-title";
            var session = new ChatSession(sessionId, "已有标题", null, 5, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

            generator.generateIfNeeded(sessionId, "你好");

            verify(generationRouter, never()).call(anyString(), anyString(), any(), any(), any(), any(), any());
        }

        @Test
        void 标题为NewChat英文默认值时触发生成() {
            String sessionId = "session-en";
            var session = new ChatSession(sessionId, "New Chat", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());
            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse("英文对话", 10, 5, "p1", "m1", 100, false));

            generator.generateIfNeeded(sessionId, "Hello");

            verify(sessionRepository).updateTitle(sessionId, "英文对话");
        }
    }

    // ===== 正常生成路径 =====

    @Nested
    class 正常生成路径 {

        @Test
        void 完整生成流程_更新数据库并推送SSE() {
            String sessionId = "session-123";
            String userMessage = "帮我查一下明天的天气预报";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("rendered prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("rendered prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse("天气预报查询", 20, 8, "openai", "gpt-4o-mini", 200, false));

            generator.generateIfNeeded(sessionId, userMessage);

            verify(sessionRepository).updateTitle(sessionId, "天气预报查询");
            verify(sseSessionManager).broadcastByPrefix(
                    eq("notification-"),
                    eq("title-generated"),
                    eq(Map.of("sessionId", sessionId, "title", "天气预报查询"))
            );
        }

        @Test
        void 超长用户消息截断到500字符传给LLM() {
            String sessionId = "session-long-msg";
            String longMessage = "重".repeat(600);
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> vars = invocation.getArgument(1, Map.class);
                String msg = (String) vars.get("userMessage");
                // 验证消息被截断到 500 + "…"
                assertEquals(501, msg.length());
                assertEquals("…", msg.substring(500));
                return "prompt";
            });
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse("长消息讨论", 20, 8, "p1", "m1", 200, false));

            generator.generateIfNeeded(sessionId, longMessage);

            verify(sessionRepository).updateTitle(sessionId, "长消息讨论");
        }

        @Test
        void SseSessionManager为null时不推送但仍更新数据库() {
            var gen = new SessionTitleGenerator(sessionRepository, generationRouter, promptRegistry, null, 30);
            String sessionId = "session-no-sse";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse("无SSE标题", 10, 5, "p1", "m1", 100, false));

            gen.generateIfNeeded(sessionId, "你好");

            verify(sessionRepository).updateTitle(sessionId, "无SSE标题");
        }
    }

    // ===== 异常容错 =====

    @Nested
    class 异常容错 {

        @Test
        void LLM调用抛异常时不更新标题_不抛出() {
            String sessionId = "session-err";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenThrow(new RuntimeException("连接超时"));

            // 不应抛出异常
            generator.generateIfNeeded(sessionId, "你好");

            verify(sessionRepository, never()).updateTitle(anyString(), anyString());
        }

        @Test
        void 模板渲染异常时不更新标题_不抛出() {
            String sessionId = "session-tpl-err";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any()))
                    .thenThrow(new RuntimeException("模板未找到"));

            generator.generateIfNeeded(sessionId, "你好");

            verify(sessionRepository, never()).updateTitle(anyString(), anyString());
        }

        @Test
        void SSE广播异常时标题仍然更新成功() {
            String sessionId = "session-sse-err";
            var session = new ChatSession(sessionId, "新对话", null, 1, false, false,
                    Instant.now(), Instant.now(), Instant.now());

            when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
            when(promptRegistry.render(eq("generation/session-title"), any())).thenReturn("prompt");
            when(generationRouter.call(
                    eq("session-title"), eq("prompt"), eq(null), eq(null), eq(null),
                    eq(GenerationCapability.CHAT), any(Duration.class)
            )).thenReturn(new LlmResponse("正常标题", 10, 5, "p1", "m1", 100, false));
            // SSE 广播时抛异常 — pushTitleUpdate 内部 catch 了，不影响主流程
            // 注意：broadcastByPrefix 异常在 pushTitleUpdate 内被 catch，不会传播到 generateIfNeeded
            // 但如果 broadcastByPrefix 抛出的是 Error 类型，这里测试的是 Exception
            org.mockito.Mockito.doThrow(new RuntimeException("SSE 发送失败"))
                    .when(sseSessionManager).broadcastByPrefix(anyString(), anyString(), any());

            generator.generateIfNeeded(sessionId, "你好");

            // 标题仍然成功更新
            verify(sessionRepository).updateTitle(sessionId, "正常标题");
        }
    }
}
