package com.lifepilot.workflow.parser;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.workflow.model.ErrorStrategy;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep.ConditionStep;
import com.lifepilot.workflow.model.WorkflowStep.LlmStep;
import com.lifepilot.workflow.model.WorkflowStep.LoopStep;
import com.lifepilot.workflow.model.WorkflowStep.NoopStep;
import com.lifepilot.workflow.model.WorkflowStep.ParallelStep;
import com.lifepilot.workflow.model.WorkflowStep.SkillStep;
import com.lifepilot.workflow.model.WorkflowStep.SubWorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.ToolStep;
import com.lifepilot.workflow.model.WorkflowStep.WaitStep;
import com.lifepilot.workflow.model.WorkflowTrigger.CronTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.EventTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.ManualTrigger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowYamlParser 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowYamlParserTest {

    private WorkflowYamlParser parser;

    @BeforeEach
    void setUp() {
        parser = new WorkflowYamlParser();
    }

    // ==================== 完整 YAML 解析 ====================

    @Test
    void 解析完整工作流定义_包含所有字段() {
        String yaml = """
                id: daily-review
                name: 每日回顾
                description: 每天晚上自动生成当日任务回顾
                version: "1.0"
                triggers:
                  - type: cron
                    cron: "0 21 * * *"
                  - type: event
                    eventType: "task.completed"
                  - type: manual
                inputs:
                  userId:
                    type: string
                    required: true
                steps:
                  - id: fetch-tasks
                    name: 获取今日任务
                    type: skill
                    skillId: todo.list
                    params:
                      filter: "today"
                metadata:
                  author: test
                """;

        var result = parser.parse(yaml);
        assertInstanceOf(Result.Ok.class, result);
        var def = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();

        assertEquals("daily-review", def.id());
        assertEquals("每日回顾", def.name());
        assertEquals("每天晚上自动生成当日任务回顾", def.description());
        assertEquals("1.0", def.version());
        assertTrue(def.enabled());
        assertEquals(3, def.triggers().size());
        assertInstanceOf(CronTrigger.class, def.triggers().get(0));
        assertInstanceOf(EventTrigger.class, def.triggers().get(1));
        assertInstanceOf(ManualTrigger.class, def.triggers().get(2));
        assertEquals(1, def.inputs().size());
        assertTrue(def.inputs().get("userId").required());
        assertEquals(1, def.steps().size());
        assertInstanceOf(SkillStep.class, def.steps().getFirst());
        assertEquals("test", def.metadata().get("author"));
    }

    // ==================== 9 种步骤类型 ====================

    @Test
    void 解析SkillStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 技能步骤
                    type: skill
                    skillId: todo.list
                    params:
                      filter: today
                """;
        var def = parseOk(yaml);
        var step = (SkillStep) def.steps().getFirst();
        assertEquals("s1", step.id());
        assertEquals("todo.list", step.skillId());
        assertEquals("today", step.params().get("filter"));
    }

    @Test
    void 解析ToolStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 工具步骤
                    type: tool
                    toolId: notification.send
                    params:
                      message: hello
                """;
        var def = parseOk(yaml);
        var step = (ToolStep) def.steps().getFirst();
        assertEquals("notification.send", step.toolId());
        assertEquals("hello", step.params().get("message"));
    }

    @Test
    void 解析LlmStep_prompt映射到promptTemplate() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: LLM步骤
                    type: llm
                    scene: chat
                    capability: CHAT
                    prompt: "请生成回顾"
                    outputSchema: '{"type":"object"}'
                """;
        var def = parseOk(yaml);
        var step = (LlmStep) def.steps().getFirst();
        assertEquals("chat", step.scene());
        assertEquals("请生成回顾", step.promptTemplate());
        assertEquals("{\"type\":\"object\"}", step.outputSchema());
    }

    @Test
    void 解析VisionLlmStep_media() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: vision
                    type: llm
                    scene: content_review
                    capability: VISION
                    prompt: "describe"
                    media:
                      - source: "${inputs.image}"
                        mimeType: image/png
                        fileName: upload.png
                """;
        var def = parseOk(yaml);
        var step = (LlmStep) def.steps().getFirst();
        assertEquals(ProviderCapability.VISION, step.capability());
        assertEquals(1, step.media().size());
        assertEquals("${inputs.image}", step.media().getFirst().source());
    }

    @Test
    void 解析ConditionStep_包含then和else分支() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: check
                    name: 条件检查
                    type: condition
                    condition: "${steps.s1.output.count} > 0"
                    then:
                      - id: yes-branch
                        name: 有数据
                        type: noop
                    else:
                      - id: no-branch
                        name: 无数据
                        type: noop
                """;
        var def = parseOk(yaml);
        var step = (ConditionStep) def.steps().getFirst();
        assertEquals("${steps.s1.output.count} > 0", step.condition());
        assertEquals(1, step.thenSteps().size());
        assertEquals(1, step.elseSteps().size());
        assertInstanceOf(NoopStep.class, step.thenSteps().getFirst());
    }

    @Test
    void 解析LoopStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: loop1
                    name: 循环
                    type: loop
                    items: "${steps.fetch.output.items}"
                    loopVar: item
                    body:
                      - id: process
                        name: 处理
                        type: noop
                """;
        var def = parseOk(yaml);
        var step = (LoopStep) def.steps().getFirst();
        assertEquals("${steps.fetch.output.items}", step.items());
        assertEquals("item", step.loopVar());
        assertEquals(1, step.body().size());
    }

    @Test
    void 解析ParallelStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: par1
                    name: 并行
                    type: parallel
                    branches:
                      - - id: b1s1
                          name: 分支1步骤1
                          type: noop
                      - - id: b2s1
                          name: 分支2步骤1
                          type: noop
                """;
        var def = parseOk(yaml);
        var step = (ParallelStep) def.steps().getFirst();
        assertEquals(2, step.branches().size());
        assertEquals(1, step.branches().get(0).size());
        assertEquals(1, step.branches().get(1).size());
    }

    @Test
    void 解析SubWorkflowStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: sub1
                    name: 子工作流
                    type: sub-workflow
                    workflowId: other-workflow
                    params:
                      key: value
                """;
        var def = parseOk(yaml);
        var step = (SubWorkflowStep) def.steps().getFirst();
        assertEquals("other-workflow", step.workflowId());
        assertEquals("value", step.params().get("key"));
    }

    @Test
    void 解析NoopStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: noop1
                    name: 空操作
                    type: noop
                """;
        var def = parseOk(yaml);
        assertInstanceOf(NoopStep.class, def.steps().getFirst());
    }

    @Test
    void 解析WaitStep() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: wait1
                    name: 等待
                    type: wait
                    durationSeconds: 60
                """;
        var def = parseOk(yaml);
        var step = (WaitStep) def.steps().getFirst();
        assertEquals(60, step.durationSeconds());
    }

    // ==================== 错误策略解析 ====================

    @Test
    void 解析ErrorStrategy_Retry() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                    errorStrategy:
                      type: retry
                      maxAttempts: 5
                      initialDelayMs: 1000
                      maxDelayMs: 10000
                """;
        var def = parseOk(yaml);
        var strategy = (ErrorStrategy.Retry) def.steps().getFirst().errorStrategy();
        assertEquals(5, strategy.maxAttempts());
        assertEquals(1000, strategy.initialDelayMs());
        assertEquals(10000, strategy.maxDelayMs());
    }

    @Test
    void 解析ErrorStrategy_Skip() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                    errorStrategy:
                      type: skip
                      reason: "不影响主流程"
                """;
        var def = parseOk(yaml);
        var strategy = (ErrorStrategy.Skip) def.steps().getFirst().errorStrategy();
        assertEquals("不影响主流程", strategy.reason());
    }

    @Test
    void 解析ErrorStrategy_Fail() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                    errorStrategy:
                      type: fail
                """;
        var def = parseOk(yaml);
        assertInstanceOf(ErrorStrategy.Fail.class, def.steps().getFirst().errorStrategy());
    }

    @Test
    void 解析ErrorStrategy_Compensate_递归解析补偿步骤() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: tool
                    toolId: api.call
                    errorStrategy:
                      type: compensate
                      compensationStep:
                        id: rollback
                        name: 回滚
                        type: tool
                        toolId: api.rollback
                """;
        var def = parseOk(yaml);
        var strategy = (ErrorStrategy.Compensate) def.steps().getFirst().errorStrategy();
        assertInstanceOf(ToolStep.class, strategy.compensationStep());
        assertEquals("rollback", strategy.compensationStep().id());
    }

    // ==================== 校验错误 ====================

    @Test
    void 缺失id_返回错误() {
        String yaml = """
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                """;
        var errors = parseErr(yaml);
        assertTrue(errors.stream().anyMatch(e -> e.contains("id")));
    }

    @Test
    void 缺失name_返回错误() {
        String yaml = """
                id: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                """;
        var errors = parseErr(yaml);
        assertTrue(errors.stream().anyMatch(e -> e.contains("name")));
    }

    @Test
    void 缺失steps_返回错误() {
        String yaml = """
                id: test
                name: test
                """;
        var errors = parseErr(yaml);
        assertTrue(errors.stream().anyMatch(e -> e.contains("steps")));
    }

    @Test
    void 未知步骤类型_返回错误() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: unknown-type
                """;
        var errors = parseErr(yaml);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown-type")));
    }

    @Test
    void 重复步骤ID_返回错误() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: dup
                    name: 步骤1
                    type: noop
                  - id: dup
                    name: 步骤2
                    type: noop
                """;
        var errors = parseErr(yaml);
        assertTrue(errors.stream().anyMatch(e -> e.contains("重复") && e.contains("dup")));
    }

    @Test
    void 嵌套步骤中重复ID_返回错误() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: outer
                    name: 条件
                    type: condition
                    condition: "true"
                    then:
                      - id: outer
                        name: 内部重复
                        type: noop
                """;
        var errors = parseErr(yaml);
        assertTrue(errors.stream().anyMatch(e -> e.contains("重复") && e.contains("outer")));
    }

    // ==================== 默认值 ====================

    @Test
    void enabled默认为true() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                """;
        var def = parseOk(yaml);
        assertTrue(def.enabled());
    }

    @Test
    void enabled显式设为false() {
        String yaml = """
                id: test
                name: test
                enabled: false
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                """;
        var def = parseOk(yaml);
        assertFalse(def.enabled());
    }

    @Test
    void 可选字段缺失时使用空集合() {
        String yaml = """
                id: test
                name: test
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                """;
        var def = parseOk(yaml);
        assertTrue(def.triggers().isEmpty());
        assertTrue(def.inputs().isEmpty());
        assertTrue(def.metadata().isEmpty());
    }

    @Test
    void 无效YAML语法_返回错误() {
        String yaml = "{{invalid yaml";
        var result = parser.parse(yaml);
        assertInstanceOf(Result.Err.class, result);
    }

    // ==================== 工具方法 ====================

    private WorkflowDefinition parseOk(String yaml) {
        var result = parser.parse(yaml);
        assertInstanceOf(Result.Ok.class, result, "期望解析成功但失败: " + result);
        return ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
    }

    private List<String> parseErr(String yaml) {
        var result = parser.parse(yaml);
        assertInstanceOf(Result.Err.class, result, "期望解析失败但成功");
        return ((Result.Err<WorkflowDefinition, List<String>>) result).error();
    }
}
