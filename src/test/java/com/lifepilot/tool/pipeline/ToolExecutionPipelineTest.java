package com.lifepilot.tool.pipeline;

import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionDecisionEntry;
import com.lifepilot.permission.model.PermissionDecisionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.permission.service.PermissionRequestFactory;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ToolExecutionPipeline 单元测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class ToolExecutionPipelineTest {

    private DynamicToolRegistry registry;
    private GuardrailEngine guardrailEngine;
    private IdempotencyManager idempotencyManager;
    private PermissionService permissionService;
    private PermissionRequestFactory permissionRequestFactory;
    private PermissionApprovalService permissionApprovalService;
    private ToolExecutionPipeline pipeline;

    @BeforeEach
    void setUp() {
        guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        permissionService = mock(PermissionService.class);
        permissionRequestFactory = mock(PermissionRequestFactory.class);
        permissionApprovalService = mock(PermissionApprovalService.class);
        when(permissionRequestFactory.create(any(), any(), any())).thenReturn(defaultPermissionRequest());
        when(permissionService.evaluateAndRecord(any())).thenReturn(passedDecision());

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        registry = new DynamicToolRegistry(publisher);
        idempotencyManager = new IdempotencyManager();
        pipeline = new ToolExecutionPipeline(
                registry,
                guardrailEngine,
                idempotencyManager,
                permissionService,
                permissionRequestFactory,
                permissionApprovalService,
                100,
                2.0,
                1000
        );
    }

    @Test
    void 工具未找到_返回错误() {
        ToolResult result = pipeline.execute("nonexistent", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("工具未找到"));
    }

    @Test
    void 参数校验失败_返回错误() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        ));
        registerTool("test.tool", schema, input -> ToolResult.success(Map.of()));

        ToolResult result = pipeline.execute("test.tool", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("缺少必需参数"));
        verifyNoInteractions(permissionService);
    }

    @Test
    void 权限阻断_返回错误() {
        registerTool("test.blocked", JsonSchema.empty(), input -> ToolResult.success(Map.of()));
        when(permissionService.evaluateAndRecord(any())).thenReturn(blockedDecision());

        ToolResult result = pipeline.execute("test.blocked", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("权限阻断"));
        verifyNoInteractions(permissionApprovalService);
        verify(guardrailEngine, never()).checkToolCall(any(), any());
    }

    @Test
    void 需要授权但未批准_返回错误() {
        registerTool("test.approval", JsonSchema.empty(), input -> ToolResult.success(Map.of()));
        when(permissionService.evaluateAndRecord(any())).thenReturn(needsApprovalDecision());
        when(permissionApprovalService.requestApproval(any(), any(), any())).thenReturn(null);

        ToolResult result = pipeline.execute("test.approval", Map.of(), "trace-1", null, "stream-1");
        assertFalse(result.ok());
        assertTrue(result.error().contains("未获得执行授权"));
        verify(permissionApprovalService).requestApproval(any(), any(), argThat(ctx ->
                ctx != null && "stream-1".equals(ctx.get("streamId"))));
        verify(guardrailEngine, never()).checkToolCall(any(), any());
    }

    @Test
    void 护栏拦截_返回错误() {
        registerTool("test.guardrail", JsonSchema.empty(), input -> ToolResult.success(Map.of()));
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Blocked("test-policy", "工具被阻止", RiskLevel.HIGH));

        ToolResult result = pipeline.execute("test.guardrail", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("护栏拦截"));
    }

    @Test
    void 正常执行_返回成功() {
        registerTool("test.echo", JsonSchema.empty(),
                input -> ToolResult.success(Map.of("msg", "hello")));

        ToolResult result = pipeline.execute("test.echo", Map.of(), "trace-1", null);
        assertTrue(result.ok());
        assertEquals("hello", result.getData("msg"));
    }

    @Test
    void 幂等缓存命中_不重复执行() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);
        registerToolIdempotent("test.idem", JsonSchema.empty(), input -> {
            counter.incrementAndGet();
            return ToolResult.success(Map.of("count", counter.get()));
        });

        ToolResult r1 = pipeline.execute("test.idem", Map.of(), "t1", "key-1");
        assertTrue(r1.ok());
        assertEquals(1, counter.get());

        ToolResult r2 = pipeline.execute("test.idem", Map.of(), "t2", "key-1");
        assertTrue(r2.ok());
        assertTrue(r2.meta().cacheHit());
        assertEquals(1, counter.get());
    }

    @Test
    void 执行超时_返回错误() {
        BuiltinTool tool = BuiltinTool.builder()
                .id("test.slow").name("Slow").description("慢工具")
                .inputSchema(JsonSchema.empty()).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(false)
                .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic())
                .budget(ToolBudget.of(Duration.ofMillis(100), 0, Integer.MAX_VALUE))
                .tags(List.of())
                .executor(input -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.success(Map.of());
                })
                .build();
        registry.registerBuiltinTool(tool);

        ToolResult result = pipeline.execute("test.slow", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("超时"));
    }

    private void registerTool(String id, JsonSchema inputSchema,
                              com.lifepilot.tool.ToolExecutor executor) {
        BuiltinTool tool = BuiltinTool.builder()
                .id(id).name(id).description("测试工具")
                .inputSchema(inputSchema).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(false)
                .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(executor).build();
        registry.registerBuiltinTool(tool);
    }

    private void registerToolIdempotent(String id, JsonSchema inputSchema,
                                        com.lifepilot.tool.ToolExecutor executor) {
        BuiltinTool tool = BuiltinTool.builder()
                .id(id).name(id).description("测试幂等工具")
                .inputSchema(inputSchema).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(true)
                .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(executor).build();
        registry.registerBuiltinTool(tool);
    }

    private PermissionRequest defaultPermissionRequest() {
        return new PermissionRequest(
                "test.tool",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.EMPTY,
                "session-1",
                null,
                null,
                "user-1",
                null,
                "trace-1"
        );
    }

    private PermissionDecisionEntry passedDecision() {
        return new PermissionDecisionEntry(
                "decision-1",
                "session-1",
                "trace-1",
                null,
                null,
                "user-1",
                "test.tool",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.EMPTY,
                PermissionDecisionType.PASSED,
                null,
                null,
                null,
                "允许执行",
                Instant.now()
        );
    }

    private PermissionDecisionEntry blockedDecision() {
        return new PermissionDecisionEntry(
                "decision-2",
                "session-1",
                "trace-1",
                null,
                null,
                "user-1",
                "test.tool",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.HIGH,
                "web",
                ExecutionGrantScope.EMPTY,
                PermissionDecisionType.BLOCKED,
                null,
                null,
                null,
                "禁止执行",
                Instant.now()
        );
    }

    private PermissionDecisionEntry needsApprovalDecision() {
        return new PermissionDecisionEntry(
                "decision-3",
                "session-1",
                "trace-1",
                null,
                null,
                "user-1",
                "test.tool",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.HIGH,
                "web",
                ExecutionGrantScope.EMPTY,
                PermissionDecisionType.NEEDS_APPROVAL,
                null,
                null,
                null,
                "需要授权",
                Instant.now()
        );
    }
}
