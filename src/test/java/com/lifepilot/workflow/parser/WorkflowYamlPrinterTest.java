package com.lifepilot.workflow.parser;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.workflow.model.ErrorStrategy;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowInputParam;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.*;
import com.lifepilot.workflow.model.WorkflowTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowYamlPrinter 单元测试。
 *
 * <p>验证 print 方法能正确将 WorkflowDefinition 序列化为 YAML，
 * 且输出可被 WorkflowYamlParser 重新解析为等价定义（round-trip）。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowYamlPrinterTest {

    private WorkflowYamlPrinter printer;
    private WorkflowYamlParser parser;

    @BeforeEach
    void setUp() {
        printer = new WorkflowYamlPrinter();
        parser = new WorkflowYamlParser();
    }

    @Test
    void 基本工作流_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("test-wf").name("测试工作流")
                .description("一个简单的测试工作流").version("1.0").enabled(true)
                .steps(List.of(new NoopStep("step1", "空操作", List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        assertEquals(definition.id(), parsed.id());
        assertEquals(definition.name(), parsed.name());
        assertEquals(definition.description(), parsed.description());
        assertEquals(definition.version(), parsed.version());
    }

    @Test
    void SkillStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("skill-wf").name("Skill 工作流")
                .steps(List.of(
                        new SkillStep("s1", "调用技能", "todo.list",
                                Map.of("filter", "today", "userId", "${inputs.userId}"),
                                List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (SkillStep) parsed.steps().getFirst();
        assertEquals("todo.list", step.skillId());
        assertEquals("today", step.params().get("filter"));
        assertEquals("${inputs.userId}", step.params().get("userId"));
    }

    @Test
    void ToolStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("tool-wf").name("Tool 工作流")
                .steps(List.of(
                        new ToolStep("t1", "发送通知", "notification.send",
                                Map.of("message", "hello"), List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (ToolStep) parsed.steps().getFirst();
        assertEquals("notification.send", step.toolId());
        assertEquals("hello", step.params().get("message"));
    }

    @Test
    void LlmStep_promptTemplate映射为prompt() {
        var definition = WorkflowDefinition.builder()
                .id("llm-wf").name("LLM 工作流")
                .steps(List.of(
                        new LlmStep("l1", "生成内容", "chat", ProviderCapability.CHAT,
                                "请总结以下内容", "{\"type\":\"object\"}",
                                null, null, List.of(), List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        assertTrue(yaml.contains("prompt:"));
        assertFalse(yaml.contains("promptTemplate"));

        var result = parser.parse(yaml);
        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (LlmStep) parsed.steps().getFirst();
        assertEquals("chat", step.scene());
        assertEquals(ProviderCapability.CHAT, step.capability());
        assertEquals("请总结以下内容", step.promptTemplate());
        assertEquals("{\"type\":\"object\"}", step.outputSchema());
    }

    @Test
    void VisionLlmStep_mediaRoundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("vision-wf").name("Vision Workflow")
                .steps(List.of(
                        new LlmStep("vision-1", "Image Review", "content_review", ProviderCapability.VISION,
                                "Describe the image", "{\"type\":\"object\"}",
                                null, "provider-1",
                                List.of(new MediaRef("${inputs.image}", "image/png", "upload.png")),
                                List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        assertTrue(yaml.contains("media:"));

        var result = parser.parse(yaml);
        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (LlmStep) parsed.steps().getFirst();
        assertEquals(ProviderCapability.VISION, step.capability());
        assertEquals(1, step.media().size());
        assertEquals("${inputs.image}", step.media().getFirst().source());
        assertEquals("provider-1", step.preferredProviderId());
    }

    @Test
    void ConditionStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("cond-wf").name("条件工作流")
                .steps(List.of(
                        new ConditionStep("c1", "检查条件", "${steps.s1.output.count} > 0",
                                List.of(new NoopStep("then1", "有数据", List.of(), null)),
                                List.of(new NoopStep("else1", "无数据", List.of(), null)),
                                List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (ConditionStep) parsed.steps().getFirst();
        assertEquals("${steps.s1.output.count} > 0", step.condition());
        assertEquals(1, step.thenSteps().size());
        assertEquals(1, step.elseSteps().size());
        assertEquals("then1", step.thenSteps().getFirst().id());
        assertEquals("else1", step.elseSteps().getFirst().id());
    }

    @Test
    void LoopStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("loop-wf").name("循环工作流")
                .steps(List.of(
                        new LoopStep("lp1", "遍历任务", "${steps.fetch.output.items}", "item",
                                List.of(new NoopStep("body1", "处理", List.of(), null)),
                                List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (LoopStep) parsed.steps().getFirst();
        assertEquals("${steps.fetch.output.items}", step.items());
        assertEquals("item", step.loopVar());
        assertEquals(1, step.body().size());
    }

    @Test
    void ParallelStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("par-wf").name("并行工作流")
                .steps(List.of(
                        new ParallelStep("p1", "并行执行",
                                List.of(
                                        List.of(new NoopStep("b1s1", "分支1步骤1", List.of(), null)),
                                        List.of(new NoopStep("b2s1", "分支2步骤1", List.of(), null))
                                ), List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (ParallelStep) parsed.steps().getFirst();
        assertEquals(2, step.branches().size());
        assertEquals("b1s1", step.branches().get(0).getFirst().id());
        assertEquals("b2s1", step.branches().get(1).getFirst().id());
    }

    @Test
    void SubWorkflowStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("sub-wf").name("子工作流")
                .steps(List.of(
                        new SubWorkflowStep("sw1", "调用子流程", "child-wf",
                                Map.of("key", "value"), List.of(), null)
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (SubWorkflowStep) parsed.steps().getFirst();
        assertEquals("child-wf", step.workflowId());
        assertEquals("value", step.params().get("key"));
    }

    @Test
    void WaitStep_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("wait-wf").name("等待工作流")
                .steps(List.of(new WaitStep("w1", "等待", 60, List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var step = (WaitStep) parsed.steps().getFirst();
        assertEquals(60, step.durationSeconds());
    }

    @Test
    void 触发器_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("trigger-wf").name("触发器工作流")
                .triggers(List.of(
                        new CronTrigger("0 21 * * *"),
                        new EventTrigger("task.completed"),
                        new ManualTrigger()
                ))
                .steps(List.of(new NoopStep("s1", "步骤", List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        assertEquals(3, parsed.triggers().size());
        assertInstanceOf(CronTrigger.class, parsed.triggers().get(0));
        assertEquals("0 21 * * *", ((CronTrigger) parsed.triggers().get(0)).cron());
        assertInstanceOf(EventTrigger.class, parsed.triggers().get(1));
        assertEquals("task.completed", ((EventTrigger) parsed.triggers().get(1)).eventType());
        assertInstanceOf(ManualTrigger.class, parsed.triggers().get(2));
    }

    @Test
    void 输入参数_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("input-wf").name("输入工作流")
                .inputs(Map.of(
                        "userId", new WorkflowInputParam("userId", "string", true, null, "用户ID"),
                        "count", new WorkflowInputParam("count", "number", false, 10, "数量")
                ))
                .steps(List.of(new NoopStep("s1", "步骤", List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        assertEquals(2, parsed.inputs().size());
        var userIdParam = parsed.inputs().get("userId");
        assertNotNull(userIdParam);
        assertEquals("string", userIdParam.type());
        assertTrue(userIdParam.required());
        assertEquals("用户ID", userIdParam.description());
    }

    @Test
    void 错误策略Retry_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("retry-wf").name("重试工作流")
                .steps(List.of(
                        new NoopStep("s1", "步骤", List.of(),
                                new ErrorStrategy.Retry(5, 1000, 10000))
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var strategy = (ErrorStrategy.Retry) parsed.steps().getFirst().errorStrategy();
        assertEquals(5, strategy.maxAttempts());
        assertEquals(1000, strategy.initialDelayMs());
        assertEquals(10000, strategy.maxDelayMs());
    }

    @Test
    void 错误策略Skip_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("skip-wf").name("跳过工作流")
                .steps(List.of(
                        new NoopStep("s1", "步骤", List.of(),
                                new ErrorStrategy.Skip("非关键步骤"))
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var strategy = (ErrorStrategy.Skip) parsed.steps().getFirst().errorStrategy();
        assertEquals("非关键步骤", strategy.reason());
    }

    @Test
    void 错误策略Fail_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("fail-wf").name("失败工作流")
                .steps(List.of(
                        new NoopStep("s1", "步骤", List.of(), new ErrorStrategy.Fail())
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        assertInstanceOf(ErrorStrategy.Fail.class, parsed.steps().getFirst().errorStrategy());
    }

    @Test
    void 错误策略Compensate_递归序列化() {
        var compStep = new NoopStep("comp1", "补偿操作", List.of(), null);
        var definition = WorkflowDefinition.builder()
                .id("comp-wf").name("补偿工作流")
                .steps(List.of(
                        new ToolStep("s1", "关键操作", "api.call",
                                Map.of("url", "https://example.com"),
                                List.of(), new ErrorStrategy.Compensate(compStep))
                )).build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        var strategy = (ErrorStrategy.Compensate) parsed.steps().getFirst().errorStrategy();
        assertEquals("comp1", strategy.compensationStep().id());
        assertEquals("补偿操作", strategy.compensationStep().name());
    }

    @Test
    void 省略空可选字段() {
        var definition = WorkflowDefinition.builder()
                .id("minimal-wf").name("最小工作流").enabled(true)
                .steps(List.of(new NoopStep("s1", "步骤", List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        assertFalse(yaml.contains("description:"));
        assertFalse(yaml.contains("version:"));
        assertFalse(yaml.contains("triggers:"));
        assertFalse(yaml.contains("inputs:"));
        assertFalse(yaml.contains("metadata:"));
        assertFalse(yaml.contains("errorStrategy:"));
        assertFalse(yaml.contains("enabled:"));
    }

    @Test
    void 元数据_roundTrip() {
        var definition = WorkflowDefinition.builder()
                .id("meta-wf").name("元数据工作流")
                .metadata(Map.of("author", "test", "category", "daily"))
                .steps(List.of(new NoopStep("s1", "步骤", List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        var result = parser.parse(yaml);

        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        assertEquals("test", parsed.metadata().get("author"));
        assertEquals("daily", parsed.metadata().get("category"));
    }

    @Test
    void disabled工作流_输出enabled字段() {
        var definition = WorkflowDefinition.builder()
                .id("disabled-wf").name("禁用工作流").enabled(false)
                .steps(List.of(new NoopStep("s1", "步骤", List.of(), null)))
                .build();

        var yaml = printer.print(definition);
        assertTrue(yaml.contains("enabled: false"));

        var result = parser.parse(yaml);
        assertInstanceOf(Result.Ok.class, result);
        var parsed = ((Result.Ok<WorkflowDefinition, List<String>>) result).value();
        assertFalse(parsed.enabled());
    }
}
