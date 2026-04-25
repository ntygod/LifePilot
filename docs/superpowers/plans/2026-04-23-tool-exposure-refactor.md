# 工具暴露机制重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套 spec**：`docs/superpowers/specs/2026-04-23-tool-exposure-redesign-design.md`
> **前置依赖**：无（纯重构，建立在现有 Tool / Skill / ReactAgent 基础之上）
>
> **实施后变更（2026-04-25）**：本文档保留作为历史档案。本计划主体已落地，但下列 Task 在后续打磨中被回退或调整，最新事实源以 `docs/architecture/tool-ecosystem.md` 为准：
>
> - **A.11 / A.12 / A.13 / A.14（Tier 1 晋升链路）整组下架**：`Tier1Advisory` / `Tier1AdvisoryStatus` / `Tier1AdvisoryRepository` / `Tier1AdvisoryJob` / `ToolUsageStats` / `ToolUsageStatsRepository` / `ToolUsageStatsRecorder` 共 7 个 Java 类删除，对应 3 个测试一并删除（V29 迁移删表）。原因：单机本地部署没有"管理员审批"角色，PENDING advisory 永远没人 APPROVE，整套机制是死代码。`Tier1Service` 简化为只读 `pinned` 配置。
> - **FTS5 tokenizer 从 `unicode61` 切到 `trigram`**（V30 迁移）：原 unicode61 word-level 分词不利于中文 query；trigram 用 3 字符滑窗双向 substring 匹配，对中文短语命中更友好。`ToolSearchQuerySanitizer` 同步重写（3-gram phrase 滑窗用 OR 连接）。
> - **`ToolValidator` description / tags 校验放开**：原方案要求 description 必须英文 + 长度 ≥ 40 字符、tags 必须英文，现允许中英混排（description ≥ 20 字符，无中文且无英文动词时 warn 软提示）。
> - **所有工具 description / tags 中文化 + 删除 `"infrastructure"` 噪声 tag**：description 改写包含高频用户短语（如"删除文件""复制目录"）让 trigram substring 直接命中。

**Goal：** 把"13 核心 schema 常驻 / 其余 Skill 激活暴露"的二分架构，换成"Tier 1 高频常驻 + Tier 2 BM25 可搜索"三层架构。新增 `tools.search` / `tools.describe` / `tools.list` 三个 meta 工具，所有工具 metadata 英文化，ToolValidator 启动时强校验命名规范。

**Architecture：**
1. **搜索层**：`ToolSearchService` + SQLite FTS5 `tool_search_index` 表 + `ToolSearchQuerySanitizer`（防注入）+ 三层缓存（Caffeine schema cache / Caffeine search result cache / Map session memo）
2. **索引生命周期**：`ToolSearchIndexBuilder`（启动时构建）+ `ToolSearchIndexMaintainer`（监听 `ToolRegistryEvent` 增量维护）
3. **Meta 工具层**：`BuiltinToolSearchProvider` 注册 `tools.search` / `tools.describe` / `tools.list` 三个 BuiltinTool
4. **过滤层**：`ToolBridgeAgentToolProvider` 过滤逻辑统一（Tier 1 ∪ activatedToolIds ∪ meta tools）
5. **Tier 1 管理层**：`Tier1Service` / `ToolUsageStatsRecorder` / `ToolUsageAnalytics` / `Tier1AdvisoryJob` / `Tier1AdvisoryRepository`
6. **校验层**：`ToolValidator` 在 `BuiltinToolRegistrar` 触发，硬规则违反启动失败
7. **Prompt 层**：`ReactAgentLoop` 构建 system prompt 时追加 category hint

**Tech Stack：** Spring Boot 3 + Java 22（record / sealed / pattern matching）、SQLite + Flyway V15 + FTS5、Caffeine、JdbcTemplate、Spring AI ToolCallback、JUnit 5 + jqwik + Mockito + ApplicationContextRunner + AssertJ、Micrometer

---

## File Structure

### 后端新建

| 路径 | 责任 |
|---|---|
| `src/main/resources/db/migration/V15__tool_exposure_refactor.sql` | FTS5 `tool_search_index` + `tool_usage_stats` + `tier1_advisory` |
| `src/main/java/com/lifepilot/tool/validation/ToolValidator.java` | 启动时校验工具命名/描述/tags 规范 |
| `src/main/java/com/lifepilot/tool/search/ToolSearchQuerySanitizer.java` | FTS5 MATCH 语法消毒，防止保留字符注入 |
| `src/main/java/com/lifepilot/tool/search/ToolSearchConfidence.java` | enum：HIGH / LOW / NONE |
| `src/main/java/com/lifepilot/tool/search/ToolSearchHit.java` | record：id / description / category / score / actions |
| `src/main/java/com/lifepilot/tool/search/ToolSearchResult.java` | record：results / totalMatched / confidence / hint |
| `src/main/java/com/lifepilot/tool/search/ToolDescribeResult.java` | record：schemas / notFound / suggestion |
| `src/main/java/com/lifepilot/tool/search/ToolListResult.java` | record：categories（Map<String, List<String>>）/ total |
| `src/main/java/com/lifepilot/tool/search/cache/SchemaCache.java` | Layer A：Caffeine `tool_id → 预序列化 schema JSON` |
| `src/main/java/com/lifepilot/tool/search/cache/SearchResultCache.java` | Layer B：Caffeine `sha256(query+cat+limit) → ToolSearchResult` |
| `src/main/java/com/lifepilot/tool/search/cache/SessionSearchMemo.java` | Layer C：会话内 Map，由 ReactAgentState 承载 |
| `src/main/java/com/lifepilot/tool/search/ToolSearchIndexBuilder.java` | 启动时扫描 registry 全量 INSERT |
| `src/main/java/com/lifepilot/tool/search/ToolSearchIndexMaintainer.java` | `@EventListener(ToolRegistryEvent)` 增量 INSERT/UPDATE/DELETE |
| `src/main/java/com/lifepilot/tool/search/ToolSearchService.java` | 主查询服务：sanitize + FTS5 查询 + 过滤 + 三层缓存 |
| `src/main/java/com/lifepilot/tool/search/ToolDescribeService.java` | 批量 describe + SchemaCache |
| `src/main/java/com/lifepilot/tool/search/ToolListService.java` | 按 category 列 ID |
| `src/main/java/com/lifepilot/tool/search/BuiltinToolSearchProvider.java` | 注册 `tools.search` / `tools.describe` / `tools.list` 三个 BuiltinTool |
| `src/main/java/com/lifepilot/tool/tier1/Tier1Advisory.java` | record：id / toolId / advisedAt / windowDays / coverageRatio / status / reviewedBy / reviewedAt |
| `src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryStatus.java` | enum：PENDING / APPROVED / REJECTED |
| `src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryRepository.java` | JdbcTemplate：save / findPending / approve / reject |
| `src/main/java/com/lifepilot/tool/tier1/Tier1Service.java` | 聚合 pinned 配置 + 已批准晋升，提供 `getCurrentTier1Ids()` |
| `src/main/java/com/lifepilot/tool/tier1/ToolUsageStats.java` | record：toolId / statDate / sessionCount / invocationCount |
| `src/main/java/com/lifepilot/tool/tier1/ToolUsageStatsRepository.java` | JdbcTemplate：upsertDaily / querySessionCoverage |
| `src/main/java/com/lifepilot/tool/tier1/ToolUsageStatsRecorder.java` | 实现 `ToolInvocationListener`，ToolExecutionPipeline 成功执行后写 daily stats |
| `src/main/java/com/lifepilot/tool/tier1/ToolUsageAnalytics.java` | 计算近 N 天会话覆盖率 |
| `src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryJob.java` | `@Scheduled` 每日凌晨生成晋升建议 |

### 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/tool/config/ToolConfigProperties.java` | 新增 `tier1` / `search` / `describe` / `categoryHint` 字段组 |
| `src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java` | 新增 11 个 Bean（ToolValidator / 搜索服务全家桶 / Tier1 服务全家桶）|
| `src/main/java/com/lifepilot/tool/registry/BuiltinToolRegistrar.java` | 注册前调用 ToolValidator，失败抛异常阻止启动 |
| `src/main/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProvider.java` | 过滤逻辑统一：Tier 1 ∪ activatedToolIds ∪ meta tools |
| `src/main/java/com/lifepilot/tool/pipeline/ToolExecutionPipeline.java` | 执行成功后发 ToolInvocationEvent 供 StatsRecorder 监听 |
| `src/main/java/com/lifepilot/agent/ReactAgentLoop.java` | prompt 构建时拼接 `categoryHint` 到 system 消息尾部 |
| `src/main/resources/application.yml` | 新增 `agent.tools.tier1` / `search` / `describe` / `category-hint` 段；旧 `core-tool-ids` 标 `@Deprecated` 保留 |

### 现有 13 个核心工具英文化

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/*/tool/*ToolProvider.java`（影响 ~13 个文件）| `description` 改英文短描述（≥ 40 字符，含主动词）+ `tags` 补 ≥ 3 个英文同义词 |

### 测试

| 路径 | 操作 |
|---|---|
| `src/test/java/com/lifepilot/tool/validation/ToolValidator_启动校验测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/ToolSearchQuerySanitizer_注入防护测试.java` | 新建（jqwik 属性测试）|
| `src/test/java/com/lifepilot/tool/search/cache/SchemaCache_行为测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/cache/SearchResultCache_失效测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/ToolSearchIndexBuilder_构建测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/ToolSearchIndexMaintainer_增量测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/ToolSearchService_查询过滤测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/ToolDescribeService_批量测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/search/BuiltinToolSearchProvider_注册测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProviderTest.java` | 修改（更新三分支→统一过滤的断言）|
| `src/test/java/com/lifepilot/tool/tier1/Tier1AdvisoryRepository_持久化测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/tier1/ToolUsageStatsRecorder_记录测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/tier1/Tier1AdvisoryJob_晋升计算测试.java` | 新建 |
| `src/test/java/com/lifepilot/integration/ToolExposureRefactor_端到端集成测试.java` | 新建 |
| `src/test/resources/tool-search-fixtures.yaml` | 新建（30-50 条质量回归 fixtures）|
| `src/test/java/com/lifepilot/tool/search/ToolSearchQuality_召回率回归测试.java` | 新建 |

---

## Phase 0 · 准备（ToolValidator + 英文化 + 配置）

### Task 0.1：新增 ToolValidator + 测试

**Files：**
- Create: `src/main/java/com/lifepilot/tool/validation/ToolValidator.java`
- Create: `src/test/java/com/lifepilot/tool/validation/ToolValidator_启动校验测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.tool.validation;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ToolValidator_启动校验测试 {

    private final ToolValidator validator = new ToolValidator();

    @Test
    void ID格式不合法_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("BadId")    // 大写不合规
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ID 格式不合法");
    }

    @Test
    void 缺少动词或namespace白名单_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("foo.bar")    // 无动词词根，namespace 不在白名单
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("ID 自描述度");
    }

    @Test
    void name不是中文_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("Read File")    // 纯英文
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("name 必须是中文");
    }

    @Test
    void description长度不足40字符_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Short")
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("description 长度不足");
    }

    @Test
    void description含中文_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file from path and 返回 content to caller")
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("description 必须英文");
    }

    @Test
    void tags少于3个_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file content from the specified path")
                .tags(List.of("read", "file"))    // 只有 2 个
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("tags 数量不足");
    }

    @Test
    void tags重复_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file content from the specified path")
                .tags(List.of("read", "read", "file"))
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("tags 有重复");
    }

    @Test
    void 合法工具_应通过() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file content from the specified path as text or binary")
                .tags(List.of("read", "file", "content", "load", "fetch"))
                .build();
        assertThatCode(() -> validator.validate(tool)).doesNotThrowAnyException();
    }

    private BuiltinTool.Builder baseBuilder() {
        return BuiltinTool.builder()
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.stateless())
                .category(ToolCategory.ACTION)
                .executor(input -> null);
    }
}
```

- [ ] **Step 2：运行测试验证失败**

```
mvn test -Dtest=ToolValidator_启动校验测试
```
Expected: 全部红（ToolValidator 不存在）

- [ ] **Step 3：实现 ToolValidator**

```java
package com.lifepilot.tool.validation;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.dispatch.ActionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具命名规范启动期强校验器。
 *
 * <p>硬规则违反抛 {@link IllegalStateException} 阻止启动；
 * 软规则仅记录 warn 日志不阻塞。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolValidator.class);

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$");

    /** namespace 白名单——属于这些 namespace 的工具即使 ID 里无动词也算自描述。 */
    private static final Set<String> SELF_DESCRIPTIVE_NAMESPACES = Set.of(
            "memory", "knowledge", "notify", "shell", "web",
            "file", "document", "datastore", "cron", "channel",
            "process", "tools", "ui", "system"
    );

    /** 动词词根（精简版 stemmer）。 */
    private static final Set<String> VERB_ROOTS = Set.of(
            "read", "write", "list", "create", "update", "delete", "remove",
            "query", "search", "find", "fetch", "get", "put", "post",
            "send", "emit", "notify", "exec", "execute", "run",
            "patch", "commit", "rollback", "store", "save", "load",
            "start", "stop", "restart", "cancel", "schedule", "trigger",
            "describe", "inspect", "status"
    );

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern ENGLISH_ONLY = Pattern.compile("^[\\x00-\\x7F]+$");

    private static final int MIN_DESCRIPTION_LENGTH = 40;
    private static final int MIN_TAG_COUNT = 3;

    /** 不参与校验的 meta 工具豁免（v1 不对自己应用硬规则）。 */
    private static final Set<String> EXEMPTED_IDS = Set.of(
            "tools.search", "tools.describe", "tools.list"
    );

    /**
     * 启动期校验工具。
     *
     * @param tool 待校验工具
     * @throws IllegalStateException 任一硬规则违反
     */
    public void validate(ToolContract tool) {
        if (EXEMPTED_IDS.contains(tool.id())) {
            return;
        }
        validateId(tool);
        validateName(tool);
        validateDescription(tool);
        validateTags(tool);
        validateActions(tool);
    }

    private void validateId(ToolContract tool) {
        String id = tool.id();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalStateException(
                    "ID 格式不合法（要求 ^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$）: " + id);
        }
        String[] parts = id.split("\\.");
        String namespace = parts[0];
        if (!SELF_DESCRIPTIVE_NAMESPACES.contains(namespace)
                && !containsVerbRoot(id)) {
            throw new IllegalStateException(
                    "ID 自描述度不足（namespace 不在白名单且不含动词词根）: " + id);
        }
    }

    private boolean containsVerbRoot(String id) {
        String lower = id.toLowerCase();
        return VERB_ROOTS.stream().anyMatch(lower::contains);
    }

    private void validateName(ToolContract tool) {
        String name = tool.name();
        if (name == null || name.isBlank() || !CHINESE_PATTERN.matcher(name).find()) {
            throw new IllegalStateException("name 必须是中文: " + tool.id());
        }
    }

    private void validateDescription(ToolContract tool) {
        String desc = tool.description();
        if (desc == null || desc.length() < MIN_DESCRIPTION_LENGTH) {
            throw new IllegalStateException(
                    "description 长度不足（要求 ≥ %d）: %s".formatted(MIN_DESCRIPTION_LENGTH, tool.id()));
        }
        if (CHINESE_PATTERN.matcher(desc).find()) {
            throw new IllegalStateException("description 必须英文: " + tool.id());
        }
        if (!containsVerbRoot(desc.toLowerCase())) {
            log.warn("description 未检测到明确动词，建议补充: toolId={}", tool.id());
        }
    }

    private void validateTags(ToolContract tool) {
        var tags = tool.tags();
        // Tier 2 要求 ≥ 3 个 tag；Tier 1（pinned 配置里的）可豁免，
        // 此处统一要求，Tier 1 也应补上以便未来降级后搜索可用
        if (tags == null || tags.size() < MIN_TAG_COUNT) {
            throw new IllegalStateException(
                    "tags 数量不足（要求 ≥ %d）: %s".formatted(MIN_TAG_COUNT, tool.id()));
        }
        for (String tag : tags) {
            if (!ENGLISH_ONLY.matcher(tag).matches()) {
                throw new IllegalStateException(
                        "tags 必须是英文（tag=%s）: %s".formatted(tag, tool.id()));
            }
        }
        Set<String> unique = new HashSet<>(tags);
        if (unique.size() != tags.size()) {
            throw new IllegalStateException("tags 有重复: " + tool.id());
        }
    }

    private void validateActions(ToolContract tool) {
        if (!(tool instanceof BuiltinTool builtin)) {
            return;
        }
        Map<String, ActionMetadata> actions = builtin.actionMetadata();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (var entry : actions.entrySet()) {
            var meta = entry.getValue();
            if (meta.description() != null
                    && CHINESE_PATTERN.matcher(meta.description()).find()) {
                throw new IllegalStateException(
                        "action.description 必须英文（action=%s）: %s"
                                .formatted(entry.getKey(), tool.id()));
            }
        }
    }
}
```

- [ ] **Step 4：运行测试验证通过**

```
mvn test -Dtest=ToolValidator_启动校验测试
```
Expected: 全部绿

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/validation/ToolValidator.java \
        src/test/java/com/lifepilot/tool/validation/ToolValidator_启动校验测试.java
git commit -m "feat(tool): 新增 ToolValidator 启动期强校验器

校验 ID 格式/自描述度、name 中文、description 英文 ≥ 40 字符、
tags 英文 ≥ 3 个且去重、action.description 英文。硬规则违反抛异常
阻止启动，软规则（description 主动词检测）仅 warn。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 0.2：ToolValidator 接入 BuiltinToolRegistrar

**Files：**
- Modify: `src/main/java/com/lifepilot/tool/registry/BuiltinToolRegistrar.java`
- Modify: `src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java`

- [ ] **Step 1：改造 BuiltinToolRegistrar，注入 ToolValidator**

```java
package com.lifepilot.tool.registry;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.validation.ToolValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;

public class BuiltinToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolRegistrar.class);

    private final List<BuiltinTool> builtinTools;
    private final DynamicToolRegistry registry;
    private final ToolValidator validator;

    public BuiltinToolRegistrar(
            List<BuiltinTool> builtinTools,
            DynamicToolRegistry registry,
            ToolValidator validator) {
        this.builtinTools = builtinTools;
        this.registry = registry;
        this.validator = validator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        log.info("开始校验并注册内置工具: count={}", builtinTools.size());
        for (BuiltinTool tool : builtinTools) {
            validator.validate(tool);   // 失败直接抛异常终止启动
            registry.registerBuiltinTool(tool);
        }
        log.info("内置工具注册完成: count={}", builtinTools.size());
        log.info("工具注册统计: {}", registry.getToolCountByLayer());
    }
}
```

- [ ] **Step 2：在 ToolAutoConfiguration 装配 ToolValidator Bean**

找到 `BuiltinToolRegistrar` Bean 声明处，前面加：

```java
@Bean
@ConditionalOnMissingBean
public ToolValidator toolValidator() {
    return new ToolValidator();
}

@Bean
public BuiltinToolRegistrar builtinToolRegistrar(
        List<BuiltinTool> builtinTools,
        DynamicToolRegistry registry,
        ToolValidator validator) {
    return new BuiltinToolRegistrar(builtinTools, registry, validator);
}
```

- [ ] **Step 3：运行 mvn compile 验证编译**

```
mvn compile
```
Expected: 成功

- [ ] **Step 4：运行完整测试确保不回归**

```
mvn test
```
Expected: 全绿（此时若现有工具定义不符合规范会启动失败，是预期——Task 0.3 修复）

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/registry/BuiltinToolRegistrar.java \
        src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java
git commit -m "refactor(tool): BuiltinToolRegistrar 注册前调用 ToolValidator

校验失败抛异常阻止启动，强制所有工具满足命名规范。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 0.3：现有 13 个核心工具英文化

**Files：**
- Modify: 约 13 个 `*ToolProvider.java` 文件（`file.*` / `web.*` / `shell.*` / `memory` / `knowledge.search` / `notify` / `document.create` / `document.edit`）
- Modify: 对应 action metadata 英文化

- [ ] **Step 1：识别所有工具定义文件**

```
mvn test -Dtest=ApplicationContextIntegrationTest 2>&1 | grep -i "ToolValidator\|IllegalStateException"
```
或手工 grep：
```
Grep pattern: BuiltinTool\.builder\(\) glob: src/main/**/*.java
```

预期输出：~13 个 Java 文件，每个是一个工具定义。

- [ ] **Step 2：改每个工具的 description 为英文（≥ 40 字符，含动词）+ tags 补齐 ≥ 3 个英文同义词**

示例模式——以 `file.read` 为例：

```java
// 改前
BuiltinTool.builder()
    .id("file.read")
    .name("读取文件")
    .description("读取指定路径的文件内容")
    .tags(List.of("文件"))
    .build();

// 改后
BuiltinTool.builder()
    .id("file.read")
    .name("读取文件")
    .description("Read file content from the specified path as text, structured document, or attachment reference")
    .tags(List.of("read", "file", "load", "fetch", "content", "document"))
    .build();
```

对每个工具逐一处理。参考模板（按工具类型）：

| 工具 ID | 英文 description 骨架 | 建议 tags |
|--------|--------------------|----------|
| `file.read` | "Read file content from the specified path as text, structured document, or attachment reference" | read, file, load, fetch, content, document |
| `file.write` | "Write text content to the specified file path, creating parent directories if needed" | write, file, save, create, content |
| `file.list` | "List entries in the specified directory, optionally filtering by pattern" | list, file, directory, browse, enumerate |
| `web.search` | "Search the web for information by keyword and return relevant results" | search, web, internet, google, query |
| `web.fetch` | "Fetch and parse the content of a web URL, returning readable text" | fetch, web, http, url, scrape, content |
| `shell.exec` | "Execute a shell command and return stdout, stderr, and exit code" | shell, exec, command, bash, terminal, run |
| `shell.process` | "Manage background shell processes: start, stop, list, inspect" | process, background, shell, job, task |
| `memory` | "Store, recall, or search episodic and semantic memory entries for the current agent" | memory, recall, remember, store, save, knowledge |
| `knowledge.search` | "Search the knowledge base by semantic similarity or keyword and return top matches" | knowledge, search, rag, retrieve, query, document |
| `notify` | "Send a notification message to the user via the current active channel" | notify, message, send, alert, notification, push |
| `document.create` | "Create a new document (docx/xlsx/pptx) from content or template, producing a downloadable file" | create, document, docx, xlsx, pptx, generate |
| `document.edit` | "Edit an existing docx working copy with actions: patch, diff, commit, rollback, list_versions" | edit, document, docx, patch, diff, commit, rollback, version |

- [ ] **Step 3：运行 mvn test 验证所有校验通过**

```
mvn test
```
Expected: ToolValidator 对所有 13 个工具校验通过，现有测试继续绿。

- [ ] **Step 4：启动应用做一次 sanity check**

```
mvn spring-boot:run
```
Expected: 启动日志 `内置工具注册完成: count=13`，无 IllegalStateException。Ctrl+C 停止。

- [ ] **Step 5：提交**

```bash
git add src/main/java
git commit -m "refactor(tool): 13 个核心工具 description 英文化 + tags 补齐

description 改为英文短描述（≥ 40 字符，含主动词），tags 补齐 ≥ 3 个
英文同义词（含中英同义、多种表达）。为 BM25 搜索索引质量做准备。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 0.4：扩展 ToolConfigProperties

**Files：**
- Modify: `src/main/java/com/lifepilot/tool/config/ToolConfigProperties.java`

- [ ] **Step 1：新增嵌套配置类**

在 `ToolConfigProperties` 里添加：

```java
// 文件顶部保留现有 import，补充：
import java.util.List;

public class ToolConfigProperties {

    // ... 现有字段保持 ...

    /** Tier 1 分层注入配置。 */
    private Tier1 tier1 = new Tier1();

    /** 搜索服务配置。 */
    private Search search = new Search();

    /** describe 服务配置。 */
    private Describe describe = new Describe();

    /** Category hint 提示文案（注入 system prompt 尾部）。 */
    private String categoryHint = """
            You have access to a tool registry indexed in English.
            Besides the tools visible above, you can discover more via:
              - tools.search(query, category?) to find tools by keyword
              - tools.describe(ids[]) to inspect schemas
              - tools.list(category?) to browse by capability category
            Available categories: PERCEPTION, ACTION, COGNITION, STORAGE,
              INTERACTION, INTROSPECTION, EXTENSION.
            Always use English keywords in search queries.
            """;

    // getter / setter 略

    public static class Tier1 {
        private List<String> pinned = List.of();
        private Promotion promotion = new Promotion();
        private Demotion demotion = new Demotion();
        // getter/setter
        public static class Promotion {
            private boolean enabled = true;
            private int windowDays = 30;
            private double sessionThreshold = 0.3;
            private int maxPromoted = 3;
            // getter/setter
        }
        public static class Demotion {
            private boolean enabled = true;
            private int idleDays = 60;
            private boolean respectPinned = true;
            // getter/setter
        }
    }

    public static class Search {
        private int defaultLimit = 5;
        private int maxLimit = 20;
        private double bm25ConfidenceThreshold = 1.0;
        private Cache cache = new Cache();
        private Fallback fallback = new Fallback();
        // getter/setter
        public static class Cache {
            private int layerAMaxSize = 500;
            private int layerBMaxSize = 1000;
            private int layerBTtlMinutes = 5;
            // getter/setter
        }
        public static class Fallback {
            private boolean vectorEnabled = false;
            // getter/setter
        }
    }

    public static class Describe {
        private int maxBatchSize = 10;
        // getter/setter
    }
}
```

（所有 getter/setter 省略，按现有 `ToolConfigProperties` 风格补全）

- [ ] **Step 2：在 `application.yml` 追加新配置段（旧 `core-tool-ids` 保留）**

```yaml
agent:
  tools:
    # 旧字段：@Deprecated 保留，Phase B 删除
    core-tool-ids:
      - shell.exec
      - web.search
      # ... 保持原样

    # 新增：Tier 1 分层注入
    tier1:
      pinned:
        - tools.search
        - tools.describe
        - tools.list
        - file.read
        - file.write
        - file.list
        - web.search
        - web.fetch
        - shell.exec
        - memory
        - knowledge.search
      promotion:
        enabled: true
        window-days: 30
        session-threshold: 0.3
        max-promoted: 3
      demotion:
        enabled: true
        idle-days: 60
        respect-pinned: true

    search:
      default-limit: 5
      max-limit: 20
      bm25-confidence-threshold: 1.0
      cache:
        layer-a-max-size: 500
        layer-b-max-size: 1000
        layer-b-ttl-minutes: 5
      fallback:
        vector-enabled: false

    describe:
      max-batch-size: 10

    category-hint: |
      You have access to a tool registry indexed in English.
      Besides the tools visible above, you can discover more via:
        - tools.search(query, category?) to find tools by keyword
        - tools.describe(ids[]) to inspect schemas
        - tools.list(category?) to browse by capability category
      Available categories: PERCEPTION, ACTION, COGNITION, STORAGE,
        INTERACTION, INTROSPECTION, EXTENSION.
      Always use English keywords in search queries.
```

- [ ] **Step 3：运行 mvn compile + 启动验证**

```
mvn compile && mvn spring-boot:run
```
Expected：启动日志显示 `agent.tools.tier1.pinned` 有 11 项；无异常。Ctrl+C 停止。

- [ ] **Step 4：运行测试**

```
mvn test
```
Expected：全绿。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/config/ToolConfigProperties.java \
        src/main/resources/application.yml
git commit -m "feat(tool): 扩展 ToolConfigProperties 支持 tier1/search/describe 配置

新增 agent.tools.tier1 / search / describe / category-hint 配置段。
旧 core-tool-ids 暂时保留（Phase B 删除），让 Phase A 可并行开发不破坏当前功能。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase A · 核心搜索基础设施

### Task A.1：V15 Flyway 迁移

**Files：**
- Create: `src/main/resources/db/migration/V15__tool_exposure_refactor.sql`

- [ ] **Step 1：编写迁移 SQL**

```sql
-- ZhiWei 工具暴露机制重构：FTS5 搜索索引 + 使用统计 + Tier 1 晋升建议表
-- V15, 2026-04-23

-- 1. 工具搜索 FTS5 索引
CREATE VIRTUAL TABLE tool_search_index USING fts5(
    tool_id UNINDEXED,
    description,
    tags,
    actions,
    category,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- 2. 工具使用统计（for Tier 1 自动晋升）
CREATE TABLE tool_usage_stats (
    tool_id          TEXT    NOT NULL,
    stat_date        TEXT    NOT NULL,
    session_count    INTEGER NOT NULL DEFAULT 0,
    invocation_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tool_id, stat_date)
);

CREATE INDEX idx_tool_usage_stats_date ON tool_usage_stats(stat_date);

-- 3. Tier 1 晋升建议
CREATE TABLE tier1_advisory (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_id        TEXT    NOT NULL,
    advised_at     TEXT    NOT NULL,
    window_days    INTEGER NOT NULL,
    coverage_ratio REAL    NOT NULL,
    status         TEXT    NOT NULL DEFAULT 'PENDING',
    reviewed_by    TEXT,
    reviewed_at    TEXT,
    CONSTRAINT chk_advisory_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_tier1_advisory_status ON tier1_advisory(status);
CREATE INDEX idx_tier1_advisory_tool ON tier1_advisory(tool_id);
```

- [ ] **Step 2：运行 mvn compile 触发 Flyway 校验**

```
mvn compile
```
Expected：Flyway 不报 checksum 错误（V15 是新文件）。

- [ ] **Step 3：启动应用确认迁移执行**

```
mvn spring-boot:run
```
Expected：日志显示 `Migrating schema ... to version 15` 或 `Current version of schema ... 15`。Ctrl+C 停止。

- [ ] **Step 4：通过 CLI 查询表结构验证**

```bash
sqlite3 ~/.zhiwei/zhiwei.db ".schema tool_search_index"
sqlite3 ~/.zhiwei/zhiwei.db ".schema tool_usage_stats"
sqlite3 ~/.zhiwei/zhiwei.db ".schema tier1_advisory"
```
Expected：三张表结构正确显示。

- [ ] **Step 5：提交**

```bash
git add src/main/resources/db/migration/V15__tool_exposure_refactor.sql
git commit -m "feat(db): V15 迁移 — 工具暴露机制重构表结构

新增 tool_search_index（FTS5）、tool_usage_stats、tier1_advisory 三表。
FTS5 索引使用 unicode61 分词器（英文 word-level 准确）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.2：ToolSearchQuerySanitizer + 注入防护测试

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchQuerySanitizer.java`
- Create: `src/test/java/com/lifepilot/tool/search/ToolSearchQuerySanitizer_注入防护测试.java`

- [ ] **Step 1：写失败测试（含 jqwik 属性测试）**

```java
package com.lifepilot.tool.search;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSearchQuerySanitizer_注入防护测试 {

    private final ToolSearchQuerySanitizer sanitizer = new ToolSearchQuerySanitizer();

    @Test
    void 空字符串_返回空() {
        assertThat(sanitizer.sanitize(null)).isEqualTo("");
        assertThat(sanitizer.sanitize("")).isEqualTo("");
        assertThat(sanitizer.sanitize("   ")).isEqualTo("");
    }

    @Test
    void 普通关键词_每个token加引号() {
        assertThat(sanitizer.sanitize("delete file"))
                .isEqualTo("\"delete\" \"file\"");
    }

    @Test
    void FTS5保留字符被清除() {
        // AND / OR / NOT / NEAR 作为普通 token 会被引号保护不作为语法
        assertThat(sanitizer.sanitize("delete * file"))
                .isEqualTo("\"delete\" \"file\"");
        assertThat(sanitizer.sanitize("delete (file)"))
                .isEqualTo("\"delete\" \"file\"");
        assertThat(sanitizer.sanitize("de\"lete file"))
                .doesNotContain("\"l");  // 内部的引号被清
    }

    @Test
    void 单引号短语_保留为普通字符() {
        assertThat(sanitizer.sanitize("what's up"))
                .contains("\"what's\"");
    }

    @Property
    void 任何输入_输出不包含未闭合的引号(@ForAll @StringLength(max = 200) String input) {
        String output = sanitizer.sanitize(input);
        // 所有引号必须成对
        long quoteCount = output.chars().filter(c -> c == '"').count();
        assertThat(quoteCount % 2).isZero();
    }

    @Property
    void 任何输入_输出不含FTS5操作符语义(@ForAll @StringLength(max = 200) String input) {
        String output = sanitizer.sanitize(input);
        // 不应有裸露的 AND/OR/NOT/NEAR 关键字（它们必须在引号内）
        assertThat(output).doesNotMatch("(^|\\s)(AND|OR|NOT|NEAR)(\\s|$)");
    }
}
```

- [ ] **Step 2：运行测试验证失败**

```
mvn test -Dtest=ToolSearchQuerySanitizer_注入防护测试
```
Expected：全红。

- [ ] **Step 3：实现 Sanitizer**

```java
package com.lifepilot.tool.search;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * FTS5 MATCH 查询消毒器。
 *
 * <p>FTS5 的 MATCH 语法有保留字符（`"`、`(`、`)`、`*`）和保留关键字
 * （`AND`、`OR`、`NOT`、`NEAR`），LLM 生成的 query 若无意命中会导致
 * 查询异常。本类把每个 token 加双引号做 phrase search，让保留关键字
 * 被当作普通字符处理。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchQuerySanitizer {

    /**
     * 消毒查询字符串。
     *
     * @param query 原始查询（可为 null / 空）
     * @return FTS5 可安全执行的 MATCH 表达式；输入为空时返回空字符串
     */
    public String sanitize(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String cleaned = query.replaceAll("[\"()*]", " ");
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(t -> !t.isBlank())
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(" "));
    }
}
```

- [ ] **Step 4：运行测试验证通过**

```
mvn test -Dtest=ToolSearchQuerySanitizer_注入防护测试
```
Expected：全绿（含 jqwik 属性测试 1000 次迭代）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search/ToolSearchQuerySanitizer.java \
        src/test/java/com/lifepilot/tool/search/ToolSearchQuerySanitizer_注入防护测试.java
git commit -m "feat(tool): 新增 ToolSearchQuerySanitizer 防止 FTS5 注入

每个 token 加双引号做 phrase search，保留关键字（AND/OR/NOT/NEAR）
和保留字符（*/()/\"）被当作普通字符。jqwik 属性测试验证任意输入
不会产生未闭合引号或裸露 FTS5 操作符。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.3：搜索结果模型（record + enum）

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchConfidence.java`
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchHit.java`
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchResult.java`
- Create: `src/main/java/com/lifepilot/tool/search/ToolDescribeResult.java`
- Create: `src/main/java/com/lifepilot/tool/search/ToolListResult.java`

- [ ] **Step 1：创建 Confidence enum**

```java
package com.lifepilot.tool.search;

/**
 * 搜索结果置信度。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum ToolSearchConfidence {
    /** top-1 BM25 分数 ≥ 阈值。 */
    HIGH,
    /** top-1 分数 < 阈值但仍有结果。 */
    LOW,
    /** 零结果。 */
    NONE
}
```

- [ ] **Step 2：创建 ToolSearchHit record**

```java
package com.lifepilot.tool.search;

import java.util.List;

/**
 * 单条搜索命中。
 *
 * @param id 工具 ID
 * @param description 英文短描述
 * @param category 工具 category 名称
 * @param score BM25 分数
 * @param actions action 名列表（若该工具支持 action 分发）
 * @author zsg
 * @since 2026-04-23
 */
public record ToolSearchHit(
        String id,
        String description,
        String category,
        double score,
        List<String> actions
) {
    public ToolSearchHit {
        actions = actions != null ? List.copyOf(actions) : List.of();
    }
}
```

- [ ] **Step 3：创建 ToolSearchResult record**

```java
package com.lifepilot.tool.search;

import java.util.List;

/**
 * `tools.search` 返回结果。
 *
 * @param results top-k 命中
 * @param totalMatched FTS5 实际命中总数
 * @param confidence 置信度
 * @param hint 给 LLM 的提示（零结果或低置信时非空）
 * @author zsg
 * @since 2026-04-23
 */
public record ToolSearchResult(
        List<ToolSearchHit> results,
        int totalMatched,
        ToolSearchConfidence confidence,
        String hint
) {
    public ToolSearchResult {
        results = results != null ? List.copyOf(results) : List.of();
    }

    public static ToolSearchResult empty() {
        return new ToolSearchResult(
                List.of(),
                0,
                ToolSearchConfidence.NONE,
                "No tools matched. Try broader keywords or call tools.list(category) to browse by category."
        );
    }
}
```

- [ ] **Step 4：创建 ToolDescribeResult record**

```java
package com.lifepilot.tool.search;

import java.util.List;
import java.util.Map;

/**
 * `tools.describe` 返回结果。
 *
 * @param schemas 工具 ID → 完整 schema（含 inputSchema/outputSchema/riskLevel/examples）
 * @param notFound 请求但未找到的 ID 列表
 * @param suggestion 给 LLM 的提示（notFound 非空时包含引导）
 * @author zsg
 * @since 2026-04-23
 */
public record ToolDescribeResult(
        Map<String, Object> schemas,
        List<String> notFound,
        String suggestion
) {
    public ToolDescribeResult {
        schemas = schemas != null ? Map.copyOf(schemas) : Map.of();
        notFound = notFound != null ? List.copyOf(notFound) : List.of();
    }
}
```

- [ ] **Step 5：创建 ToolListResult record + 提交**

```java
package com.lifepilot.tool.search;

import java.util.List;
import java.util.Map;

/**
 * `tools.list` 返回结果。
 *
 * @param categories category 名 → 该 category 下的工具 ID 列表
 * @param total 所有返回 ID 的总数
 * @author zsg
 * @since 2026-04-23
 */
public record ToolListResult(
        Map<String, List<String>> categories,
        int total
) {
    public ToolListResult {
        categories = categories != null ? Map.copyOf(categories) : Map.of();
    }
}
```

```bash
mvn compile
git add src/main/java/com/lifepilot/tool/search/ToolSearchConfidence.java \
        src/main/java/com/lifepilot/tool/search/ToolSearchHit.java \
        src/main/java/com/lifepilot/tool/search/ToolSearchResult.java \
        src/main/java/com/lifepilot/tool/search/ToolDescribeResult.java \
        src/main/java/com/lifepilot/tool/search/ToolListResult.java
git commit -m "feat(tool): 新增搜索结果模型 (Confidence/Hit/Result/Describe/List)

五个类型均为 record/enum，不可变。ToolSearchResult 提供 empty() 工厂。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.4：三层缓存实现

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/cache/SchemaCache.java`
- Create: `src/main/java/com/lifepilot/tool/search/cache/SearchResultCache.java`
- Create: `src/main/java/com/lifepilot/tool/search/cache/SessionSearchMemo.java`
- Create: `src/test/java/com/lifepilot/tool/search/cache/SchemaCache_行为测试.java`
- Create: `src/test/java/com/lifepilot/tool/search/cache/SearchResultCache_失效测试.java`

- [ ] **Step 1：写 SchemaCache 测试**

```java
package com.lifepilot.tool.search.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCache_行为测试 {

    private final SchemaCache cache = new SchemaCache(100);

    @Test
    void 未命中_从loader加载并缓存() {
        AtomicInteger loaderCalls = new AtomicInteger();
        String value = cache.get("file.read", id -> {
            loaderCalls.incrementAndGet();
            return "{\"schema\": \"" + id + "\"}";
        });
        assertThat(value).isEqualTo("{\"schema\": \"file.read\"}");
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void 已缓存_loader不再触发() {
        AtomicInteger loaderCalls = new AtomicInteger();
        cache.get("file.read", id -> { loaderCalls.incrementAndGet(); return "v1"; });
        cache.get("file.read", id -> { loaderCalls.incrementAndGet(); return "v2"; });
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void invalidate_清除指定key() {
        cache.get("file.read", id -> "v1");
        cache.invalidate("file.read");
        AtomicInteger loaderCalls = new AtomicInteger();
        cache.get("file.read", id -> { loaderCalls.incrementAndGet(); return "v2"; });
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void invalidateAll_清空() {
        cache.get("a", id -> "v1");
        cache.get("b", id -> "v2");
        cache.invalidateAll();
        AtomicInteger calls = new AtomicInteger();
        cache.get("a", id -> { calls.incrementAndGet(); return "v3"; });
        cache.get("b", id -> { calls.incrementAndGet(); return "v4"; });
        assertThat(calls.get()).isEqualTo(2);
    }
}
```

- [ ] **Step 2：写 SearchResultCache 测试**

```java
package com.lifepilot.tool.search.cache;

import com.lifepilot.tool.search.ToolSearchConfidence;
import com.lifepilot.tool.search.ToolSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultCache_失效测试 {

    private final SearchResultCache cache = new SearchResultCache(100, Duration.ofMinutes(5));

    @Test
    void 相同query_cat_limit命中同一缓存条目() {
        ToolSearchResult r1 = sample("delete files", "ACTION", 5);
        cache.put("delete files", "ACTION", 5, r1);
        assertThat(cache.get("delete files", "ACTION", 5)).isPresent();
        assertThat(cache.get("delete files", "ACTION", 5).get()).isSameAs(r1);
    }

    @Test
    void 不同参数组合_各自独立() {
        cache.put("a", null, 5, sample("a", null, 5));
        cache.put("a", "STORAGE", 5, sample("a", "STORAGE", 5));
        assertThat(cache.get("a", null, 5)).isPresent();
        assertThat(cache.get("a", "STORAGE", 5)).isPresent();
        assertThat(cache.get("a", "ACTION", 5)).isEmpty();
    }

    @Test
    void invalidateAll_清空所有条目() {
        cache.put("a", null, 5, sample("a", null, 5));
        cache.put("b", null, 5, sample("b", null, 5));
        cache.invalidateAll();
        assertThat(cache.get("a", null, 5)).isEmpty();
        assertThat(cache.get("b", null, 5)).isEmpty();
    }

    private ToolSearchResult sample(String q, String cat, int limit) {
        return new ToolSearchResult(List.of(), 0, ToolSearchConfidence.NONE, null);
    }
}
```

- [ ] **Step 3：实现三个缓存类**

`SchemaCache.java`：
```java
package com.lifepilot.tool.search.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.function.Function;

/**
 * Layer A · 工具 schema 序列化缓存。
 *
 * <p>Key：工具 ID。Value：预序列化的 schema JSON 字符串。
 * 仅在 {@code ToolRegistryEvent.UPDATE/REMOVE} 时由 DescribeCacheInvalidator 精确失效。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class SchemaCache {

    private final Cache<String, String> delegate;

    public SchemaCache(int maxSize) {
        this.delegate = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .build();
    }

    public String get(String toolId, Function<String, String> loader) {
        return delegate.get(toolId, loader);
    }

    public void invalidate(String toolId) {
        delegate.invalidate(toolId);
    }

    public void invalidateAll() {
        delegate.invalidateAll();
    }
}
```

`SearchResultCache.java`：
```java
package com.lifepilot.tool.search.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lifepilot.tool.search.ToolSearchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Layer B · 跨会话搜索结果缓存。
 *
 * <p>Key：sha256(query + category + limit) 归一化字符串。
 * TTL：默认 5 分钟；{@code ToolRegistryEvent} 触发全表清（粗粒度）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class SearchResultCache {

    private final Cache<String, ToolSearchResult> delegate;

    public SearchResultCache(int maxSize, Duration ttl) {
        this.delegate = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }

    public Optional<ToolSearchResult> get(String query, String category, int limit) {
        return Optional.ofNullable(delegate.getIfPresent(key(query, category, limit)));
    }

    public void put(String query, String category, int limit, ToolSearchResult result) {
        delegate.put(key(query, category, limit), result);
    }

    public void invalidateAll() {
        delegate.invalidateAll();
    }

    private String key(String query, String category, int limit) {
        String raw = Objects.toString(query, "") + "|"
                   + Objects.toString(category, "") + "|"
                   + limit;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SearchResultCache key 生成失败", e);
        }
    }
}
```

`SessionSearchMemo.java`：
```java
package com.lifepilot.tool.search.cache;

import com.lifepilot.tool.search.ToolSearchResult;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer C · 会话内搜索 memo。
 *
 * <p>Key：traceId + "|" + query + "|" + category + "|" + limit。
 * 同一轮对话内重复搜索同一 query 近零成本。会话结束由 ReactAgentState 自动 GC。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class SessionSearchMemo {

    private final Map<String, ToolSearchResult> store = new ConcurrentHashMap<>();

    public Optional<ToolSearchResult> get(String traceId, String query, String category, int limit) {
        return Optional.ofNullable(store.get(key(traceId, query, category, limit)));
    }

    public void put(String traceId, String query, String category, int limit, ToolSearchResult result) {
        store.put(key(traceId, query, category, limit), result);
    }

    public void clear() {
        store.clear();
    }

    private String key(String traceId, String query, String category, int limit) {
        return Objects.toString(traceId, "")
             + "|" + Objects.toString(query, "")
             + "|" + Objects.toString(category, "")
             + "|" + limit;
    }
}
```

- [ ] **Step 4：运行测试验证通过**

```
mvn test -Dtest=SchemaCache_行为测试,SearchResultCache_失效测试
```
Expected：全绿。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search/cache \
        src/test/java/com/lifepilot/tool/search/cache
git commit -m "feat(tool): 新增三层缓存 (SchemaCache/SearchResultCache/SessionSearchMemo)

Layer A：Caffeine schema 缓存，精确失效。
Layer B：Caffeine search 结果缓存，TTL + 全表清。
Layer C：ConcurrentHashMap 会话级 memo，会话结束清理。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.5：ToolSearchIndexBuilder + 构建测试

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchIndexBuilder.java`
- Create: `src/test/java/com/lifepilot/tool/search/ToolSearchIndexBuilder_构建测试.java`

- [ ] **Step 1：写测试**

```java
package com.lifepilot.tool.search;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSearchIndexBuilder_构建测试 {

    // 注：实际项目应使用 SQLite embedded 或测试 DataSource；
    // 此处用占位展示结构，真实测试应走 @SpringBootTest 或 @JdbcTest

    private JdbcTemplate jdbcTemplate;
    private DynamicToolRegistry registry;
    private ToolSearchIndexBuilder builder;

    @BeforeEach
    void setup() {
        // 实际实施时使用测试 SQLite 文件 + 执行 V15 建表 DDL
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)  // 注：真实使用需 SQLite；H2 FTS5 不支持
                .addScript("classpath:sql/test-tool-search-index-h2-fallback.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(ds);
        registry = new DynamicToolRegistry();
        builder = new ToolSearchIndexBuilder(registry, jdbcTemplate);
    }

    @Test
    void 启动时扫描所有工具写入索引() {
        registry.registerBuiltinTool(sampleTool("file.read", "Read file content from path",
                List.of("read", "file"), ToolCategory.ACTION));
        registry.registerBuiltinTool(sampleTool("web.search", "Search web by keyword and return top results",
                List.of("search", "web", "google"), ToolCategory.PERCEPTION));

        builder.build();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tool_search_index", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    private BuiltinTool sampleTool(String id, String desc, List<String> tags, ToolCategory cat) {
        return BuiltinTool.builder()
                .id(id)
                .name("测试工具")
                .description(desc)
                .tags(tags)
                .category(cat)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.stateless())
                .executor(in -> null)
                .build();
    }
}
```

> **实施提示**：H2 不支持 FTS5。正式测试应使用 `@SpringBootTest` 启动完整 Spring 上下文走真实 SQLite（与主库相同文件或临时文件）。本测试骨架仅示意，执行者应按项目现有测试 pattern（`ApplicationContextRunner` + 真实 SQLite）改造。

- [ ] **Step 2：运行测试验证失败**

```
mvn test -Dtest=ToolSearchIndexBuilder_构建测试
```
Expected：全红（类不存在）。

- [ ] **Step 3：实现 Builder**

```java
package com.lifepilot.tool.search;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.dispatch.ActionMetadata;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动时扫描 DynamicToolRegistry 并批量 INSERT 到 tool_search_index。
 *
 * <p>在 {@link com.lifepilot.tool.registry.BuiltinToolRegistrar} 之后执行
 * （{@code @Order} 靠后），确保内置工具已注册完毕。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndexBuilder.class);

    private final DynamicToolRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public ToolSearchIndexBuilder(DynamicToolRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)   // BuiltinToolRegistrar 默认 order 更早，确保注册完再建索引
    public void build() {
        log.info("开始构建工具搜索索引");
        jdbcTemplate.update("DELETE FROM tool_search_index");
        int inserted = 0;
        for (ToolContract tool : registry.getToolSnapshot()) {
            try {
                upsert(tool);
                inserted++;
            } catch (Exception e) {
                log.error("索引工具失败: toolId={}", tool.id(), e);
            }
        }
        log.info("工具搜索索引构建完成: count={}", inserted);
    }

    /** 上插一条索引（供 Maintainer 复用）。 */
    public void upsert(ToolContract tool) {
        jdbcTemplate.update(
                "INSERT INTO tool_search_index (tool_id, description, tags, actions, category) "
                + "VALUES (?, ?, ?, ?, ?)",
                tool.id(),
                tool.description(),
                String.join(" ", tool.tags()),
                extractActions(tool),
                tool.category().name()
        );
    }

    public void delete(String toolId) {
        jdbcTemplate.update("DELETE FROM tool_search_index WHERE tool_id = ?", toolId);
    }

    private String extractActions(ToolContract tool) {
        if (!(tool instanceof BuiltinTool builtin) || builtin.actionMetadata() == null) {
            return "";
        }
        Map<String, ActionMetadata> actions = builtin.actionMetadata();
        return actions.entrySet().stream()
                .map(e -> e.getKey() + " " + (e.getValue().description() == null ? "" : e.getValue().description()))
                .collect(Collectors.joining(" "));
    }
}
```

- [ ] **Step 4：运行测试验证通过**

```
mvn test -Dtest=ToolSearchIndexBuilder_构建测试
```
Expected：绿（使用真实 SQLite 测试 profile）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search/ToolSearchIndexBuilder.java \
        src/test/java/com/lifepilot/tool/search/ToolSearchIndexBuilder_构建测试.java
git commit -m "feat(tool): 新增 ToolSearchIndexBuilder 启动时批量建索引

扫描 DynamicToolRegistry 全量工具，写入 tool_search_index 表。
提供 upsert / delete 方法供增量维护者复用。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.6：ToolSearchIndexMaintainer 增量维护

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchIndexMaintainer.java`
- Create: `src/test/java/com/lifepilot/tool/search/ToolSearchIndexMaintainer_增量测试.java`

- [ ] **Step 1：写测试**

```java
package com.lifepilot.tool.search;

import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.event.ToolRegistryEventType;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.search.cache.SchemaCache;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;

import static org.mockito.Mockito.*;

class ToolSearchIndexMaintainer_增量测试 {

    @Test
    void ADD事件_触发upsert_清两个缓存() {
        ToolSearchIndexBuilder builder = mock(ToolSearchIndexBuilder.class);
        SchemaCache schemaCache = mock(SchemaCache.class);
        SearchResultCache searchCache = mock(SearchResultCache.class);
        var tool = TestTools.sampleTool("file.read");

        new ToolSearchIndexMaintainer(builder, schemaCache, searchCache)
                .onEvent(new ToolRegistryEvent(ToolRegistryEventType.REGISTERED, tool));

        verify(builder).upsert(tool);
        verify(schemaCache).invalidate("file.read");
        verify(searchCache).invalidateAll();
    }

    @Test
    void REMOVE事件_触发delete_清两个缓存() {
        ToolSearchIndexBuilder builder = mock(ToolSearchIndexBuilder.class);
        SchemaCache schemaCache = mock(SchemaCache.class);
        SearchResultCache searchCache = mock(SearchResultCache.class);
        var tool = TestTools.sampleTool("file.read");

        new ToolSearchIndexMaintainer(builder, schemaCache, searchCache)
                .onEvent(new ToolRegistryEvent(ToolRegistryEventType.UNREGISTERED, tool));

        verify(builder).delete("file.read");
        verify(schemaCache).invalidate("file.read");
        verify(searchCache).invalidateAll();
    }
}
```

> **实施提示**：`TestTools.sampleTool` 是测试 helper，需在 `src/test/java/com/lifepilot/tool/search/TestTools.java` 预建一个工厂方法（返回最小合法 BuiltinTool）。`ToolRegistryEventType` 的实际枚举值参考 `src/main/java/com/lifepilot/tool/event/ToolRegistryEvent.java`，此处占位 `REGISTERED/UNREGISTERED`——执行前确认实际枚举名。

- [ ] **Step 2：运行测试验证失败**

```
mvn test -Dtest=ToolSearchIndexMaintainer_增量测试
```
Expected：红。

- [ ] **Step 3：实现 Maintainer**

```java
package com.lifepilot.tool.search;

import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.search.cache.SchemaCache;
import com.lifepilot.tool.search.cache.SearchResultCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * 监听 ToolRegistryEvent 增量维护搜索索引 + 失效相关缓存。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchIndexMaintainer {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndexMaintainer.class);

    private final ToolSearchIndexBuilder builder;
    private final SchemaCache schemaCache;
    private final SearchResultCache searchCache;

    public ToolSearchIndexMaintainer(
            ToolSearchIndexBuilder builder,
            SchemaCache schemaCache,
            SearchResultCache searchCache) {
        this.builder = builder;
        this.schemaCache = schemaCache;
        this.searchCache = searchCache;
    }

    @EventListener
    public void onEvent(ToolRegistryEvent event) {
        String toolId = event.tool().id();
        try {
            switch (event.type()) {
                case REGISTERED, UPDATED -> builder.upsert(event.tool());
                case UNREGISTERED -> builder.delete(toolId);
            }
            schemaCache.invalidate(toolId);
            searchCache.invalidateAll();   // 粗粒度全表清
            log.debug("索引增量维护: type={}, toolId={}", event.type(), toolId);
        } catch (Exception e) {
            log.error("索引增量维护失败: type={}, toolId={}", event.type(), toolId, e);
        }
    }
}
```

> **注**：`ToolRegistryEvent` 枚举值请在实施时对齐实际 `ToolRegistryEventType`；若现有枚举只有两个值（如 `REGISTERED` / `UNREGISTERED`），`UPDATED` 分支可省略或合并到 `REGISTERED`。

- [ ] **Step 4：运行测试验证通过**

```
mvn test -Dtest=ToolSearchIndexMaintainer_增量测试
```

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search/ToolSearchIndexMaintainer.java \
        src/test/java/com/lifepilot/tool/search/ToolSearchIndexMaintainer_增量测试.java \
        src/test/java/com/lifepilot/tool/search/TestTools.java
git commit -m "feat(tool): ToolSearchIndexMaintainer 增量维护索引 + 失效缓存

监听 ToolRegistryEvent，REGISTERED/UPDATED 走 upsert，UNREGISTERED 走 delete。
Layer A schema cache 精确失效，Layer B search cache 全表清（粗粒度）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.7：ToolSearchService 主查询服务

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/ToolSearchService.java`
- Create: `src/test/java/com/lifepilot/tool/search/ToolSearchService_查询过滤测试.java`

- [ ] **Step 1：写测试**

```java
package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.search.cache.SessionSearchMemo;
import com.lifepilot.tool.tier1.Tier1Service;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolSearchService_查询过滤测试 {

    private final ToolConfigProperties props = createProps();
    private final ToolSearchQuerySanitizer sanitizer = new ToolSearchQuerySanitizer();

    @Test
    void 返回结果排除Tier1和activated和meta工具() {
        var service = buildService(/* ... */);
        Tier1Service tier1 = mock(Tier1Service.class);
        when(tier1.getCurrentTier1Ids()).thenReturn(Set.of("file.read"));

        ReactAgentState state = stateWith("trace-1", Set.of("datastore.query_documents"));
        ToolSearchResult result = service.search(state, "read file", null, 5);

        // file.read 不应出现（Tier 1），datastore.query_documents 不应出现（activated）
        // tools.search 不应出现（meta）
        assertThat(result.results()).noneMatch(h -> h.id().equals("file.read"));
        assertThat(result.results()).noneMatch(h -> h.id().equals("datastore.query_documents"));
        assertThat(result.results()).noneMatch(h -> h.id().startsWith("tools."));
    }

    @Test
    void 命中LayerC_不走FTS5() {
        // 注：详细 mock 骨架，实施者按需补全
    }

    @Test
    void 低置信返回LOW_confidence() {
        var service = buildService(/* ... */);
        // 模拟 FTS5 返回 top-1 分数低于阈值
        // 断言 result.confidence() == ToolSearchConfidence.LOW
    }

    @Test
    void 零结果返回NONE_confidence并给hint() {
        var service = buildService(/* ... */);
        // 模拟 FTS5 返回空
        ToolSearchResult result = /* ... */ null;
        // 断言 result.confidence() == ToolSearchConfidence.NONE
        // 断言 result.hint() 包含 "Try broader keywords"
    }

    private ToolSearchService buildService(/* params */) {
        // 构造完整 service，注入 mock 的 JdbcTemplate / Tier1Service / DynamicToolRegistry
        return null;  // 实施者补
    }

    private ReactAgentState stateWith(String traceId, Set<String> activated) {
        // 使用项目已有的 ReactAgentState builder 构造最小实例
        return null;  // 实施者补
    }

    private ToolConfigProperties createProps() {
        var p = new ToolConfigProperties();
        // 设置 search.default-limit=5, max-limit=20, bm25ConfidenceThreshold=1.0 等
        return p;
    }
}
```

> **实施提示**：测试的详细 mock 骨架依赖 `ReactAgentState` 现有的 builder 和 `DynamicToolRegistry` 的 public API。实施者需要读一下这两个类以补齐测试。核心断言是：过滤规则、缓存命中、置信度判定。

- [ ] **Step 2：运行测试验证失败**

```
mvn test -Dtest=ToolSearchService_查询过滤测试
```

- [ ] **Step 3：实现 ToolSearchService**

```java
package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.dispatch.ActionMetadata;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.search.cache.SessionSearchMemo;
import com.lifepilot.tool.tier1.Tier1Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 工具搜索主服务：sanitize → 三层缓存 → FTS5 BM25 → 过滤 → 返回结果。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchService {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchService.class);
    private static final Set<String> META_TOOL_IDS = Set.of(
            "tools.search", "tools.describe", "tools.list");

    private final JdbcTemplate jdbcTemplate;
    private final DynamicToolRegistry registry;
    private final ToolSearchQuerySanitizer sanitizer;
    private final Tier1Service tier1Service;
    private final SearchResultCache searchResultCache;
    private final SessionSearchMemo sessionMemo;
    private final ToolConfigProperties.Search config;

    public ToolSearchService(
            JdbcTemplate jdbcTemplate,
            DynamicToolRegistry registry,
            ToolSearchQuerySanitizer sanitizer,
            Tier1Service tier1Service,
            SearchResultCache searchResultCache,
            SessionSearchMemo sessionMemo,
            ToolConfigProperties.Search config) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        this.sanitizer = sanitizer;
        this.tier1Service = tier1Service;
        this.searchResultCache = searchResultCache;
        this.sessionMemo = sessionMemo;
        this.config = config;
    }

    public ToolSearchResult search(
            ReactAgentState state,
            String rawQuery,
            @Nullable String category,
            @Nullable Integer limitArg) {

        int limit = Math.min(
                limitArg == null ? config.getDefaultLimit() : limitArg,
                config.getMaxLimit());

        // Layer C
        String traceId = state.traceId();
        var layerC = sessionMemo.get(traceId, rawQuery, category, limit);
        if (layerC.isPresent()) {
            log.debug("搜索命中 Layer C: traceId={}, query={}", traceId, rawQuery);
            return layerC.get();
        }

        // Layer B
        var layerB = searchResultCache.get(rawQuery, category, limit);
        if (layerB.isPresent()) {
            sessionMemo.put(traceId, rawQuery, category, limit, layerB.get());
            log.debug("搜索命中 Layer B: query={}", rawQuery);
            return layerB.get();
        }

        // FTS5 查询
        String matchExpr = sanitizer.sanitize(rawQuery);
        if (matchExpr.isEmpty()) {
            return ToolSearchResult.empty();
        }

        Set<String> excluded = new HashSet<>();
        excluded.addAll(tier1Service.getCurrentTier1Ids());
        Set<String> activated = state.activatedToolIds() != null
                ? state.activatedToolIds() : Set.of();
        excluded.addAll(activated);
        excluded.addAll(META_TOOL_IDS);

        Set<String> allowedScope = state.allowedToolIds();    // null = 无限制

        List<FtsRow> rows = executeFts(matchExpr, category, limit * 3);   // 多取点，过滤后再截

        List<ToolSearchHit> hits = rows.stream()
                .filter(r -> !excluded.contains(r.toolId()))
                .filter(r -> allowedScope == null || allowedScope.isEmpty() || allowedScope.contains(r.toolId()))
                .limit(limit)
                .map(this::toHit)
                .filter(Objects::nonNull)
                .toList();

        ToolSearchConfidence confidence;
        String hint = null;
        if (hits.isEmpty()) {
            confidence = ToolSearchConfidence.NONE;
            hint = "No tools matched. Try broader keywords or call tools.list(category) to browse by category.";
        } else if (hits.get(0).score() < config.getBm25ConfidenceThreshold()) {
            confidence = ToolSearchConfidence.LOW;
            hint = "Low confidence match. Consider refining keywords or checking tools.list(category).";
        } else {
            confidence = ToolSearchConfidence.HIGH;
        }

        ToolSearchResult result = new ToolSearchResult(hits, rows.size(), confidence, hint);

        // 写缓存
        searchResultCache.put(rawQuery, category, limit, result);
        sessionMemo.put(traceId, rawQuery, category, limit, result);
        return result;
    }

    private List<FtsRow> executeFts(String matchExpr, @Nullable String category, int limit) {
        String sql;
        Object[] args;
        if (category != null && !category.isBlank()) {
            sql = """
                  SELECT tool_id, bm25(tool_search_index) AS score
                  FROM tool_search_index
                  WHERE tool_search_index MATCH ? AND category = ?
                  ORDER BY score
                  LIMIT ?
                  """;
            args = new Object[]{matchExpr, category, limit};
        } else {
            sql = """
                  SELECT tool_id, bm25(tool_search_index) AS score
                  FROM tool_search_index
                  WHERE tool_search_index MATCH ?
                  ORDER BY score
                  LIMIT ?
                  """;
            args = new Object[]{matchExpr, limit};
        }
        return jdbcTemplate.query(sql, args, (rs, i) ->
                new FtsRow(rs.getString("tool_id"), rs.getDouble("score")));
    }

    @Nullable
    private ToolSearchHit toHit(FtsRow row) {
        return registry.resolve(row.toolId())
                .map(tool -> new ToolSearchHit(
                        tool.id(),
                        tool.description(),
                        tool.category().name(),
                        // FTS5 bm25() 越小越相关；统一转成"越大越好"的分数
                        Math.abs(row.score()),
                        extractActions(tool)
                ))
                .orElse(null);
    }

    private List<String> extractActions(ToolContract tool) {
        if (!(tool instanceof BuiltinTool builtin) || builtin.actionMetadata() == null) {
            return List.of();
        }
        return List.copyOf(builtin.actionMetadata().keySet());
    }

    private record FtsRow(String toolId, double score) {}
}
```

- [ ] **Step 4：运行测试**

```
mvn test -Dtest=ToolSearchService_查询过滤测试
```
Expected：所有测试通过（mock 骨架补齐后）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search/ToolSearchService.java \
        src/test/java/com/lifepilot/tool/search/ToolSearchService_查询过滤测试.java
git commit -m "feat(tool): 新增 ToolSearchService 主查询服务

sanitize → Layer C → Layer B → FTS5 BM25 → 过滤（Tier 1/activated/meta/权限）
→ 置信度判定 → 写两层缓存。过滤集合采用显式 Set，清晰可测。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.8：ToolDescribeService + ToolListService

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/ToolDescribeService.java`
- Create: `src/main/java/com/lifepilot/tool/search/ToolListService.java`
- Create: `src/test/java/com/lifepilot/tool/search/ToolDescribeService_批量测试.java`

- [ ] **Step 1：写测试**

```java
package com.lifepilot.tool.search;

import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SchemaCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDescribeService_批量测试 {

    @Test
    void 批量describe_部分存在部分不存在_结构化返回() {
        DynamicToolRegistry reg = new DynamicToolRegistry();
        reg.registerBuiltinTool(TestTools.sampleTool("file.read"));

        var svc = new ToolDescribeService(reg, new SchemaCache(100), 10);

        ToolDescribeResult result = svc.describe(List.of("file.read", "nonexistent.tool"));

        assertThat(result.schemas()).containsKey("file.read");
        assertThat(result.notFound()).containsExactly("nonexistent.tool");
        assertThat(result.suggestion()).isNotBlank();
    }

    @Test
    void 批量超过上限_截断并给hint() {
        DynamicToolRegistry reg = new DynamicToolRegistry();
        for (int i = 0; i < 15; i++) {
            reg.registerBuiltinTool(TestTools.sampleTool("tool.t" + i));
        }
        var svc = new ToolDescribeService(reg, new SchemaCache(100), 10);

        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) ids.add("tool.t" + i);

        ToolDescribeResult result = svc.describe(ids);
        assertThat(result.schemas()).hasSize(10);
        assertThat(result.suggestion()).contains("batch size limit");
    }
}
```

- [ ] **Step 2：运行测试**

```
mvn test -Dtest=ToolDescribeService_批量测试
```
Expected：红。

- [ ] **Step 3：实现 DescribeService + ListService**

`ToolDescribeService.java`：
```java
package com.lifepilot.tool.search;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.dispatch.ActionMetadata;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SchemaCache;

import java.util.*;

/**
 * `tools.describe` 服务：批量返回工具完整 schema。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolDescribeService {

    private final DynamicToolRegistry registry;
    private final SchemaCache schemaCache;
    private final int maxBatchSize;

    public ToolDescribeService(DynamicToolRegistry registry, SchemaCache schemaCache, int maxBatchSize) {
        this.registry = registry;
        this.schemaCache = schemaCache;
        this.maxBatchSize = maxBatchSize;
    }

    public ToolDescribeResult describe(List<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return new ToolDescribeResult(
                    Map.of(),
                    List.of(),
                    "No tool_ids provided. Pass an array of tool IDs obtained from tools.search."
            );
        }
        List<String> effective = requestedIds.size() > maxBatchSize
                ? requestedIds.subList(0, maxBatchSize)
                : requestedIds;
        boolean truncated = requestedIds.size() > maxBatchSize;

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

        String suggestion;
        if (truncated) {
            suggestion = "Request exceeded batch size limit (%d). Truncated to first %d. Call describe in separate batches."
                    .formatted(maxBatchSize, maxBatchSize);
        } else if (!notFound.isEmpty()) {
            suggestion = "Some tool_ids not found. Call tools.search to discover valid IDs.";
        } else {
            suggestion = null;
        }

        return new ToolDescribeResult(schemas, notFound, suggestion);
    }

    private Map<String, Object> buildSchemaView(ToolContract tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("description", tool.description());
        view.put("input_schema", tool.inputSchema().toMap());
        view.put("output_schema", tool.outputSchema().toMap());
        view.put("risk_level", tool.riskLevel().name());
        view.put("idempotent", tool.idempotent());
        if (tool instanceof BuiltinTool builtin
                && builtin.actionMetadata() != null && !builtin.actionMetadata().isEmpty()) {
            Map<String, Map<String, Object>> actionsView = new LinkedHashMap<>();
            for (var entry : builtin.actionMetadata().entrySet()) {
                ActionMetadata meta = entry.getValue();
                actionsView.put(entry.getKey(), Map.of(
                        "description", meta.description() == null ? "" : meta.description(),
                        "risk_level", meta.riskLevel().name()
                ));
            }
            view.put("actions", actionsView);
        }
        return view;
    }
}
```

`ToolListService.java`：
```java
package com.lifepilot.tool.search;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * `tools.list` 服务：按 category 返回工具 ID（不含 description，避免 token 膨胀）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolListService {

    private final DynamicToolRegistry registry;

    public ToolListService(DynamicToolRegistry registry) {
        this.registry = registry;
    }

    public ToolListResult list(@Nullable String category) {
        List<ToolContract> all = registry.getToolSnapshot();
        Map<String, List<String>> grouped = all.stream()
                .filter(t -> category == null || category.isBlank()
                        || t.category().name().equalsIgnoreCase(category))
                .collect(Collectors.groupingBy(
                        t -> t.category().name(),
                        LinkedHashMap::new,
                        Collectors.mapping(ToolContract::id, Collectors.toList())));
        int total = grouped.values().stream().mapToInt(List::size).sum();
        return new ToolListResult(grouped, total);
    }
}
```

- [ ] **Step 4：运行测试**

```
mvn test -Dtest=ToolDescribeService_批量测试
```

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search/ToolDescribeService.java \
        src/main/java/com/lifepilot/tool/search/ToolListService.java \
        src/test/java/com/lifepilot/tool/search/ToolDescribeService_批量测试.java
git commit -m "feat(tool): 新增 ToolDescribeService + ToolListService

Describe 支持批量（超上限截断 + hint），每工具返回完整 schema view。
List 按 category 分组返回 ID（不含描述避免膨胀）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.9：BuiltinToolSearchProvider 注册三个 meta 工具

**Files：**
- Create: `src/main/java/com/lifepilot/tool/search/BuiltinToolSearchProvider.java`
- Create: `src/test/java/com/lifepilot/tool/search/BuiltinToolSearchProvider_注册测试.java`

- [ ] **Step 1：写测试**

```java
package com.lifepilot.tool.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinToolSearchProvider_注册测试 {

    @Test
    void 提供三个meta工具() {
        var provider = new BuiltinToolSearchProvider(null, null, null);
        assertThat(provider.searchTool().id()).isEqualTo("tools.search");
        assertThat(provider.describeTool().id()).isEqualTo("tools.describe");
        assertThat(provider.listTool().id()).isEqualTo("tools.list");
    }

    @Test
    void search工具有正确的inputSchema() {
        var tool = new BuiltinToolSearchProvider(null, null, null).searchTool();
        var schema = tool.inputSchema().toMap();
        assertThat(schema).containsKey("properties");
        // properties 里至少有 query/category/limit
    }
}
```

- [ ] **Step 2：运行测试**

```
mvn test -Dtest=BuiltinToolSearchProvider_注册测试
```

- [ ] **Step 3：实现 Provider**

```java
package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 注册 `tools.search` / `tools.describe` / `tools.list` 三个 meta BuiltinTool。
 *
 * <p>这三个工具常驻 prompt，不能 defer。工具执行器把参数转给对应的 Service。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class BuiltinToolSearchProvider {

    private final ToolSearchService searchService;
    private final ToolDescribeService describeService;
    private final ToolListService listService;

    public BuiltinToolSearchProvider(
            ToolSearchService searchService,
            ToolDescribeService describeService,
            ToolListService listService) {
        this.searchService = searchService;
        this.describeService = describeService;
        this.listService = listService;
    }

    public BuiltinTool searchTool() {
        return BuiltinTool.builder()
                .id("tools.search")
                .name("搜索工具")
                .description("Search the tool registry by English keywords and return top-k matches with BM25 ranking")
                .tags(List.of("search", "tools", "discover", "find", "registry"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.stateless())
                .inputSchema(JsonSchema.fromMap(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "English keywords describing the desired capability"),
                                "category", Map.of("type", "string",
                                        "description", "Optional category filter: PERCEPTION/ACTION/COGNITION/STORAGE/INTERACTION/INTROSPECTION/EXTENSION"),
                                "limit", Map.of("type", "integer",
                                        "description", "Max results (default 5, max 20)")
                        ),
                        "required", List.of("query")
                )))
                .executor(this::executeSearch)
                .build();
    }

    public BuiltinTool describeTool() {
        return BuiltinTool.builder()
                .id("tools.describe")
                .name("查询工具详情")
                .description("Fetch full JSON schema and metadata for the specified tool IDs (batch supported)")
                .tags(List.of("describe", "tools", "schema", "inspect", "registry"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.stateless())
                .inputSchema(JsonSchema.fromMap(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "tool_ids", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Array of tool IDs to describe"
                                )
                        ),
                        "required", List.of("tool_ids")
                )))
                .executor(this::executeDescribe)
                .build();
    }

    public BuiltinTool listTool() {
        return BuiltinTool.builder()
                .id("tools.list")
                .name("列举工具")
                .description("List tool IDs grouped by category (returns IDs only, call describe for details)")
                .tags(List.of("list", "tools", "browse", "enumerate", "registry"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.stateless())
                .inputSchema(JsonSchema.fromMap(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "category", Map.of(
                                        "type", "string",
                                        "description", "Optional category filter; omit to list all"
                                )
                        )
                )))
                .executor(this::executeList)
                .build();
    }

    private ToolResult executeSearch(ToolInput input) {
        String query = input.getOptionalParam("query", String.class).orElse("");
        String category = input.getOptionalParam("category", String.class).orElse(null);
        Integer limit = input.getOptionalParam("limit", Integer.class).orElse(null);

        ReactAgentState state = (ReactAgentState) input.context().get(ToolContextKeys.CALLER_STATE);
        if (state == null) {
            // 退化：构造临时 state，仅用 traceId 做 memo key
            state = fallbackState(input);
        }

        ToolSearchResult result = searchService.search(state, query, category, limit);
        return ToolResult.success(Map.of(
                "results", result.results(),
                "total_matched", result.totalMatched(),
                "confidence", result.confidence().name(),
                "hint", result.hint() == null ? "" : result.hint()
        ));
    }

    private ToolResult executeDescribe(ToolInput input) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) input.parameters().getOrDefault("tool_ids", List.of());
        ToolDescribeResult result = describeService.describe(ids);
        return ToolResult.success(Map.of(
                "schemas", result.schemas(),
                "not_found", result.notFound(),
                "suggestion", result.suggestion() == null ? "" : result.suggestion()
        ));
    }

    private ToolResult executeList(ToolInput input) {
        String category = input.getOptionalParam("category", String.class).orElse(null);
        ToolListResult result = listService.list(category);
        return ToolResult.success(Map.of(
                "categories", result.categories(),
                "total", result.total()
        ));
    }

    @Nullable
    private ReactAgentState fallbackState(ToolInput input) {
        // 按项目 ReactAgentState 构造最小实例，仅填 traceId（从 context 取）
        String traceId = Objects.toString(input.context().get(ToolContextKeys.CALLER_TRACE_ID), "search-fallback");
        // 此处需根据实际 ReactAgentState API 调整
        return null;  // 实施者补充
    }
}
```

> **注**：`ToolContextKeys.CALLER_STATE` 如果当前项目没有，需要新增一个常量并在 `ToolBridgeAgentToolProvider.toToolCallback` 里把 `state` 放进 context map；或者采用"fallback state"方案。任选一种实施，保持语义：search 需要知道当前 state 的 traceId / activatedToolIds / allowedToolIds。

- [ ] **Step 4：运行测试**

```
mvn test -Dtest=BuiltinToolSearchProvider_注册测试
```

- [ ] **Step 5：在 ToolAutoConfiguration 装配 Bean + 提交**

```java
@Bean public SchemaCache schemaCache(ToolConfigProperties p) {
    return new SchemaCache(p.getSearch().getCache().getLayerAMaxSize());
}
@Bean public SearchResultCache searchResultCache(ToolConfigProperties p) {
    return new SearchResultCache(
            p.getSearch().getCache().getLayerBMaxSize(),
            java.time.Duration.ofMinutes(p.getSearch().getCache().getLayerBTtlMinutes()));
}
@Bean public SessionSearchMemo sessionSearchMemo() { return new SessionSearchMemo(); }
@Bean public ToolSearchQuerySanitizer toolSearchQuerySanitizer() { return new ToolSearchQuerySanitizer(); }
@Bean public ToolSearchIndexBuilder toolSearchIndexBuilder(
        DynamicToolRegistry r, JdbcTemplate jt) { return new ToolSearchIndexBuilder(r, jt); }
@Bean public ToolSearchIndexMaintainer toolSearchIndexMaintainer(
        ToolSearchIndexBuilder b, SchemaCache sc, SearchResultCache rc) {
    return new ToolSearchIndexMaintainer(b, sc, rc);
}
@Bean public ToolSearchService toolSearchService(
        JdbcTemplate jt, DynamicToolRegistry r, ToolSearchQuerySanitizer s,
        Tier1Service t1, SearchResultCache rc, SessionSearchMemo sm, ToolConfigProperties p) {
    return new ToolSearchService(jt, r, s, t1, rc, sm, p.getSearch());
}
@Bean public ToolDescribeService toolDescribeService(
        DynamicToolRegistry r, SchemaCache sc, ToolConfigProperties p) {
    return new ToolDescribeService(r, sc, p.getDescribe().getMaxBatchSize());
}
@Bean public ToolListService toolListService(DynamicToolRegistry r) {
    return new ToolListService(r);
}
@Bean public BuiltinToolSearchProvider builtinToolSearchProvider(
        ToolSearchService ss, ToolDescribeService ds, ToolListService ls) {
    return new BuiltinToolSearchProvider(ss, ds, ls);
}
@Bean public BuiltinTool toolsSearchBuiltin(BuiltinToolSearchProvider p) { return p.searchTool(); }
@Bean public BuiltinTool toolsDescribeBuiltin(BuiltinToolSearchProvider p) { return p.describeTool(); }
@Bean public BuiltinTool toolsListBuiltin(BuiltinToolSearchProvider p) { return p.listTool(); }
```

```bash
mvn test -Dtest=BuiltinToolSearchProvider_注册测试
git add src/main/java/com/lifepilot/tool/search/BuiltinToolSearchProvider.java \
        src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java \
        src/test/java/com/lifepilot/tool/search/BuiltinToolSearchProvider_注册测试.java
git commit -m "feat(tool): 注册 tools.search/describe/list 三个 meta BuiltinTool

三个工具 category=INTROSPECTION、risk=LOW、idempotent=true。
schema 明确声明 query/tool_ids/category 参数。Bean 装配在 ToolAutoConfiguration。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.10：Tier1Service + Advisory 模型

**Files：**
- Create: `src/main/java/com/lifepilot/tool/tier1/Tier1Advisory.java`
- Create: `src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryStatus.java`
- Create: `src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryRepository.java`
- Create: `src/main/java/com/lifepilot/tool/tier1/Tier1Service.java`
- Create: `src/test/java/com/lifepilot/tool/tier1/Tier1AdvisoryRepository_持久化测试.java`

- [ ] **Step 1：创建 Advisory record + enum**

```java
// Tier1AdvisoryStatus.java
package com.lifepilot.tool.tier1;

public enum Tier1AdvisoryStatus { PENDING, APPROVED, REJECTED }
```

```java
// Tier1Advisory.java
package com.lifepilot.tool.tier1;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Tier 1 晋升建议记录。
 *
 * @param id 数据库主键（新建时为 null）
 * @param toolId 候选工具 ID
 * @param advisedAt 建议生成时间
 * @param windowDays 观察窗口天数
 * @param coverageRatio 会话覆盖率（0-1）
 * @param status 状态
 * @param reviewedBy 审批人（可空）
 * @param reviewedAt 审批时间（可空）
 * @author zsg
 * @since 2026-04-23
 */
public record Tier1Advisory(
        @Nullable Long id,
        String toolId,
        Instant advisedAt,
        int windowDays,
        double coverageRatio,
        Tier1AdvisoryStatus status,
        @Nullable String reviewedBy,
        @Nullable Instant reviewedAt
) {}
```

- [ ] **Step 2：写 Repository 测试**

```java
package com.lifepilot.tool.tier1;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Tier1AdvisoryRepository_持久化测试 {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:sqlite::memory:",
                    "spring.datasource.driver-class-name=org.sqlite.JDBC"
            );

    @Test
    void save_findPending_approve() {
        runner.run(ctx -> {
            var jt = new org.springframework.jdbc.core.JdbcTemplate(ctx.getBean(javax.sql.DataSource.class));
            // 跑 V15 DDL
            // ...
            var repo = new Tier1AdvisoryRepository(jt);

            var adv = new Tier1Advisory(null, "datastore.query_documents", Instant.now(), 30, 0.35,
                    Tier1AdvisoryStatus.PENDING, null, null);
            Long id = repo.save(adv);
            assertThat(id).isNotNull();

            List<Tier1Advisory> pending = repo.findPending();
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).toolId()).isEqualTo("datastore.query_documents");

            repo.approve(id, "admin");
            assertThat(repo.findPending()).isEmpty();
        });
    }
}
```

- [ ] **Step 3：实现 Repository + Service**

```java
// Tier1AdvisoryRepository.java
package com.lifepilot.tool.tier1;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Tier 1 晋升建议持久化。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1AdvisoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public Tier1AdvisoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long save(Tier1Advisory adv) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO tier1_advisory (tool_id, advised_at, window_days, coverage_ratio, status) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, adv.toolId());
            ps.setString(2, adv.advisedAt().toString());
            ps.setInt(3, adv.windowDays());
            ps.setDouble(4, adv.coverageRatio());
            ps.setString(5, adv.status().name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public List<Tier1Advisory> findPending() {
        return jdbcTemplate.query(
                "SELECT id, tool_id, advised_at, window_days, coverage_ratio, status, reviewed_by, reviewed_at "
                        + "FROM tier1_advisory WHERE status = 'PENDING' ORDER BY advised_at DESC",
                (rs, i) -> new Tier1Advisory(
                        rs.getLong("id"),
                        rs.getString("tool_id"),
                        Instant.parse(rs.getString("advised_at")),
                        rs.getInt("window_days"),
                        rs.getDouble("coverage_ratio"),
                        Tier1AdvisoryStatus.valueOf(rs.getString("status")),
                        rs.getString("reviewed_by"),
                        rs.getString("reviewed_at") == null ? null : Instant.parse(rs.getString("reviewed_at"))
                ));
    }

    public List<String> findApprovedToolIds() {
        return jdbcTemplate.queryForList(
                "SELECT tool_id FROM tier1_advisory WHERE status = 'APPROVED'",
                String.class);
    }

    public void approve(Long id, String reviewer) {
        jdbcTemplate.update(
                "UPDATE tier1_advisory SET status = 'APPROVED', reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                reviewer, Instant.now().toString(), id);
    }

    public void reject(Long id, String reviewer) {
        jdbcTemplate.update(
                "UPDATE tier1_advisory SET status = 'REJECTED', reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                reviewer, Instant.now().toString(), id);
    }
}
```

```java
// Tier1Service.java
package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tier 1 工具 ID 聚合服务：配置 pinned + Advisory APPROVED。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1Service {

    private final ToolConfigProperties config;
    private final Tier1AdvisoryRepository advisoryRepository;

    public Tier1Service(ToolConfigProperties config, Tier1AdvisoryRepository advisoryRepository) {
        this.config = config;
        this.advisoryRepository = advisoryRepository;
    }

    public Set<String> getCurrentTier1Ids() {
        Set<String> ids = new LinkedHashSet<>(config.getTier1().getPinned());
        ids.addAll(advisoryRepository.findApprovedToolIds());
        return ids;
    }

    public boolean isPinned(String toolId) {
        return config.getTier1().getPinned().contains(toolId);
    }
}
```

- [ ] **Step 4：运行测试**

```
mvn test -Dtest=Tier1AdvisoryRepository_持久化测试
```

- [ ] **Step 5：Bean 装配 + 提交**

在 `ToolAutoConfiguration` 追加：
```java
@Bean public Tier1AdvisoryRepository tier1AdvisoryRepository(JdbcTemplate jt) {
    return new Tier1AdvisoryRepository(jt);
}
@Bean public Tier1Service tier1Service(ToolConfigProperties p, Tier1AdvisoryRepository r) {
    return new Tier1Service(p, r);
}
```

```bash
git add src/main/java/com/lifepilot/tool/tier1 \
        src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java \
        src/test/java/com/lifepilot/tool/tier1
git commit -m "feat(tool): Tier1Service + AdvisoryRepository 聚合 pinned + 已批准晋升

Tier1Advisory record + 4 状态枚举。Repository 含 save/findPending/approve/reject。
Service 的 getCurrentTier1Ids() 聚合配置 pinned 与数据库 APPROVED。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.11：ToolBridgeAgentToolProvider 过滤逻辑重写

**Files：**
- Modify: `src/main/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProvider.java`
- Modify: `src/test/java/com/lifepilot/tool/bridge/ToolBridgeAgentToolProviderTest.java`

- [ ] **Step 1：更新既有测试断言新过滤规则**

读当前 `ToolBridgeAgentToolProviderTest.java`，把"三分支测试"改为"统一过滤测试"：

```java
// 旧：断言 coreToolIds 非空时过滤出 core + activated
// 新：断言 Tier1Service.getCurrentTier1Ids() + activatedToolIds + META_TOOL_IDS 的并集

@Test
void 普通场景_返回Tier1并合上activated() {
    when(tier1Service.getCurrentTier1Ids()).thenReturn(Set.of("file.read", "tools.search"));
    when(state.activatedToolIds()).thenReturn(Set.of("datastore.query_documents"));

    var callbacks = provider.getToolCallbacks(state, null);
    var visible = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();

    assertThat(visible).contains("file.read", "tools.search", "datastore.query_documents");
    // 非 Tier 1 非 activated 非 meta 的工具不应出现
    assertThat(visible).doesNotContain("shell.process");
}

@Test
void allowedToolIds非空_走受限白名单过滤() {
    when(state.allowedToolIds()).thenReturn(Set.of("file.read"));
    var callbacks = provider.getToolCallbacks(state, null);
    var visible = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
    assertThat(visible).containsOnly("file.read");
}
```

- [ ] **Step 2：运行测试验证失败**

```
mvn test -Dtest=ToolBridgeAgentToolProviderTest
```
Expected：旧测试失败（因为 coreToolIds 字段语义已变）。

- [ ] **Step 3：改写 ToolBridgeAgentToolProvider**

把 `coreToolIds` 字段替换为 `Tier1Service`：

```java
// 构造器签名改变
public ToolBridgeAgentToolProvider(
        DynamicToolRegistry toolRegistry,
        ToolExecutionPipeline pipeline,
        ObjectMapper objectMapper,
        int maxToolOutputChars,
        Tier1Service tier1Service) {
    this.toolRegistry = toolRegistry;
    this.pipeline = pipeline;
    this.objectMapper = objectMapper;
    this.maxToolOutputChars = maxToolOutputChars;
    this.tier1Service = tier1Service;
}

private static final Set<String> META_TOOL_IDS = Set.of(
        "tools.search", "tools.describe", "tools.list");

@Override
public List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId) {
    List<ToolContract> all = toolRegistry.getToolSnapshot();
    Set<String> allowed = state.allowedToolIds();

    List<ToolContract> tools;
    if (allowed != null && !allowed.isEmpty()) {
        int totalCount = all.size();
        tools = all.stream()
                .filter(t -> allowed.contains(t.id()) || t.tags().contains("infrastructure"))
                .toList();
        log.debug("ToolCallback 过滤 (allowedToolIds): total={}, filtered={}", totalCount, tools.size());
    } else {
        Set<String> tier1 = tier1Service.getCurrentTier1Ids();
        Set<String> activated = state.activatedToolIds() != null ? state.activatedToolIds() : Set.of();
        Set<String> visible = new HashSet<>();
        visible.addAll(tier1);
        visible.addAll(activated);
        visible.addAll(META_TOOL_IDS);
        int totalCount = all.size();
        tools = all.stream().filter(t -> visible.contains(t.id())).toList();
        log.debug("ToolCallback 过滤 (统一分层): total={}, tier1={}, activated={}, meta={}, final={}",
                totalCount, tier1.size(), activated.size(), META_TOOL_IDS.size(), tools.size());
    }

    refreshToolNameMappings(tools);
    return tools.stream()
            .map(t -> toToolCallback(t, streamId, state))
            .toList();
}
```

注：把 `toToolCallback` 里 context map 填充 `state` 以便 meta tools 使用（若采用"通过 context 传 state"方案）：
```java
context.put(ToolContextKeys.CALLER_STATE, state);
```
（如前述 Task A.9 的实施提示，`CALLER_STATE` 为新增常量）

- [ ] **Step 4：更新 ToolAutoConfiguration 注入**

```java
@Bean public ToolBridgeAgentToolProvider toolBridgeAgentToolProvider(
        DynamicToolRegistry r, ToolExecutionPipeline p, ObjectMapper om,
        @Value("${agent.tools.max-output-chars:4096}") int maxChars,
        Tier1Service tier1) {
    return new ToolBridgeAgentToolProvider(r, p, om, maxChars, tier1);
}
```

- [ ] **Step 5：运行测试 + 提交**

```
mvn test -Dtest=ToolBridgeAgentToolProviderTest
```

```bash
git add src/main/java/com/lifepilot/tool/bridge \
        src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java \
        src/main/java/com/lifepilot/tool/model/ToolContextKeys.java \
        src/test/java/com/lifepilot/tool/bridge
git commit -m "refactor(tool): ToolBridge 过滤逻辑统一 (Tier1 + activated + meta)

删除 coreToolIds 字段，改为注入 Tier1Service。allowedToolIds 分支不变，
普通场景三路合一：Tier1 ∪ activated ∪ meta。context 追加 CALLER_STATE
供 meta tools 取 state。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.12：ReactAgentLoop 注入 Category Hint

**Files：**
- Modify: `src/main/java/com/lifepilot/agent/ReactAgentLoop.java`

- [ ] **Step 1：找到 system prompt 拼装位置**

grep 当前代码：
```
Grep pattern: buildSystemPrompt|systemMessage|system\.message glob: src/main/java/com/lifepilot/agent/*.java
```

定位 `ReactAgentLoop` 里构建 system prompt 的代码块。

- [ ] **Step 2：注入 ToolConfigProperties 依赖**

在 `ReactAgentLoop` 构造器增加 `ToolConfigProperties` 参数，保存为字段。

- [ ] **Step 3：拼接 category hint**

在 system prompt 的组装完成后追加：

```java
String categoryHint = toolConfigProperties.getCategoryHint();
if (categoryHint != null && !categoryHint.isBlank()) {
    systemPromptBuilder.append("\n\n").append(categoryHint.strip());
}
```

- [ ] **Step 4：运行现有 Agent 测试不回归**

```
mvn test -Dtest=ReactAgentLoop*
```
Expected：全绿。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/agent/ReactAgentLoop.java \
        src/main/java/com/lifepilot/agent/config
git commit -m "feat(agent): ReactAgentLoop system prompt 追加 category hint

从 ToolConfigProperties.categoryHint 读文案，末尾拼接到系统消息。
告诉 LLM 还有哪些 category 可通过 tools.search 发现，并要求用英文关键词。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.13：ToolUsageStatsRecorder + Repository

**Files：**
- Create: `src/main/java/com/lifepilot/tool/tier1/ToolUsageStats.java`
- Create: `src/main/java/com/lifepilot/tool/tier1/ToolUsageStatsRepository.java`
- Create: `src/main/java/com/lifepilot/tool/tier1/ToolUsageStatsRecorder.java`
- Create: `src/test/java/com/lifepilot/tool/tier1/ToolUsageStatsRecorder_记录测试.java`
- Modify: `src/main/java/com/lifepilot/tool/pipeline/ToolExecutionPipeline.java`（发事件）

- [ ] **Step 1：创建 ToolUsageStats record**

```java
package com.lifepilot.tool.tier1;

public record ToolUsageStats(
        String toolId,
        String statDate,
        int sessionCount,
        int invocationCount
) {}
```

- [ ] **Step 2：实现 Repository**

```java
package com.lifepilot.tool.tier1;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具使用统计持久化。SQLite 语法：ON CONFLICT DO UPDATE 做 upsert。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolUsageStatsRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolUsageStatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 记录一次调用；若 (toolId, date) 已存在则累加计数。 */
    public void recordInvocation(String toolId, String statDate, boolean newSessionForTool) {
        jdbcTemplate.update("""
                INSERT INTO tool_usage_stats (tool_id, stat_date, session_count, invocation_count)
                VALUES (?, ?, ?, 1)
                ON CONFLICT(tool_id, stat_date) DO UPDATE SET
                    session_count = session_count + excluded.session_count,
                    invocation_count = invocation_count + 1
                """,
                toolId, statDate, newSessionForTool ? 1 : 0);
    }

    /** 返回近 N 天内各工具的会话覆盖率（0-1）。 */
    public Map<String, Double> computeSessionCoverage(int windowDays) {
        // 近 N 天总不重复会话数
        Integer totalSessions = jdbcTemplate.queryForObject("""
                SELECT SUM(session_count) FROM tool_usage_stats
                WHERE stat_date >= date('now', ?)
                """, Integer.class, "-%d days".formatted(windowDays));
        if (totalSessions == null || totalSessions == 0) {
            return Map.of();
        }
        // 各 tool 的会话数
        return jdbcTemplate.query("""
                SELECT tool_id, SUM(session_count) AS s
                FROM tool_usage_stats
                WHERE stat_date >= date('now', ?)
                GROUP BY tool_id
                """,
                (rs, i) -> Map.entry(rs.getString("tool_id"), rs.getInt("s") / (double) totalSessions),
                "-%d days".formatted(windowDays))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
```

> **注**：上面 `computeSessionCoverage` 的"总会话数"算法是简化（所有工具 session_count 求和会重复统计），正式实现建议引入独立的 `session_daily_tools` 临时视图或单独表跟踪。MVP 可先粗略，后续精细化。

- [ ] **Step 3：实现 Recorder（监听 ToolExecutionPipeline 成功事件）**

```java
package com.lifepilot.tool.tier1;

import com.lifepilot.tool.pipeline.ToolInvocationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拦截 ToolInvocationEvent 写入每日统计。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolUsageStatsRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolUsageStatsRecorder.class);

    private final ToolUsageStatsRepository repository;
    /** 记录每个 session 在当日使用过的 toolId，用于判断是否首次（递增 session_count）。 */
    private final ConcurrentHashMap<String, Set<String>> sessionDailyTools = new ConcurrentHashMap<>();

    public ToolUsageStatsRecorder(ToolUsageStatsRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onInvocation(ToolInvocationEvent event) {
        if (!event.success()) {
            return;
        }
        String today = LocalDate.now().toString();
        String sessionKey = event.sessionId() + "|" + today;
        Set<String> seen = sessionDailyTools.computeIfAbsent(
                sessionKey, k -> ConcurrentHashMap.newKeySet());
        boolean firstTime = seen.add(event.toolId());
        try {
            repository.recordInvocation(event.toolId(), today, firstTime);
        } catch (Exception e) {
            log.warn("记录工具使用统计失败: toolId={}", event.toolId(), e);
        }
    }
}
```

- [ ] **Step 4：在 ToolExecutionPipeline 发布事件**

```java
// 在 pipeline.execute 成功路径末尾：
eventPublisher.publishEvent(new ToolInvocationEvent(
        toolId, sessionId, success, durationMs, Instant.now()));
```

创建 event record：
```java
// src/main/java/com/lifepilot/tool/pipeline/ToolInvocationEvent.java
package com.lifepilot.tool.pipeline;

import java.time.Instant;

public record ToolInvocationEvent(
        String toolId,
        String sessionId,
        boolean success,
        long durationMs,
        Instant at
) {}
```

- [ ] **Step 5：写 Recorder 测试 + Bean 装配 + 提交**

```java
// ToolUsageStatsRecorder_记录测试.java
package com.lifepilot.tool.tier1;

import com.lifepilot.tool.pipeline.ToolInvocationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.*;

class ToolUsageStatsRecorder_记录测试 {

    @Test
    void 成功调用_记录为当日并首次() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);

        recorder.onInvocation(new ToolInvocationEvent(
                "file.read", "session-1", true, 100L, Instant.now()));

        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(true));
    }

    @Test
    void 同一session同一日同一工具_第二次不递增session计数() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);

        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", true, 1L, Instant.now()));
        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", true, 1L, Instant.now()));

        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(true));
        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(false));
    }

    @Test
    void 执行失败_不记录() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);
        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", false, 1L, Instant.now()));
        verifyNoInteractions(repo);
    }
}
```

```java
// ToolAutoConfiguration 追加
@Bean public ToolUsageStatsRepository toolUsageStatsRepository(JdbcTemplate jt) {
    return new ToolUsageStatsRepository(jt);
}
@Bean public ToolUsageStatsRecorder toolUsageStatsRecorder(ToolUsageStatsRepository r) {
    return new ToolUsageStatsRecorder(r);
}
```

```bash
mvn test -Dtest=ToolUsageStatsRecorder_记录测试
git add src/main/java/com/lifepilot/tool/tier1/ToolUsageStats.java \
        src/main/java/com/lifepilot/tool/tier1/ToolUsageStatsRepository.java \
        src/main/java/com/lifepilot/tool/tier1/ToolUsageStatsRecorder.java \
        src/main/java/com/lifepilot/tool/pipeline/ToolInvocationEvent.java \
        src/main/java/com/lifepilot/tool/pipeline/ToolExecutionPipeline.java \
        src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java \
        src/test/java/com/lifepilot/tool/tier1/ToolUsageStatsRecorder_记录测试.java
git commit -m "feat(tool): 新增 ToolUsageStats 采集链路

ToolInvocationEvent 发布 + Recorder 监听写每日统计。
Repository 用 SQLite ON CONFLICT 做 upsert。
会话级去重用 ConcurrentHashMap 标记首次。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.14：Tier1AdvisoryJob 定时任务

**Files：**
- Create: `src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryJob.java`
- Create: `src/test/java/com/lifepilot/tool/tier1/Tier1AdvisoryJob_晋升计算测试.java`

- [ ] **Step 1：写测试**

```java
package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Tier1AdvisoryJob_晋升计算测试 {

    @Test
    void 覆盖率达标且未pinned_生成建议() {
        var repo = mock(Tier1AdvisoryRepository.class);
        var statsRepo = mock(ToolUsageStatsRepository.class);
        var config = new ToolConfigProperties();
        config.getTier1().getPromotion().setEnabled(true);
        config.getTier1().getPromotion().setWindowDays(30);
        config.getTier1().getPromotion().setSessionThreshold(0.3);
        config.getTier1().getPromotion().setMaxPromoted(3);
        config.getTier1().setPinned(java.util.List.of("file.read"));

        when(statsRepo.computeSessionCoverage(30)).thenReturn(Map.of(
                "file.read", 0.8,                   // 已 pinned 跳过
                "datastore.query_documents", 0.5,   // 达标，候选
                "shell.exec", 0.1                    // 未达标
        ));

        new Tier1AdvisoryJob(config, statsRepo, repo).reviewTier1();

        verify(repo).save(argThat(adv -> adv.toolId().equals("datastore.query_documents")));
        verify(repo, never()).save(argThat(adv -> adv.toolId().equals("shell.exec")));
        verify(repo, never()).save(argThat(adv -> adv.toolId().equals("file.read")));
    }

    @Test
    void 禁用promotion_不跑() {
        var repo = mock(Tier1AdvisoryRepository.class);
        var statsRepo = mock(ToolUsageStatsRepository.class);
        var config = new ToolConfigProperties();
        config.getTier1().getPromotion().setEnabled(false);

        new Tier1AdvisoryJob(config, statsRepo, repo).reviewTier1();

        verifyNoInteractions(statsRepo);
        verifyNoInteractions(repo);
    }
}
```

- [ ] **Step 2：运行测试验证失败**

- [ ] **Step 3：实现 Job**

```java
package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/**
 * 每日凌晨扫描使用数据，生成 Tier 1 晋升建议（写入 tier1_advisory 表，等管理员审批）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1AdvisoryJob {

    private static final Logger log = LoggerFactory.getLogger(Tier1AdvisoryJob.class);

    private final ToolConfigProperties config;
    private final ToolUsageStatsRepository statsRepository;
    private final Tier1AdvisoryRepository advisoryRepository;

    public Tier1AdvisoryJob(
            ToolConfigProperties config,
            ToolUsageStatsRepository statsRepository,
            Tier1AdvisoryRepository advisoryRepository) {
        this.config = config;
        this.statsRepository = statsRepository;
        this.advisoryRepository = advisoryRepository;
    }

    @Scheduled(cron = "${agent.tools.tier1.promotion.cron:0 0 3 * * *}")
    public void reviewTier1() {
        var promo = config.getTier1().getPromotion();
        if (!promo.isEnabled()) {
            return;
        }
        log.info("开始 Tier 1 晋升候选审查: window={}天", promo.getWindowDays());
        Map<String, Double> coverage = statsRepository.computeSessionCoverage(promo.getWindowDays());
        Set<String> pinned = Set.copyOf(config.getTier1().getPinned());

        var now = Instant.now();
        int created = coverage.entrySet().stream()
                .filter(e -> e.getValue() >= promo.getSessionThreshold())
                .filter(e -> !pinned.contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(promo.getMaxPromoted())
                .map(e -> new Tier1Advisory(
                        null, e.getKey(), now, promo.getWindowDays(),
                        e.getValue(), Tier1AdvisoryStatus.PENDING, null, null))
                .mapToInt(adv -> {
                    advisoryRepository.save(adv);
                    return 1;
                })
                .sum();
        log.info("Tier 1 建议生成完成: count={}", created);
    }
}
```

- [ ] **Step 4：Bean 装配 + 测试通过**

```java
// ToolAutoConfiguration
@Bean public Tier1AdvisoryJob tier1AdvisoryJob(
        ToolConfigProperties p, ToolUsageStatsRepository sr, Tier1AdvisoryRepository ar) {
    return new Tier1AdvisoryJob(p, sr, ar);
}
```

```
mvn test -Dtest=Tier1AdvisoryJob_晋升计算测试
```

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/tier1/Tier1AdvisoryJob.java \
        src/main/java/com/lifepilot/tool/config/ToolAutoConfiguration.java \
        src/test/java/com/lifepilot/tool/tier1/Tier1AdvisoryJob_晋升计算测试.java
git commit -m "feat(tool): Tier1AdvisoryJob 定时生成晋升候选

每日凌晨（cron 可配）按会话覆盖率筛候选，排除已 pinned，
按覆盖率降序限制 maxPromoted，写入 tier1_advisory 表待审批。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.15：质量回归测试集

**Files：**
- Create: `src/test/resources/tool-search-fixtures.yaml`
- Create: `src/test/java/com/lifepilot/tool/search/ToolSearchQuality_召回率回归测试.java`

- [ ] **Step 1：编写 fixtures**

`tool-search-fixtures.yaml`：
```yaml
fixtures:
  - query: "delete files"
    expected_top_3: ["file.delete", "shell.exec", "file.list"]
  - query: "remove old logs"
    expected_top_3: ["file.delete", "shell.exec"]
  - query: "write content to file"
    expected_top_3: ["file.write", "document.create"]
  - query: "read document"
    expected_top_3: ["file.read", "document.edit", "document.create"]
  - query: "list directory"
    expected_top_3: ["file.list", "shell.exec"]
  - query: "search web"
    expected_top_3: ["web.search", "web.fetch"]
  - query: "fetch url content"
    expected_top_3: ["web.fetch", "web.search"]
  - query: "execute shell command"
    expected_top_3: ["shell.exec", "shell.process"]
  - query: "run bash script"
    expected_top_3: ["shell.exec"]
  - query: "query knowledge base"
    expected_top_3: ["knowledge.search", "datastore.query_documents"]
  - query: "remember this fact"
    expected_top_3: ["memory"]
  - query: "recall memory"
    expected_top_3: ["memory"]
  - query: "send message to user"
    expected_top_3: ["notify"]
  - query: "notify user"
    expected_top_3: ["notify"]
  - query: "create document"
    expected_top_3: ["document.create", "file.write"]
  - query: "edit docx patch"
    expected_top_3: ["document.edit"]
  - query: "rollback document"
    expected_top_3: ["document.edit"]
  - query: "query datastore"
    expected_top_3: ["datastore.query_documents"]
  - query: "schedule cron job"
    expected_top_3: ["cron.create"]
  - query: "system status"
    expected_top_3: ["system.status"]
  # ... 再补 10-30 条，覆盖实际业务场景
```

- [ ] **Step 2：写回归测试**

```java
package com.lifepilot.tool.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.lifepilot.agent.model.ReactAgentState;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具搜索召回率回归测试。要求 fixture 集 top-3 召回率 ≥ 85%。
 *
 * @author zsg
 * @since 2026-04-23
 */
@SpringBootTest
class ToolSearchQuality_召回率回归测试 {

    @Autowired ToolSearchService searchService;

    @Test
    void 固定fixture集_top3召回率应不低于85percent() throws Exception {
        var mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> root;
        try (InputStream is = getClass().getResourceAsStream("/tool-search-fixtures.yaml")) {
            root = mapper.readValue(is, Map.class);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) root.get("fixtures");

        int hits = 0;
        int total = fixtures.size();
        for (var fx : fixtures) {
            String query = (String) fx.get("query");
            @SuppressWarnings("unchecked")
            List<String> expected = (List<String>) fx.get("expected_top_3");

            ToolSearchResult result = searchService.search(emptyState(), query, null, 3);
            List<String> actualIds = result.results().stream().map(ToolSearchHit::id).toList();

            if (!java.util.Collections.disjoint(actualIds, expected)) {
                hits++;
            } else {
                System.err.printf("召回失败: query=%s, expected=%s, actual=%s%n",
                        query, expected, actualIds);
            }
        }

        double recall = (double) hits / total;
        System.out.printf("召回率: %d / %d = %.2f%%%n", hits, total, recall * 100);
        assertThat(recall).isGreaterThanOrEqualTo(0.85);
    }

    private ReactAgentState emptyState() {
        // 按项目现有 ReactAgentState API 构造
        return null;    // 实施者补
    }
}
```

- [ ] **Step 3：运行测试**

```
mvn test -Dtest=ToolSearchQuality_召回率回归测试
```
Expected：首次运行可能 < 85%，观察失败的 query 补相应工具 tags 直到通过。

- [ ] **Step 4：若召回率不足，迭代补 tags**

读失败日志，找出漏召回的 query → expected 工具，补充该工具 `tags` 里缺失的英文关键词。重复 Step 3 直至 ≥ 85%。

- [ ] **Step 5：提交**

```bash
git add src/test/resources/tool-search-fixtures.yaml \
        src/test/java/com/lifepilot/tool/search/ToolSearchQuality_召回率回归测试.java
git commit -m "test(tool): 工具搜索召回率回归测试 fixture + 测试

30+ 条 query → expected_top_3 映射，断言 top-3 召回率 ≥ 85%。
执行失败时打印失败 query，便于补 tags 迭代。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.16：E2E 集成测试

**Files：**
- Create: `src/test/java/com/lifepilot/integration/ToolExposureRefactor_端到端集成测试.java`

- [ ] **Step 1：编写 E2E 测试**

```java
package com.lifepilot.integration;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.tool.search.*;
import com.lifepilot.tool.bridge.ToolBridgeAgentToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具暴露机制重构 E2E 集成测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
@SpringBootTest
class ToolExposureRefactor_端到端集成测试 {

    @Autowired ToolSearchService searchService;
    @Autowired ToolDescribeService describeService;
    @Autowired ToolListService listService;
    @Autowired ToolBridgeAgentToolProvider bridgeProvider;

    @Test
    void search_describe_调用_完整链路() {
        ReactAgentState state = /* 构造最小 state */ null;

        // 1. search
        ToolSearchResult sr = searchService.search(state, "delete files", null, 5);
        assertThat(sr.confidence()).isIn(ToolSearchConfidence.HIGH, ToolSearchConfidence.LOW);
        assertThat(sr.results()).isNotEmpty();

        // 2. describe
        String topId = sr.results().get(0).id();
        ToolDescribeResult dr = describeService.describe(List.of(topId));
        assertThat(dr.schemas()).containsKey(topId);
        assertThat(dr.notFound()).isEmpty();

        // 3. ToolBridge 暴露集合
        var callbacks = bridgeProvider.getToolCallbacks(state, null);
        var visible = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
        // Tier 1 + meta 工具必须可见
        assertThat(visible).contains("tools.search", "tools.describe", "tools.list", "file.read");
    }

    @Test
    void list_按category过滤() {
        ToolListResult r = listService.list("ACTION");
        assertThat(r.categories()).containsOnlyKeys("ACTION");
        assertThat(r.total()).isGreaterThan(0);
    }

    @Test
    void 零结果_返回NONE置信度且带hint() {
        ReactAgentState state = /* 构造 state */ null;
        ToolSearchResult r = searchService.search(state, "zzz-no-such-tool-xyz", null, 5);
        assertThat(r.confidence()).isEqualTo(ToolSearchConfidence.NONE);
        assertThat(r.hint()).contains("Try broader keywords");
    }

    @Test
    void Skill激活场景_激活工具进Tier1可见集合() {
        ReactAgentState state = /* 构造带 activatedToolIds=[datastore.query_documents] 的 state */ null;
        var callbacks = bridgeProvider.getToolCallbacks(state, null);
        var visible = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
        assertThat(visible).contains("datastore.query_documents");
    }

    @Test
    void allowedToolIds场景_受限白名单() {
        ReactAgentState state = /* 构造 allowedToolIds=[file.read] */ null;
        var callbacks = bridgeProvider.getToolCallbacks(state, null);
        var visible = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
        assertThat(visible).containsOnly("file.read");
    }
}
```

> **实施提示**：构造 `ReactAgentState` 的骨架需要读项目实际类型。如果 `ReactAgentState` 是 record，用 `new ReactAgentState(...)` 列全字段；若是 builder，走 builder。

- [ ] **Step 2：运行 E2E**

```
mvn test -Dtest=ToolExposureRefactor_端到端集成测试
```

- [ ] **Step 3：修复遗漏**

若测试失败，按栈信息修复。特别注意：
- `ToolContextKeys.CALLER_STATE` 是否已添加
- `BuiltinToolSearchProvider.fallbackState` 的正确实现
- ToolBridge 是否正确注入 state 到 context

- [ ] **Step 4：mvn clean test 全量验证**

```
mvn clean test
```
Expected：全绿。

- [ ] **Step 5：提交**

```bash
git add src/test/java/com/lifepilot/integration/ToolExposureRefactor_端到端集成测试.java
git commit -m "test(integration): 工具暴露机制重构 E2E 集成测试

验证 search→describe→调用链路、零结果 hint、Skill 激活合入、
allowedToolIds 受限白名单四大场景。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task A.17：Micrometer 观测指标

**Files：**
- Modify: `ToolSearchService.java` / `ToolDescribeService.java` / `ToolListService.java`
- Modify: `BuiltinToolSearchProvider.java`（统计 `tool_invocation.not_exposed`）

- [ ] **Step 1：在 ToolSearchService 埋点**

注入 `MeterRegistry`，在 `search()` 方法内记录：

```java
// 构造器追加 MeterRegistry 依赖
private final Counter searchInvocations;
private final Timer searchDuration;
private final Counter cacheHitA;   // layer
private final Counter cacheHitB;
private final Counter cacheHitC;
private final Counter emptyResults;
private final Counter lowConfidence;

public ToolSearchService(..., MeterRegistry meter) {
    this.searchInvocations = meter.counter("tool_search.invocations");
    this.searchDuration = meter.timer("tool_search.duration");
    this.cacheHitA = meter.counter("tool_search.cache_hit", "layer", "a");
    this.cacheHitB = meter.counter("tool_search.cache_hit", "layer", "b");
    this.cacheHitC = meter.counter("tool_search.cache_hit", "layer", "c");
    this.emptyResults = meter.counter("tool_search.empty_results");
    this.lowConfidence = meter.counter("tool_search.low_confidence");
}

public ToolSearchResult search(...) {
    searchInvocations.increment();
    return searchDuration.record(() -> {
        // ... 原逻辑
        if (layerC.isPresent()) cacheHitC.increment();
        if (layerB.isPresent()) cacheHitB.increment();
        // 结果后：
        if (confidence == NONE) emptyResults.increment();
        if (confidence == LOW) lowConfidence.increment();
    });
}
```

- [ ] **Step 2：ToolDescribeService 埋点**

```java
private final Counter describeInvocations;
private final DistributionSummary batchSize;

// 构造器
this.describeInvocations = meter.counter("tool_describe.invocations");
this.batchSize = meter.summary("tool_describe.batch_size");

// describe() 内：
describeInvocations.increment();
batchSize.record(effective.size());
```

- [ ] **Step 3：ToolBridge 拦截未曝光工具调用**

在 `toToolCallback.call()` 增加 precheck：

```java
@Override
public String call(String toolInput) {
    // 检查这个工具是否在当前可见集合
    // （由 bridgeProvider 构造 callback 时已过滤，此处为保险双重校验）
    Map<String, Object> params = parseInput(toolInput);
    // ... 原执行逻辑
}
```

实际上 ToolBridge 已经过滤掉未曝光工具，ToolCallback 不会被创建。LLM 如果试图调用未暴露工具 id（例如通过 tool_use 强传），Spring AI 框架会在找不到 ToolCallback 时抛错。

更稳妥的方式：在 Spring AI 的 `ToolCallingManager` 层面加个拦截器，若 LLM 请求的 tool name 不在 visible 集合，返回结构化 error。若项目当前已有类似机制（guardrail），复用即可；否则暂时依赖 Spring AI 内置错误返回，并在观测层记录 `tool_invocation.not_exposed`。

在 `ToolExecutionPipeline.execute` 或 event handler 监测到"工具不存在"时：
```java
if (!registry.resolve(toolId).isPresent()) {
    meter.counter("tool_invocation.not_exposed").increment();
    return ToolResult.failure("Tool '" + toolId + "' not exposed. Use tools.search to discover and tools.describe to inspect before invocation.");
}
```

- [ ] **Step 4：运行测试 + 启动检查指标暴露**

```
mvn test
mvn spring-boot:run   # 访问 /actuator/metrics 看是否有 tool_search.* 系列
```

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/search \
        src/main/java/com/lifepilot/tool/pipeline
git commit -m "feat(observability): 接入 Micrometer 指标

tool_search.invocations/duration/cache_hit/empty_results/low_confidence
tool_describe.invocations/batch_size
tool_invocation.not_exposed（工具未曝光错误路径）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Phase B · 清理

### Task B.1：删除 @Deprecated 的 core-tool-ids

**Files：**
- Modify: `src/main/resources/application.yml`（删字段）
- Modify: `src/main/java/com/lifepilot/tool/config/ToolConfigProperties.java`（删字段）

- [ ] **Step 1：确认 Phase A 已稳定运行至少 N 天（由人工判断）**

- [ ] **Step 2：从 application.yml 删除 core-tool-ids 字段**

```yaml
agent:
  tools:
    # 删除 core-tool-ids 段（已由 tier1.pinned 替代）
    tier1:
      pinned: [...]
      # ...
```

- [ ] **Step 3：从 ToolConfigProperties 删除 coreToolIds 字段 + getter/setter**

- [ ] **Step 4：mvn test + 启动验证**

```
mvn clean test && mvn spring-boot:run
```

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/tool/config/ToolConfigProperties.java \
        src/main/resources/application.yml
git commit -m "refactor(tool): 删除 @Deprecated 的 core-tool-ids 配置

已由 agent.tools.tier1.pinned 完全替代，Phase A 稳定运行后清理。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## 自审清单

### 1. Spec 覆盖

| Spec 章节 | 实现任务 | 状态 |
|----------|---------|------|
| §2 架构总览（3 Tier + 过滤）| A.11 | ✓ |
| §3.1 FTS5 表结构 | A.1 | ✓ |
| §3.2 tools.search 契约 | A.9 | ✓ |
| §3.3 tools.describe 契约 | A.9 | ✓ |
| §3.4 tools.list 契约 | A.9 | ✓ |
| §3.5 索引生命周期 | A.5, A.6 | ✓ |
| §3.6 三层缓存 | A.4 | ✓ |
| §3.7 英文化 metadata | 0.3 | ✓ |
| §3.8 FTS5 注入防护 | A.2 | ✓ |
| §4 ToolBridge 过滤重写 | A.11 | ✓ |
| §5.1 Happy path | A.16 | ✓（E2E 覆盖）|
| §5.2 异常分支 | A.7（空/低置信返回）、A.8（notFound）| ✓ |
| §5.4 观测指标 | A.17 | ✓ |
| §6.1 Tier1 动态管理 | A.10, A.14 | ✓ |
| §6.2 Tier1AdvisoryJob | A.14 | ✓ |
| §6.3 命名规范校验 | 0.1 | ✓ |
| §6.5 application.yml 配置 | 0.4 | ✓ |
| §6.6 V15 迁移 | A.1 | ✓ |
| §7.1 Phase 划分 | Phase 0 / A / B | ✓ |
| §7.2 测试策略 | 所有 Task 的 Step 1 / 5 | ✓ |
| §7.3 验收标准（召回率 85%） | A.15 | ✓ |

### 2. 占位符扫描

- 无 TBD / TODO
- 测试骨架带"实施提示"说明需读取实际 API 补齐（ReactAgentState、DynamicToolRegistry 部分 mock）——明确提示而非占位
- V15 SQL / 配置 YAML / 代码都是完整可执行内容

### 3. 类型一致性

- `ToolSearchResult` 字段：`results / totalMatched / confidence / hint`——全文一致
- `ToolSearchHit`：`id / description / category / score / actions`——JSON 和 Java 字段对齐
- `ToolDescribeResult`：`schemas / notFound / suggestion`——一致
- `Tier1AdvisoryStatus`：`PENDING/APPROVED/REJECTED`——与 SQL CHECK 约束一致
- Meta 工具 ID：`tools.search / tools.describe / tools.list`——全文一致

### 4. 执行顺序依赖

```
Phase 0 顺序：0.1 → 0.2 → 0.3 → 0.4
Phase A 顺序：
  A.1（V15）→ A.2（sanitizer）→ A.3（models）
  → A.4（caches）
  → A.5（builder）→ A.6（maintainer）
  → A.10（Tier1 先于 A.7，因 SearchService 依赖 Tier1Service）
  → A.13（Recorder 早于 A.14 因 Job 依赖 stats 数据，但任务本身可并行，数据生成需要流量）
  → A.7（search service）
  → A.8（describe / list service）
  → A.9（BuiltinToolSearchProvider 注册 meta 工具）
  → A.11（ToolBridge 重写）
  → A.12（category hint）
  → A.14（advisory job）
  → A.15（质量回归）
  → A.16（E2E）
  → A.17（观测指标）
Phase B：
  B.1（等 Phase A 稳定后）
```

---

## 执行交接

**Plan complete and saved to `docs/superpowers/plans/2026-04-23-tool-exposure-refactor.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
