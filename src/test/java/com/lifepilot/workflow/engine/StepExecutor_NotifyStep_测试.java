package com.lifepilot.workflow.engine;

import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.Urgency;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowStep.NotifyStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link StepExecutor} NotifyStep 单元测试。
 *
 * <p>通过 public {@code execute()} 方法间接测试 private {@code executeNotify()} 逻辑。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class StepExecutor_NotifyStep_测试 {

    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillActivator skillActivator;
    @Mock private DynamicToolRegistry toolRegistry;
    @Mock private LlmRouter llmRouter;
    @Mock private MultimodalRouter multimodalRouter;
    @Mock private NotificationService notificationService;

    private WorkflowConfigProperties config;
    private ExpressionEngine expressionEngine;
    private StepExecutor stepExecutor;

    @BeforeEach
    void setUp() {
        config = new WorkflowConfigProperties();
        expressionEngine = new ExpressionEngine();
        stepExecutor = new StepExecutor(
                skillRegistry, skillActivator, toolRegistry,
                llmRouter, multimodalRouter, config, notificationService);
    }

    // ── 表达式解析 ────────────────────────────────────────

    @Nested
    class 表达式解析 {

        @Test
        void executeNotify_解析targetUserId和content中的表达式() {
            when(notificationService.send(any())).thenReturn(List.of("n-1"));

            var context = new WorkflowContext();
            context.set("inputs", Map.of("userId", "user-123", "taskName", "审批任务"));

            var step = new NotifyStep("notify-1", "通知步骤",
                    "${inputs.userId}", "你的${inputs.taskName}已完成",
                    "TEXT", Urgency.HIGH, List.of(), null);

            stepExecutor.execute(step, context, expressionEngine);

            var captor = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notificationService).send(captor.capture());

            var request = captor.getValue();
            assertEquals("user-123", request.targetUserId());
            assertInstanceOf(ResponseContent.TextContent.class, request.content());
            assertEquals("你的审批任务已完成", ((ResponseContent.TextContent) request.content()).text());
        }
    }

    // ── contentType 映射 ─────────────────────────────────

    @Nested
    class ContentType映射 {

        @Test
        void contentType_TEXT_生成TextContent() {
            when(notificationService.send(any())).thenReturn(List.of("n-1"));

            var step = new NotifyStep("notify-1", "通知", "user-1", "纯文本",
                    "TEXT", Urgency.MEDIUM, List.of(), null);

            stepExecutor.execute(step, new WorkflowContext(), expressionEngine);

            var captor = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notificationService).send(captor.capture());
            assertInstanceOf(ResponseContent.TextContent.class, captor.getValue().content());
        }

        @Test
        void contentType_MARKDOWN_生成MarkdownContent() {
            when(notificationService.send(any())).thenReturn(List.of("n-1"));

            var step = new NotifyStep("notify-2", "通知", "user-1", "# 标题",
                    "MARKDOWN", Urgency.MEDIUM, List.of(), null);

            stepExecutor.execute(step, new WorkflowContext(), expressionEngine);

            var captor = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notificationService).send(captor.capture());
            assertInstanceOf(ResponseContent.MarkdownContent.class, captor.getValue().content());
        }

        @Test
        void contentType_CARD_生成CardContent() {
            when(notificationService.send(any())).thenReturn(List.of("n-1"));

            var step = new NotifyStep("notify-3", "通知", "user-1", "卡片内容",
                    "CARD", Urgency.HIGH, List.of(), null);

            stepExecutor.execute(step, new WorkflowContext(), expressionEngine);

            var captor = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notificationService).send(captor.capture());
            assertInstanceOf(ResponseContent.CardContent.class, captor.getValue().content());
        }
    }

    // ── NotificationService 调用 ─────────────────────────

    @Nested
    class 通知服务调用 {

        @Test
        void send_使用正确的NotificationRequest() {
            when(notificationService.send(any())).thenReturn(List.of("n-1", "n-2"));

            var step = new NotifyStep("notify-1", "通知步骤", "user-1", "测试内容",
                    "TEXT", Urgency.HIGH, List.of(), null);

            stepExecutor.execute(step, new WorkflowContext(), expressionEngine);

            var captor = ArgumentCaptor.forClass(NotificationRequest.class);
            verify(notificationService).send(captor.capture());

            var request = captor.getValue();
            assertEquals("user-1", request.targetUserId());
            assertEquals(Urgency.HIGH, request.urgency());
            assertEquals("notify-1", request.metadata().get("workflowStepId"));
        }
    }

    // ── 返回结果 ─────────────────────────────────────────

    @Nested
    class 返回结果 {

        @Test
        void 结果包含success和notificationIds和targetUserId() {
            when(notificationService.send(any())).thenReturn(List.of("n-1", "n-2"));

            var step = new NotifyStep("notify-1", "通知步骤", "user-1", "测试",
                    "TEXT", Urgency.MEDIUM, List.of(), null);

            var result = stepExecutor.execute(step, new WorkflowContext(), expressionEngine);

            assertEquals(true, result.get("success"));
            assertEquals(List.of("n-1", "n-2"), result.get("notificationIds"));
            assertEquals("user-1", result.get("targetUserId"));
        }
    }
}
