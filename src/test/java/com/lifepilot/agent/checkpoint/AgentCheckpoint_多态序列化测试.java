package com.lifepilot.agent.checkpoint;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.suspend.model.ResumePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Checkpoint 多态 JSON 序列化回归测试。
 *
 * <p>背景：{@link AgentCheckpoint#restore} 用 {@code objectMapper.readValue} 还原
 * {@link ReactAgentState}，其中 {@code steps: List<ReactStep>}、
 * {@link ReactStep.Suspend#reason} 以及 {@link ReactStep.Resume#payload} 都是
 * sealed interface。Jackson 默认对 sealed interface 不写类型信息，恢复时
 * 抛 {@code Cannot construct instance of ReactStep (abstract types ...)}。
 * 已通过 {@code @JsonTypeInfo + @JsonSubTypes} 修复，此测试锁定回归。</p>
 *
 * <p>测试覆盖三层：<ol>
 *   <li>各 ReactStep 子类都能 JSON roundtrip</li>
 *   <li>SuspendReason / ResumePayload 所有子类能独立 roundtrip（嵌套防御）</li>
 *   <li>完整 AgentCheckpoint.from → restore 走通，steps 等值</li>
 * </ol></p>
 *
 * <p>ObjectMapper 用 {@code findAndAddModules()} 贴近 Spring Boot 默认行为
 * （自动注册 JavaTimeModule 等），避免 Instant/Duration 序列化差异干扰断言。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("Agent Checkpoint 多态序列化回归")
class AgentCheckpoint_多态序列化测试 {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // findAndAddModules 注册 JavaTimeModule 等；FAIL_ON_UNKNOWN_PROPERTIES=false
        // 贴近 Spring Boot 应用默认 ObjectMapper 行为（生产 checkpoint 序列化路径的实际配置）。
        objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Test
    void SuspendReason所有子类型以父接口静态类型roundtrip() throws Exception {
        List<SuspendReason> reasons = List.of(
                new SuspendReason.WorkflowWait("exec-1", "wf-1", "测试工作流"),
                new SuspendReason.UserConfirmation("shell.exec", "{\"cmd\":\"rm\"}", "HIGH", "conf-1"),
                new SuspendReason.RemoteDelegation("task-1", "http://remote/agent", "去查询订单"),
                new SuspendReason.ScheduledWakeup(Instant.parse("2026-04-24T20:00:00Z"), "等待 cron"),
                new SuspendReason.ExternalDataWait("crawler-1", "等爬虫完成")
        );

        // 写出时必须告诉 Jackson "按 SuspendReason 父接口静态类型"，否则运行时只看到具体子类、
        // 不会应用父接口上的 @JsonTypeInfo —— 生产路径(ReactStep.Suspend.reason 字段)自带静态类型。
        for (SuspendReason original : reasons) {
            String json = objectMapper.writerFor(SuspendReason.class).writeValueAsString(original);
            SuspendReason restored = objectMapper.readValue(json, SuspendReason.class);
            assertThat(restored)
                    .as("SuspendReason 子类型 %s 应完整还原", original.getClass().getSimpleName())
                    .isEqualTo(original);
        }
    }

    @Test
    void ResumePayload所有子类型以父接口静态类型roundtrip() throws Exception {
        List<ResumePayload> payloads = List.of(
                new ResumePayload.WorkflowResult("exec-1", "SUCCESS", "{\"data\":1}"),
                new ResumePayload.UserDecision("conf-1", true, "同意"),
                new ResumePayload.UserDecision("conf-2", false, null),
                new ResumePayload.RemoteResult("task-1", "{\"result\":\"ok\"}"),
                new ResumePayload.WakeupSignal(Instant.parse("2026-04-24T20:00:01Z")),
                new ResumePayload.DataReady("crawler-1", "s3://bucket/data.json")
        );

        for (ResumePayload original : payloads) {
            String json = objectMapper.writerFor(ResumePayload.class).writeValueAsString(original);
            ResumePayload restored = objectMapper.readValue(json, ResumePayload.class);
            assertThat(restored)
                    .as("ResumePayload 子类型 %s 应完整还原", original.getClass().getSimpleName())
                    .isEqualTo(original);
        }
    }

    @Test
    void AgentCheckpoint_from_restore完整往返保留所有steps() {
        AgentRequest request = new AgentRequest("原始目标", "session-ckpt-1", "web");
        ReactAgentState base = ReactAgentState.init(request, testBudget());
        ReactAgentState state = base.toBuilder()
                .steps(allReactStepSamples())
                .stepCount(allReactStepSamples().size())
                .build();

        AgentCheckpoint checkpoint = AgentCheckpoint.from(state, "task-fingerprint", objectMapper);

        // 模拟新 turn 触发 resume
        AgentRequest resumeRequest = new AgentRequest("新 turn 触发恢复", "session-ckpt-1", "web");
        ReactAgentState restored = checkpoint.restore(objectMapper, resumeRequest);

        // restore 会重置 traceId / goal / source 等字段（见 AgentCheckpoint.restore），
        // 但 steps / stepCount / shortTermMemory 必须按原样还原
        assertThat(restored.steps())
                .as("恢复后的 steps 应与原 state 一一等值（多态还原正确）")
                .containsExactlyElementsOf(state.steps());
        assertThat(restored.stepCount()).isEqualTo(state.stepCount());
    }

    @Test
    void 包含Suspend_Resume的state能完整roundtrip() {
        AgentRequest request = new AgentRequest("挂起恢复测试", "session-sr-1", "web");
        ReactAgentState base = ReactAgentState.init(request, testBudget());

        List<ReactStep> steps = new ArrayList<>();
        steps.add(new ReactStep.Thought("判断需要工作流"));
        steps.add(new ReactStep.Suspend(
                new SuspendReason.WorkflowWait("exec-xyz", "wf-approval", "审批工作流"),
                Instant.parse("2026-04-24T10:00:00Z"),
                1
        ));
        steps.add(new ReactStep.Resume(
                new ResumePayload.WorkflowResult("exec-xyz", "SUCCESS", "{\"approved\":true}"),
                Instant.parse("2026-04-24T10:05:00Z"),
                Duration.ofMinutes(5)
        ));
        steps.add(new ReactStep.Answer("已完成"));

        ReactAgentState state = base.toBuilder().steps(steps).stepCount(steps.size()).build();

        AgentCheckpoint checkpoint = AgentCheckpoint.from(state, "fp", objectMapper);
        ReactAgentState restored = checkpoint.restore(objectMapper, request);

        assertThat(restored.steps()).containsExactlyElementsOf(steps);
    }

    /** 测试用预算 —— 给定足够大的上限，不影响序列化逻辑验证。 */
    private Budget testBudget() {
        return new Budget(100_000, 0, 0, 20, 0, Duration.ofMinutes(10), Duration.ZERO);
    }

    /** 构造一份覆盖全部 ReactStep 子类型的样本序列。 */
    private List<ReactStep> allReactStepSamples() {
        return List.of(
                new ReactStep.Progress("正在查询资料..."),
                new ReactStep.Thought("需要先搜索知识库"),
                new ReactStep.ToolCall(
                        "knowledge.search",
                        "知识库搜索",
                        "{\"query\":\"test\"}",
                        123L,
                        "call-1"
                ),
                new ReactStep.Observation(
                        "knowledge.search",
                        "知识库搜索",
                        true,
                        "{\"hits\":3}",
                        45,
                        "call-1"
                ),
                new ReactStep.Suspend(
                        new SuspendReason.UserConfirmation("shell.exec", "{\"cmd\":\"ls\"}", "LOW", "conf-42"),
                        Instant.parse("2026-04-24T09:00:00Z"),
                        3
                ),
                new ReactStep.Resume(
                        new ResumePayload.UserDecision("conf-42", true, "放行"),
                        Instant.parse("2026-04-24T09:01:00Z"),
                        Duration.ofSeconds(60)
                ),
                new ReactStep.Reflect("检查失败是否需要换策略", ReactStep.ReflectTrigger.TOOL_FAILURE),
                new ReactStep.Answer("最终回答")
        );
    }
}
