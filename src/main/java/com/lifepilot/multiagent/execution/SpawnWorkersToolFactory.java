package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * spawn_workers 工具工厂 — 创建并行 Worker 派发工具。
 *
 * <p>Worker 是主 Agent 的通用分身，继承完整工具集（排除 spawn_workers 自身防止递归），
 * 通过虚拟线程并行执行独立 ReAct 循环，结果聚合后返回主 Agent。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public class SpawnWorkersToolFactory {

    private static final Logger log = LoggerFactory.getLogger(SpawnWorkersToolFactory.class);

    /** 工具 ID。 */
    public static final String TOOL_ID = "spawn_workers";

    /** 默认 Worker System Prompt。 */
    private static final String DEFAULT_WORKER_SYSTEM_PROMPT = """
            你是一个专注执行单一任务的 Worker。你会收到一个明确的任务描述，请高效完成并返回结果。

            规则：
            - 不要偏离任务范围，不要主动发起新的子任务
            - 充分利用可用工具完成任务
            - 完成后直接输出结果，不需要额外的总结或寒暄
            - 如果任务无法完成，明确说明原因
            """;

    private final AgentOrchestrator agentOrchestrator;
    private final DynamicToolRegistry toolRegistry;
    private final MultiAgentProperties config;

    public SpawnWorkersToolFactory(AgentOrchestrator agentOrchestrator,
                                   DynamicToolRegistry toolRegistry,
                                   MultiAgentProperties config) {
        this.agentOrchestrator = agentOrchestrator;
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /**
     * 创建 spawn_workers BuiltinTool 实例。
     *
     * @return 并行 Worker 派发工具
     */
    public BuiltinTool createSpawnWorkersTool() {
        JsonSchema inputSchema = JsonSchema.of(Map.of(
                "type", "object",
                "properties", Map.of(
                        "tasks", Map.of(
                                "type", "array",
                                "description", "Worker 任务列表，每个元素描述一个独立子任务",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "task", Map.of("type", "string",
                                                        "description", "任务描述"),
                                                "context", Map.of("type", "string",
                                                        "description", "附加上下文信息"),
                                                "budget", Map.of("type", "object",
                                                        "description", "自定义预算覆盖",
                                                        "properties", Map.of(
                                                                "max_tokens", Map.of("type", "integer"),
                                                                "max_steps", Map.of("type", "integer"),
                                                                "timeout_seconds", Map.of("type", "integer")
                                                        ))
                                        ),
                                        "required", List.of("task")
                                )
                        )
                ),
                "required", List.of("tasks")
        ));

        return BuiltinTool.builder()
                .id(TOOL_ID)
                .name("并行 Worker 派发")
                .description("将任务拆解为多个独立子任务，并行派发给 Worker 执行。适用于可并行的调研、对比、批量处理等场景。")
                .inputSchema(inputSchema)
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(List.of("multi-agent", "parallel", "worker"))
                .executor(this::executeSpawnWorkers)
                .build();
    }

    /**
     * spawn_workers 工具执行逻辑。
     */
    @SuppressWarnings("unchecked")
    private ToolResult executeSpawnWorkers(ToolInput input) {
        // 1. 解析任务列表
        List<Map<String, Object>> tasks;
        try {
            tasks = input.getParam("tasks", List.class);
        } catch (Exception e) {
            return ToolResult.error("tasks 参数解析失败: " + e.getMessage());
        }

        if (tasks == null || tasks.isEmpty()) {
            return ToolResult.error("tasks 不能为空");
        }

        // 2. 校验 Worker 数量
        int maxWorkers = config.getParallelWorker().getMaxParallelWorkers();
        if (tasks.size() > maxWorkers) {
            return ToolResult.error("Worker 数量超限: %d > %d".formatted(tasks.size(), maxWorkers));
        }

        // 3. 读取调用方上下文
        int callerDepth = input.getContextValue(ToolContextKeys.CALLER_DEPTH, Integer.class).orElse(0);
        String callerTraceId = input.getContextValue(ToolContextKeys.CALLER_TRACE_ID, String.class).orElse(null);
        String callerSessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class)
                .orElse("worker-" + UUID.randomUUID().toString().substring(0, 8));
        Budget callerBudget = input.getContextValue(ToolContextKeys.CALLER_BUDGET, Budget.class).orElse(null);

        // 4. 深度校验
        int workerDepth = callerDepth + 1;
        if (workerDepth > config.getMaxDelegationDepth()) {
            return ToolResult.error("委托深度超限: depth=%d, maxDepth=%d"
                    .formatted(workerDepth, config.getMaxDelegationDepth()));
        }

        // 5. 预算分配
        Budget workerPoolBudget = resolveWorkerPoolBudget(callerBudget);
        int workerCount = tasks.size();

        // 6. 构建 Worker 工具白名单
        List<String> workerToolIds = buildWorkerToolIds();

        // 7. Worker System Prompt
        String workerPrompt = resolveWorkerPrompt();

        log.info("spawn_workers 开始执行: workerCount={}, depth={}, toolCount={}",
                workerCount, workerDepth, workerToolIds.size());

        // 8. 并行执行
        List<WorkerResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<WorkerResult>> futures = new ArrayList<>();

            for (int i = 0; i < workerCount; i++) {
                final int idx = i;
                final Map<String, Object> taskDef = tasks.get(i);

                // 每个 Worker 均分预算，支持 per-task override
                Budget perWorkerBudget = allocatePerWorkerBudget(workerPoolBudget, workerCount, taskDef);

                String message = buildWorkerMessage(taskDef);
                String workerSessionId = callerSessionId + ":worker-" + idx;

                var workerRequest = new AgentRequest(
                        message,
                        workerSessionId,
                        "internal",
                        null,
                        workerPrompt,
                        perWorkerBudget,
                        callerTraceId,
                        workerDepth,
                        null,
                        workerToolIds,
                        null,
                        null
                );

                futures.add(CompletableFuture.supplyAsync(() -> executeWorker(idx, taskDef, workerRequest), executor));
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }

        log.info("spawn_workers 执行完成: workerCount={}, successCount={}",
                workerCount, results.stream().filter(WorkerResult::success).count());

        // 9. 聚合结果
        return buildAggregatedResult(results);
    }

    /**
     * 执行单个 Worker。
     */
    private WorkerResult executeWorker(int index, Map<String, Object> taskDef, AgentRequest request) {
        String task = Objects.toString(taskDef.get("task"), "");
        Instant start = Instant.now();
        try {
            AgentResponse response = agentOrchestrator.run(request);
            boolean success = response.terminationReason() == null
                    || "需要用户澄清".equals(response.terminationReason());
            return new WorkerResult(
                    index, task, success, response.content(),
                    response.tokensUsed(),
                    Duration.between(start, Instant.now()).toMillis());
        } catch (Exception e) {
            log.warn("Worker-{} 执行异常: task={}, error={}", index, task, e.getMessage(), e);
            return new WorkerResult(
                    index, task, false,
                    "Worker 执行异常: " + e.getMessage(),
                    0,
                    Duration.between(start, Instant.now()).toMillis());
        }
    }

    /**
     * 解析 Worker 池预算 — 取配置默认预算的 workerBudgetRatio 比例。
     */
    private Budget resolveWorkerPoolBudget(@org.springframework.lang.Nullable Budget callerBudget) {
        var budgetDefaults = config.getBudget();
        var fullBudget = Budget.builder()
                .maxTokens(budgetDefaults.getDefaultMaxTokens())
                .tokensUsed(0).tokensReserved(0)
                .maxSteps(budgetDefaults.getDefaultMaxSteps())
                .stepsUsed(0)
                .maxDuration(Duration.ofSeconds(budgetDefaults.getDefaultTimeoutSeconds()))
                .elapsed(Duration.ZERO)
                .build();
        return SubAgentBudgetAllocator.allocateWorkerPoolBudget(
                callerBudget,
                fullBudget,
                config.getParallelWorker().getWorkerBudgetRatio()
        );
    }

    /**
     * 为单个 Worker 分配预算，支持 per-task budget override。
     */
    private Budget allocatePerWorkerBudget(Budget poolBudget, int workerCount, Map<String, Object> taskDef) {
        return SubAgentBudgetAllocator.allocateWorkerBudget(poolBudget, workerCount, taskDef);
    }

    /**
     * 构建 Worker 工具白名单 — 继承全部工具，排除 spawn_workers 防止递归。
     */
    private List<String> buildWorkerToolIds() {
        return toolRegistry.getToolSnapshot().stream()
                .map(ToolContract::id)
                .filter(id -> !TOOL_ID.equals(id))
                .toList();
    }

    /**
     * 解析 Worker System Prompt。
     */
    private String resolveWorkerPrompt() {
        String custom = config.getParallelWorker().getWorkerSystemPrompt();
        return (custom != null && !custom.isBlank()) ? custom : DEFAULT_WORKER_SYSTEM_PROMPT;
    }

    /**
     * 组装 Worker 消息。
     */
    private String buildWorkerMessage(Map<String, Object> taskDef) {
        String task = Objects.toString(taskDef.get("task"), "");
        Object context = taskDef.get("context");
        if (context != null && !context.toString().isBlank()) {
            return "任务: %s\n上下文: %s".formatted(task, context);
        }
        return task;
    }

    /**
     * 聚合所有 Worker 结果。
     */
    private ToolResult buildAggregatedResult(List<WorkerResult> results) {
        long successCount = results.stream().filter(WorkerResult::success).count();
        long failureCount = results.stream().filter(r -> !r.success()).count();
        int totalTokens = results.stream().mapToInt(WorkerResult::tokensUsed).sum();
        long wallClockMs = results.stream().mapToLong(WorkerResult::durationMs).max().orElse(0);

        List<Map<String, Object>> workerOutputs = results.stream()
                .map(r -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("workerIndex", r.workerIndex());
                    map.put("task", r.task());
                    map.put("success", r.success());
                    map.put("output", r.output());
                    map.put("tokensUsed", r.tokensUsed());
                    map.put("durationMs", r.durationMs());
                    return (Map<String, Object>) map;
                })
                .toList();

        var data = new LinkedHashMap<String, Object>();
        data.put("workers", workerOutputs);
        data.put("totalTokensUsed", totalTokens);
        data.put("wallClockMs", wallClockMs);
        data.put("successCount", successCount);
        data.put("failureCount", failureCount);

        if (failureCount == 0) {
            return ToolResult.success(data);
        } else if (successCount > 0) {
            String failedTasks = results.stream()
                    .filter(r -> !r.success())
                    .map(r -> "Worker-%d: %s".formatted(r.workerIndex(), r.task()))
                    .collect(Collectors.joining("; "));
            return ToolResult.partialSuccess(data, "部分 Worker 执行失败: " + failedTasks);
        } else {
            return ToolResult.error("所有 Worker 执行失败");
        }
    }
}
