package com.lifepilot.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReactAgentLoop.run(sessionId, UserMessage) 便捷入口签名单元测试。
 *
 * <p>完整 E2E 验证在 Task 9 之后（FixtureBackedGenerationRouter 搭好）再补；
 * 本测试仅通过反射验证新 API 的方法签名、返回类型和参数类型正确，避免在当前
 * 任务阶段实例化 ReactAgentLoop（其构造器依赖众多协作者）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ReactAgentLoop_单轮调用入口_单元测试 {

    @Test
    void 单轮调用入口应返回TurnResult并接受sessionId和UserMessage() throws Exception {
        Method method = ReactAgentLoop.class.getDeclaredMethod(
                "run", String.class, UserMessage.class);

        assertThat(method.getReturnType())
                .as("run 方法应返回 TurnResult")
                .isEqualTo(TurnResult.class);
        assertThat(method.getParameterCount())
                .as("run 方法应接受两个参数")
                .isEqualTo(2);
        assertThat(method.getParameterTypes()[0])
                .as("第一个参数应为 sessionId (String)")
                .isEqualTo(String.class);
        assertThat(method.getParameterTypes()[1])
                .as("第二个参数应为 Spring AI UserMessage")
                .isEqualTo(UserMessage.class);
    }

    @Test
    void 结果类应暴露预期的只读字段() {
        assertThat(TurnResult.class.isRecord())
                .as("TurnResult 必须是 record")
                .isTrue();
        var components = TurnResult.class.getRecordComponents();
        assertThat(components).extracting(c -> c.getName())
                .as("TurnResult 的 record 组件应与设计保持一致")
                .containsExactly("sessionId", "turnId", "toolInvocations", "finalText", "completed");
    }

    @Test
    void 工具调用快照应暴露tool和argsJson和resultJson三字段() {
        assertThat(TurnResult.ToolInvocation.class.isRecord())
                .as("TurnResult.ToolInvocation 必须是 record")
                .isTrue();
        var components = TurnResult.ToolInvocation.class.getRecordComponents();
        assertThat(components).extracting(c -> c.getName())
                .as("ToolInvocation 应按顺序暴露 tool / argsJson / resultJson")
                .containsExactly("tool", "argsJson", "resultJson");
    }
}
