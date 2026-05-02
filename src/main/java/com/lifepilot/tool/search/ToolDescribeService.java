package com.lifepilot.tool.search;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tool.search} 服务：批量返回工具完整 schema。
 *
 * <p>超过批量上限时截断前 N 个并在 suggestion 字段给提示；
 * 部分 ID 找不到时同时返回 schemas 和 notFound。</p>
 *
 * <p>Micrometer 指标：invocations / batch_size（DistributionSummary）/ not_found。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolDescribeService {

    private final DynamicToolRegistry registry;
    private final int maxBatchSize;

    // ── Micrometer 指标 ──
    private final Counter invocationsCounter;
    private final DistributionSummary batchSizeSummary;
    private final Counter notFoundCounter;

    public ToolDescribeService(DynamicToolRegistry registry,
                               int maxBatchSize,
                               MeterRegistry meterRegistry) {
        this.registry = registry;
        this.maxBatchSize = maxBatchSize;

        this.invocationsCounter = meterRegistry.counter("tool_describe.invocations");
        this.batchSizeSummary = DistributionSummary.builder("tool_describe.batch_size")
                .register(meterRegistry);
        this.notFoundCounter = meterRegistry.counter("tool_describe.not_found");
    }

    /**
     * 批量描述工具 schema。
     *
     * @param requestedIds 请求的工具 ID 列表
     * @return 描述结果（永不为 null）
     */
    public ToolDescribeResult describe(List<String> requestedIds) {
        invocationsCounter.increment();

        if (requestedIds == null || requestedIds.isEmpty()) {
            batchSizeSummary.record(0);
            return new ToolDescribeResult(
                    Map.of(),
                    List.of(),
                    "No tool_ids provided. Pass an array of tool IDs obtained from tool.search.");
        }

        boolean truncated = requestedIds.size() > maxBatchSize;
        List<String> effective = truncated
                ? requestedIds.subList(0, maxBatchSize)
                : requestedIds;

        batchSizeSummary.record(effective.size());

        Map<String, Object> schemas = new LinkedHashMap<>();
        List<String> notFound = new ArrayList<>();

        for (String id : effective) {
            Optional<ToolContract> opt = registry.resolve(id);
            if (opt.isEmpty()) {
                notFound.add(id);
                continue;
            }
            schemas.put(id, buildSchemaView(opt.get()));
        }

        if (!notFound.isEmpty()) {
            notFoundCounter.increment();
        }

        String suggestion;
        if (truncated) {
            suggestion = "Request exceeded batch size limit (%d). Truncated to first %d. Call describe in separate batches."
                    .formatted(maxBatchSize, maxBatchSize);
        } else if (!notFound.isEmpty()) {
            suggestion = "Some tool_ids not found. Call tool.search to discover valid IDs.";
        } else {
            suggestion = null;
        }

        return new ToolDescribeResult(schemas, notFound, suggestion);
    }

    /** 组装一个工具对外暴露的 schema 视图（description + input/output schema + risk + action 映射）。 */
    private Map<String, Object> buildSchemaView(ToolContract tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("description", tool.description() == null ? "" : tool.description());
        view.put("input_schema", tool.inputSchema().toMap());
        view.put("output_schema", tool.outputSchema().toMap());
        view.put("risk_level", tool.riskLevel().name());
        view.put("idempotent", tool.idempotent());
        if (tool instanceof BuiltinTool builtin
                && builtin.actionMetadata() != null && !builtin.actionMetadata().isEmpty()) {
            Map<String, Map<String, Object>> actionsView = new LinkedHashMap<>();
            for (var entry : builtin.actionMetadata().entrySet()) {
                actionsView.put(entry.getKey(), Map.of(
                        "risk_level", entry.getValue().riskLevel().name()
                ));
            }
            view.put("actions", actionsView);
        }
        return view;
    }
}
