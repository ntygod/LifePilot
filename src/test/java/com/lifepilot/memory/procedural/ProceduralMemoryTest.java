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

        // 创建 preference_rules 表（与 V20 迁移脚本一致）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS preference_rules (
                    rule_id             TEXT PRIMARY KEY,
                    category            TEXT NOT NULL,
                    key                 TEXT NOT NULL,
                    value               TEXT NOT NULL,
                    confidence          REAL NOT NULL DEFAULT 0.3,
                    learned_from_json   TEXT NOT NULL DEFAULT '[]',
                    observation_count   INTEGER NOT NULL DEFAULT 1,
                    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at          TEXT NOT NULL DEFAULT (datetime('now')),
                    UNIQUE(category, key)
                )""");

        // 创建 strategy_patterns 表（与 V20 迁移脚本一致）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS strategy_patterns (
                    pattern_id          TEXT PRIMARY KEY,
                    situation           TEXT NOT NULL,
                    recommended_action  TEXT NOT NULL,
                    success_rate        REAL NOT NULL DEFAULT 0.0,
                    application_count   INTEGER NOT NULL DEFAULT 0,
                    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
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

    // --- 成功率追踪测试 ---

    @Test
    void recordExecution_成功时_加权平均提升successRate() {
        var now = Instant.now();
        // 初始 successRate=0.8, useCount=4
        var template = new ProcedureTemplate(
                "tpl-exec-success", "测试模板", "描述", "意图",
                List.of(), Map.of(), 0.8f, 4, now, List.of(), now, now);
        proceduralMemory.save(template);

        proceduralMemory.recordExecution("tpl-exec-success", true);

        var found = proceduralMemory.findById("tpl-exec-success");
        assertThat(found).isPresent();
        // newRate = (0.8 * 4 + 1.0) / 5 = 4.2 / 5 = 0.84
        assertThat(found.get().successRate()).isCloseTo(0.84f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(found.get().useCount()).isEqualTo(5);
        assertThat(found.get().lastUsedAt()).isNotNull();
    }

    @Test
    void recordExecution_失败时_加权平均降低successRate() {
        var now = Instant.now();
        // 初始 successRate=0.8, useCount=4
        var template = new ProcedureTemplate(
                "tpl-exec-fail", "测试模板", "描述", "意图",
                List.of(), Map.of(), 0.8f, 4, now, List.of(), now, now);
        proceduralMemory.save(template);

        proceduralMemory.recordExecution("tpl-exec-fail", false);

        var found = proceduralMemory.findById("tpl-exec-fail");
        assertThat(found).isPresent();
        // newRate = (0.8 * 4 + 0.0) / 5 = 3.2 / 5 = 0.64
        assertThat(found.get().successRate()).isCloseTo(0.64f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(found.get().useCount()).isEqualTo(5);
    }

    @Test
    void recordExecution_模板不存在_不抛异常() {
        // 不应抛异常，仅记录 WARN 日志
        proceduralMemory.recordExecution("non-existent-id", true);
    }

    @Test
    void recordExecution_初始useCount为0_首次执行() {
        var now = Instant.now();
        var template = new ProcedureTemplate(
                "tpl-exec-first", "新模板", "描述", "意图",
                List.of(), Map.of(), 0.0f, 0, null, List.of(), now, now);
        proceduralMemory.save(template);

        proceduralMemory.recordExecution("tpl-exec-first", true);

        var found = proceduralMemory.findById("tpl-exec-first");
        assertThat(found).isPresent();
        // newRate = (0.0 * 0 + 1.0) / 1 = 1.0
        assertThat(found.get().successRate()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(found.get().useCount()).isEqualTo(1);
        assertThat(found.get().lastUsedAt()).isNotNull();
    }

    // --- 偏好规则 CRUD 测试 ---

    @Test
    void savePreference_findPreference_往返一致() {
        var now = Instant.now();
        var rule = new PreferenceRule(
                "pref-1", "output", "language", "中文",
                0.8f, "conversation-123", 3, now, now);

        proceduralMemory.savePreference(rule);

        var found = proceduralMemory.findPreference("output", "language");
        assertThat(found).isPresent();
        var loaded = found.get();
        assertThat(loaded.ruleId()).isEqualTo("pref-1");
        assertThat(loaded.category()).isEqualTo("output");
        assertThat(loaded.key()).isEqualTo("language");
        assertThat(loaded.value()).isEqualTo("中文");
        assertThat(loaded.confidence()).isEqualTo(0.8f);
        assertThat(loaded.learnedFrom()).isEqualTo("conversation-123");
        assertThat(loaded.observationCount()).isEqualTo(3);
    }

    @Test
    void savePreference_相同categoryKey_覆盖旧记录() {
        var now = Instant.now();
        var rule1 = new PreferenceRule(
                "pref-old", "output", "format", "markdown",
                0.5f, "conv-1", 1, now, now);
        var rule2 = new PreferenceRule(
                "pref-new", "output", "format", "plain-text",
                0.9f, "conv-2", 5, now, now);

        proceduralMemory.savePreference(rule1);
        proceduralMemory.savePreference(rule2);

        var found = proceduralMemory.findPreference("output", "format");
        assertThat(found).isPresent();
        assertThat(found.get().ruleId()).isEqualTo("pref-new");
        assertThat(found.get().value()).isEqualTo("plain-text");

        // 确认只有一条记录
        var all = proceduralMemory.getPreferences("output");
        assertThat(all).hasSize(1);
    }

    @Test
    void findPreference_不存在_返回空() {
        var found = proceduralMemory.findPreference("nonexistent", "key");
        assertThat(found).isEmpty();
    }

    @Test
    void getPreferences_按类别返回多条() {
        var now = Instant.now();
        proceduralMemory.savePreference(new PreferenceRule(
                "pref-a", "schedule", "reminder", "提前15分钟",
                0.6f, "conv-1", 2, now, now));
        proceduralMemory.savePreference(new PreferenceRule(
                "pref-b", "schedule", "default-duration", "30分钟",
                0.7f, "conv-2", 3, now, now));
        proceduralMemory.savePreference(new PreferenceRule(
                "pref-c", "output", "tone", "友好",
                0.5f, "conv-3", 1, now, now));

        var schedulePrefs = proceduralMemory.getPreferences("schedule");
        assertThat(schedulePrefs).hasSize(2);
        assertThat(schedulePrefs).extracting(PreferenceRule::category)
                .containsOnly("schedule");
    }

    @Test
    void getPreferences_空类别_返回空列表() {
        var result = proceduralMemory.getPreferences("empty-category");
        assertThat(result).isEmpty();
    }

    @Test
    void reinforcePreference_递增observationCount并提升confidence() {
        var now = Instant.now();
        var rule = new PreferenceRule(
                "pref-reinforce", "habit", "wake-time", "7:00",
                0.3f, "conv-1", 1, now, now);
        proceduralMemory.savePreference(rule);

        proceduralMemory.reinforcePreference("pref-reinforce");

        var found = proceduralMemory.findPreference("habit", "wake-time");
        assertThat(found).isPresent();
        assertThat(found.get().observationCount()).isEqualTo(2);
        assertThat(found.get().confidence()).isCloseTo(0.35f,
                org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void reinforcePreference_confidence上限为1() {
        var now = Instant.now();
        var rule = new PreferenceRule(
                "pref-cap", "habit", "exercise", "跑步",
                0.98f, "conv-1", 10, now, now);
        proceduralMemory.savePreference(rule);

        proceduralMemory.reinforcePreference("pref-cap");

        var found = proceduralMemory.findPreference("habit", "exercise");
        assertThat(found).isPresent();
        assertThat(found.get().confidence()).isLessThanOrEqualTo(1.0f);
        assertThat(found.get().observationCount()).isEqualTo(11);
    }

    @Test
    void reinforcePreference_规则不存在_不抛异常() {
        // 不应抛异常，仅记录 WARN 日志
        proceduralMemory.reinforcePreference("non-existent-id");
    }

    // --- 辅助方法 ---

    private ProcedureTemplate createSimpleTemplate(String id, String name, Instant now) {
        return new ProcedureTemplate(
                id, name, "测试描述", "测试意图",
                List.of(new TemplateStep(1, "test-tool", "run", Map.of(), "测试步骤", false)),
                Map.of(), 0.8f, 3, now, List.of(), now, now);
    }
}
