package com.lifepilot.tool.search;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.search.cache.SchemaCache;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolDescribeService 批量行为测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
class ToolDescribeService_批量测试 {

    private DynamicToolRegistry registry;
    private ToolDescribeService service;

    @BeforeEach
    void setUp() {
        registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(sample("file.read"));
        service = new ToolDescribeService(registry, new SchemaCache(100), 10);
    }

    @Test
    void 部分存在部分不存在_structured返回() {
        ToolDescribeResult result = service.describe(List.of("file.read", "nonexistent.tool"));

        assertThat(result.schemas()).containsKey("file.read");
        assertThat(result.notFound()).containsExactly("nonexistent.tool");
        assertThat(result.suggestion()).isNotBlank();
    }

    @Test
    void 空id列表_返回suggestion提示() {
        ToolDescribeResult result = service.describe(List.of());

        assertThat(result.schemas()).isEmpty();
        assertThat(result.suggestion()).contains("No tool_ids provided");
    }

    @Test
    void 批量超过上限_截断且给提示() {
        for (int i = 0; i < 15; i++) {
            registry.registerBuiltinTool(sample("tool.t" + i));
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            ids.add("tool.t" + i);
        }

        ToolDescribeResult result = service.describe(ids);

        assertThat(result.schemas()).hasSize(10);
        assertThat(result.suggestion()).contains("batch size limit");
    }

    @Test
    void 完整描述包含schema和风险级() {
        ToolDescribeResult result = service.describe(List.of("file.read"));
        Object schema = result.schemas().get("file.read");

        assertThat(schema).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaMap = (Map<String, Object>) schema;
        assertThat(schemaMap).containsKeys(
                "description", "input_schema", "output_schema", "risk_level", "idempotent");
    }

    /** 构造一个最小可用的 BuiltinTool 样例。 */
    private BuiltinTool sample(String id) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("Read content from the specified path")
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT)
                .tags(List.of("read", "file", "content"))
                .category(ToolCategory.ACTION)
                .actionMetadata(Map.of())
                .executor(input -> null)
                .build();
    }
}
