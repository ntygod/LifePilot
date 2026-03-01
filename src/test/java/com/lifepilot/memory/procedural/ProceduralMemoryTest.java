package com.lifepilot.memory.procedural;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ProceduralMemory 模板 CRUD 单元测试。
 *
 * <p>使用内存 SQLite 验证 save → findById → update → delete 完整流程。
 * VectorSearcher 使用 Mock，避免依赖 LLM 和 sqlite-vec。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
class ProceduralMemoryTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private VectorSearcher vectorSearcher;
    private ProceduralMemory proceduralMemory;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 创建 procedure_templates 表（与 V20 迁移脚本一致）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS procedure_templates (
                    template_id         TEXT PRIMARY KEY,
                    name                TEXT NOT NULL,
                    description         TEXT NOT NULL,
                    trigger_intent      TEXT NOT NULL,
                    steps_json          TEXT NOT NULL,
                    variables_json      TEXT NOT NULL DEFAULT '{}',
                    success_rate        REAL NOT NULL DEFAULT 0.0,
                    use_count           INTEGER NOT NULL DEFAULT 0,
                    last_used_at        TEXT,
                    source_trace_ids_json TEXT NOT NULL DEFAULT '[]',
                    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at          TEXT NOT NULL DEFAULT (datetime('now'))
                )""");

        // Mock VectorSearcher — 避免依赖 LLM 和 sqlite-vec
        vectorSearcher = mock(VectorSearcher.class);

        var properties = new MemoryProperties();
        properties.setEmbeddingDimensions(128);

        proceduralMemory = new ProceduralMemory(jdbcTemplate, vectorSearcher, properties);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void save_findById_完整字段往返一致() {
        var now = Instant.now();
        var steps = List.of(
                new TemplateStep(1, "todo-add", "execute",
                        Map.of("title", "${taskName}"), "添加待办", false),
                new TemplateStep(2, "schedule-add", "execute",
                        Map.of("time", "${deadline}"), "设置提醒", true)
        );
        var template = new ProcedureTemplate(
                UUID.randomUUID().toString(), "添加待办并提醒", "创建待办事项并设置截止提醒",
                "帮我添加一个待办", steps, Map.of("taskName", "", "deadline", ""),
                0.85f, 5, now, List.of("trace-1", "trace-2"), now, now);

        proceduralMemory.save(template);

        var found = proceduralMemory.findById(template.templateId());
        assertThat(found).isPresent();

        var loaded = found.get();
        assertThat(loaded.templateId()).isEqualTo(template.templateId());
        assertThat(loaded.name()).isEqualTo("添加待办并提醒");
        assertThat(loaded.description()).isEqualTo("创建待办事项并设置截止提醒");
        assertThat(loaded.triggerIntent()).isEqualTo("帮我添加一个待办");
        assertThat(loaded.successRate()).isEqualTo(0.85f);
        assertThat(loaded.useCount()).isEqualTo(5);
        assertThat(loaded.sourceTraceIds()).containsExactly("trace-1", "trace-2");

        // 验证 steps 反序列化
        assertThat(loaded.steps()).hasSize(2);
        assertThat(loaded.steps().get(0).toolId()).isEqualTo("todo-add");
        assertThat(loaded.steps().get(0).parameterTemplate()).containsEntry("title", "${taskName}");
        assertThat(loaded.steps().get(1).isOptional()).isTrue();

        // 验证 variables 反序列化
        assertThat(loaded.variables()).containsKeys("taskName", "deadline");

        // 验证 VectorSearcher 被调用
        verify(vectorSearcher).upsertEntityVector(template.templateId(), "帮我添加一个待办");
    }

    @Test
    void findById_不存在_返回空() {
        var found = proceduralMemory.findById("non-existent-id");
        assertThat(found).isEmpty();
    }

    @Test
    void update_修改字段后_查询返回新值() {
        var now = Instant.now();
        var template = createSimpleTemplate("tpl-update", "原始名称", now);
        proceduralMemory.save(template);

        // 更新
        var updated = new ProcedureTemplate(
                template.templateId(), "更新后名称", "更新后描述",
                "新的触发意图", template.steps(), Map.of("newVar", "value"),
                0.95f, 10, now, List.of("trace-3"), now, Instant.now());
        proceduralMemory.update(updated);

        var found = proceduralMemory.findById(template.templateId());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("更新后名称");
        assertThat(found.get().description()).isEqualTo("更新后描述");
        assertThat(found.get().triggerIntent()).isEqualTo("新的触发意图");
        assertThat(found.get().successRate()).isEqualTo(0.95f);
        assertThat(found.get().useCount()).isEqualTo(10);
        assertThat(found.get().variables()).containsEntry("newVar", "value");

        // 验证 VectorSearcher 被调用两次（save + update）
        verify(vectorSearcher, times(2)).upsertEntityVector(anyString(), anyString());
    }

    @Test
    void delete_删除后_查询返回空() {
        var now = Instant.now();
        var template = createSimpleTemplate("tpl-delete", "待删除模板", now);
        proceduralMemory.save(template);

        assertThat(proceduralMemory.findById(template.templateId())).isPresent();

        proceduralMemory.delete(template.templateId());

        assertThat(proceduralMemory.findById(template.templateId())).isEmpty();
        verify(vectorSearcher).deleteEntityVector(template.templateId());
    }

    @Test
    void save_lastUsedAt为null_正常保存和读取() {
        var now = Instant.now();
        var template = new ProcedureTemplate(
                UUID.randomUUID().toString(), "无使用记录模板", "描述",
                "触发意图", List.of(), Map.of(),
                0.0f, 0, null, List.of(), now, now);

        proceduralMemory.save(template);

        var found = proceduralMemory.findById(template.templateId());
        assertThat(found).isPresent();
        assertThat(found.get().lastUsedAt()).isNull();
        assertThat(found.get().steps()).isEmpty();
        assertThat(found.get().variables()).isEmpty();
        assertThat(found.get().sourceTraceIds()).isEmpty();
    }

    // --- 辅助方法 ---

    private ProcedureTemplate createSimpleTemplate(String id, String name, Instant now) {
        return new ProcedureTemplate(
                id, name, "测试描述", "测试意图",
                List.of(new TemplateStep(1, "test-tool", "run", Map.of(), "测试步骤", false)),
                Map.of(), 0.8f, 3, now, List.of(), now, now);
    }
}
