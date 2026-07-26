package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.thinking.ThinkingMode;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.service.ModelServiceRuntimeHealth;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalDiagnosticService} 单元测试。
 *
 * @author zsg
 * @since 2026-07-04
 */
class LocalDiagnosticService_单元测试 {

    @TempDir
    Path tempDir;

    @Test
    void 生成报告_聚合本地服务状态和关键计数() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");

        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("backups"));
        when(paths.workspace()).thenReturn(tempDir.resolve("workspace"));
        tempDir.resolve("workspace").toFile().mkdirs();
        tempDir.resolve("backups").toFile().mkdirs();

        var sessions = mock(ChatSessionRepository.class);
        when(sessions.findAll()).thenReturn(List.of(new ChatSession(
                "session-1",
                "测试会话",
                null,
                2,
                false,
                false,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null
        )));

        var knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(knowledgeBases.findAll()).thenReturn(List.of(new KnowledgeBase(
                "kb-1",
                "资料库",
                "测试",
                "embedding",
                null,
                "smart",
                Map.of(),
                2,
                12,
                List.of(),
                Instant.now(),
                Instant.now()
        )));

        var modelServices = mock(ModelServiceRepository.class);
        when(modelServices.findAll()).thenReturn(List.of(new ModelServiceEntity(
                "generation-1",
                ModelServiceKind.GENERATION,
                "openai-official",
                "http://localhost",
                null,
                "gpt-test",
                30,
                0,
                true,
                false,
                ThinkingMode.AUTO,
                List.of("chat"),
                Set.of(),
                Map.of(),
                "测试模型",
                null
        )));

        var skills = mock(SkillRegistry.class);
        when(skills.listAll()).thenReturn(List.of(SkillDefinition.builder()
                .id("skill.test")
                .name("测试技能")
                .instructions("执行测试")
                .build()));

        var tools = mock(DynamicToolRegistry.class);
        when(tools.getToolSnapshot()).thenReturn(List.of());

        var workflows = mock(WorkflowRegistry.class);
        when(workflows.listAll()).thenReturn(List.of());
        when(workflows.listEnabled()).thenReturn(List.of());

        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                paths,
                sessions,
                knowledgeBases,
                modelServices,
                null,
                skills,
                tools,
                workflows,
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.status()).isEqualTo("WARN");
        assertThat(report.counts()).containsEntry("modelServices.generationEnabled", 1L);
        assertThat(report.counts()).containsEntry("modelServices.embeddingEnabled", 0L);
        assertThat(report.counts()).containsEntry("intelligence.experienceMatchTimeoutMs", 0L);
        assertThat(report.counts()).containsEntry("intelligence.effectiveExperienceMatchTimeoutMs", 0L);
        assertThat(report.counts()).containsEntry("intelligence.experienceMatchBackgroundTimeoutMs", 1200L);
        assertThat(report.counts()).containsEntry("intelligence.experienceMatchTrigger", "task-like");
        assertThat(report.counts()).containsEntry("agentLoop.decisionSignalContextTimeoutMs", 0L);
        assertThat(report.counts()).containsEntry("agentLoop.maxPendingToolExperienceRecords", 4);
        assertThat(report.counts()).containsEntry("agentLoop.toolExperienceRecordTimeoutMs", 1200L);
        assertThat(report.counts()).containsEntry("knowledgeBases.documents", 2L);
        assertThat(report.counts()).containsEntry("sessions.total", 1);
        assertThat(report.counts()).containsEntry("skills.suggestedToolReferences", 0);
        assertThat(report.counts()).containsEntry("skills.unknownToolReferences", 0);
        assertThat(report.counts()).containsEntry("skills.missingCanonicalToolReferences", 0);
        assertThat(report.counts()).containsEntry("tools.highRisk", 0L);
        assertThat(report.checks()).extracting("id")
                .contains("database", "schema-migrations", "data-backups", "desktop-distribution", "paths", "model-services", "intelligence", "python-runtime");
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("database");
                    assertThat(check.status()).isEqualTo("OK");
                    assertThat(check.detail()).contains("内存库");
                    assertThat(check.metadata())
                            .containsEntry("databaseLocation", "memory")
                            .containsEntry("jdbcUrl", "jdbc:sqlite::memory:")
                            .containsKeys(
                                    "sqliteVersion",
                                    "journalMode",
                                    "busyTimeoutMs",
                                    "pageSize",
                                    "pageCount");
                });
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("OK");
                    assertThat(check.detail()).contains("不占用主对话等待");
                    assertThat(check.metadata())
                            .containsEntry("experienceMatchTimeoutMs", 0L)
                            .containsEntry("experienceMatchForegroundWaitCapMs", 80L)
                            .containsEntry("experienceMatchBackgroundTimeoutMs", 1200L)
                            .containsEntry("effectiveExperienceMatchTimeoutMs", 0L)
                            .containsEntry("experienceMatchTrigger", "task-like")
                            .containsEntry("decisionSignalContextTimeoutMs", 0L)
                            .containsEntry("maxPendingToolExperienceRecords", 4)
                            .containsEntry("toolExperienceRecordTimeoutMs", 1200L);
                });
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("schema-migrations");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("未发现 Flyway 迁移历史");
                });
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("desktop-distribution");
                    assertThat(check.metadata()).containsEntry("versionsAligned", true);
                    assertThat(check.metadata()).containsEntry("beforeBuildCommand", "npm run tauri:prepare");
                    assertThat(check.metadata()).containsEntry("buildScriptsAligned", true);
                    assertThat(check.metadata()).containsEntry("tauriBuildScriptValid", true);
                    assertThat(check.metadata()).containsEntry("tauriWindowsBuildScriptValid", true);
                    assertThat(check.metadata()).containsKeys(
                            "mavenCliAvailable",
                            "mavenCliVersion",
                            "cargoCliAvailable",
                            "cargoCliVersion",
                            "rustcCliAvailable",
                            "rustcCliVersion",
                            "buildToolchainReady",
                            "embeddedJreDirectory",
                            "embeddedJreJava",
                            "embeddedJreAvailable",
                            "embeddedJreVersion",
                            "embeddedJreMajorVersion",
                            "embeddedJreReady",
                            "embeddedJreRequiredForWindowsBuild",
                            "embeddedJrePrepareCommand");
                    assertThat(check.metadata()).containsEntry("updaterDependency", false);
                    assertThat(check.metadata()).containsEntry("updaterReady", false);
                    assertThat(check.metadata()).containsEntry("updaterArtifactsConfigured", false);
                    assertThat(check.metadata()).containsEntry("updaterArtifactsMode", "missing");
                    assertThat(check.metadata()).containsEntry("updaterPubkeyConfigured", false);
                    assertThat(check.metadata()).containsEntry("updaterEndpointCount", 0);
                    assertThat(check.metadata()).containsEntry("updaterEndpointsConfigured", false);
                    assertThat(check.metadata()).containsEntry("updaterInstallMode", "default");
                    assertThat(check.metadata()).containsEntry("embeddedJrePrepareCommand",
                            "cd zhiwei-web && npm run tauri:prepare:jre");
                    assertThat(check.metadata()).containsEntry("desktopBuildCommand", "cd zhiwei-web && npm run tauri:build");
                    assertThat(check.metadata()).containsEntry("windowsBuildCommand", "cd zhiwei-web && npm run tauri:build:windows");
                    assertThat(check.metadata()).containsKey("packageArtifactDirectory");
                    assertThat(check.metadata()).containsKey("packageArtifactCount");
                    assertThat(check.metadata().get("packageArtifactCount")).isInstanceOf(Integer.class);
                    if (Boolean.FALSE.equals(check.metadata().get("buildToolchainReady"))) {
                        assertThat(check.detail()).contains("构建工具链");
                    } else {
                        assertThat(check.detail()).contains("自动更新配置不完整");
                    }
                });
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("data-backups");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("尚未发现本地备份文件");
                });
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("capabilities");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("没有可用工具");
                    assertThat(check.metadata())
                            .containsEntry("skillSuggestedToolReferences", 0)
                            .containsEntry("unknownSkillToolReferences", 0)
                            .containsEntry("missingCanonicalSkillToolReferences", 0)
                            .containsEntry("highRiskTools", 0L);
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("Python 运行时"));
    }

    @Test
    void 生成报告_经验匹配前台等待会被限流并标记为轻风险() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", "zhiwei")
                .withProperty("lifepilot.intelligence.experience-match-timeout-ms", "250")
                .withProperty("lifepilot.intelligence.experience-match-background-timeout-ms", "1500")
                .withProperty("lifepilot.intelligence.experience-match-trigger", "always");

        var service = new LocalDiagnosticService(
                environment,
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("80ms").contains("后台复用");
                    assertThat(check.metadata())
                            .containsEntry("experienceMatchTimeoutMs", 250L)
                            .containsEntry("experienceMatchForegroundWaitCapMs", 80L)
                            .containsEntry("experienceMatchBackgroundTimeoutMs", 1500L)
                            .containsEntry("effectiveExperienceMatchTimeoutMs", 80L)
                            .containsEntry("experienceMatchTrigger", "always")
                            .containsEntry("decisionSignalContextTimeoutMs", 0L)
                            .containsEntry("capabilityDiscoveryEnabled", true)
                            .containsEntry("capabilityDiscoveryPlanningProbeMaxChars", 320)
                            .containsEntry("toolExperienceRecordTimeoutMs", 1200L);
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("智能增强"));
    }

    @Test
    void 生成报告_决策信号上下文预算会提示不要占用主对话等待() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", "zhiwei")
                .withProperty("lifepilot.agent.context.decision-signal-timeout-ms", "60");

        var service = new LocalDiagnosticService(
                environment,
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail())
                            .contains("决策信号最多等待 60ms")
                            .contains("建议设为 0");
                    assertThat(check.metadata())
                            .containsEntry("decisionSignalContextTimeoutMs", 60L)
                            .containsEntry("effectiveExperienceMatchTimeoutMs", 0L);
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("智能增强"));
    }

    @Test
    void 生成报告_工具经验后台记录没有超时时标记为风险() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", "zhiwei")
                .withProperty("lifepilot.agent.loop.tool-experience-record-timeout-ms", "0");

        var service = new LocalDiagnosticService(
                environment,
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("工具经验记录未设置后台超时");
                    assertThat(check.metadata())
                            .containsEntry("maxPendingToolExperienceRecords", 4)
                            .containsEntry("toolExperienceRecordTimeoutMs", 0L);
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("智能增强"));
    }

    @Test
    void 生成报告_能力预发现关闭时说明不会做前置意图探针() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", "zhiwei")
                .withProperty("lifepilot.intelligence.experience-match-trigger", "disabled")
                .withProperty("lifepilot.agent.capability-discovery.enabled", "false");

        var service = new LocalDiagnosticService(
                environment,
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("OK");
                    assertThat(check.detail()).contains("不会做前置意图探针");
                    assertThat(check.metadata())
                            .containsEntry("experienceMatchTrigger", "disabled")
                            .containsEntry("capabilityDiscoveryEnabled", false);
                });
    }

    @Test
    void 生成报告_能力预发现探针配置非法时标记错误() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", "zhiwei")
                .withProperty("lifepilot.agent.capability-discovery.control-prefix-chars", "0");

        var service = new LocalDiagnosticService(
                environment,
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("ERROR");
                    assertThat(check.detail()).contains("lifepilot.agent.capability-discovery");
                    assertThat(check.metadata())
                            .containsEntry("capabilityDiscoveryControlPrefixChars", 0);
                });
    }

    @Test
    void 生成报告_决策信号上下文预算非法时标记错误() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var environment = new MockEnvironment()
                .withProperty("spring.application.name", "zhiwei")
                .withProperty("lifepilot.agent.context.decision-signal-timeout-ms", "-1");

        var service = new LocalDiagnosticService(
                environment,
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("intelligence");
                    assertThat(check.status()).isEqualTo("ERROR");
                    assertThat(check.detail()).contains("lifepilot.agent.context");
                    assertThat(check.metadata())
                            .containsEntry("decisionSignalContextTimeoutMs", -1L);
                });
    }

    @Test
    void 生成报告_向量服务预热失败时标记能力降级() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var runtimeHealth = new ModelServiceRuntimeHealth();
        runtimeHealth.record(
                "generation-1",
                Set.of(ProviderCapability.CHAT),
                ModelServiceRuntimeHealth.HealthState.HEALTHY,
                "启动预热成功");
        runtimeHealth.record(
                "embedding-1",
                Set.of(ProviderCapability.EMBEDDING),
                ModelServiceRuntimeHealth.HealthState.UNHEALTHY,
                "启动预热返回异常状态");

        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationAndEmbeddingServices(),
                runtimeHealth,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("model-services");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("向量服务启动预热异常");
                    assertThat(check.metadata())
                            .containsEntry("generationEnabled", 1L)
                            .containsEntry("embeddingEnabled", 1L)
                            .containsEntry("generationUnhealthy", 0L)
                            .containsEntry("embeddingUnhealthy", 1L);
                    assertThat(check.metadata().get("runtimeHealth").toString())
                            .contains("embedding-1")
                            .contains("UNHEALTHY");
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("模型服务"));
    }

    @Test
    void 生成报告_技能引用断链工具时标记能力风险() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");

        var skills = mock(SkillRegistry.class);
        when(skills.listAll()).thenReturn(List.of(SkillDefinition.builder()
                .id("skill.broken")
                .name("断链技能")
                .instructions("执行需要多种工具的任务")
                .suggestedTools(List.of("unknown.tool", "shell.exec", "memory"))
                .build()));

        var tools = mock(DynamicToolRegistry.class);
        when(tools.getToolSnapshot()).thenReturn(List.of(
                testTool("memory", RiskLevel.LOW),
                testTool("file.write", RiskLevel.HIGH)
        ));

        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                skills,
                tools,
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.counts())
                .containsEntry("skills.suggestedToolReferences", 3)
                .containsEntry("skills.registeredToolReferences", 1)
                .containsEntry("skills.canonicalToolReferences", 2)
                .containsEntry("skills.unknownToolReferences", 1)
                .containsEntry("skills.missingCanonicalToolReferences", 1)
                .containsEntry("tools.highRisk", 1L);
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("capabilities");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("未知工具").contains("核心工具当前不可用");
                    assertThat(check.metadata())
                            .containsEntry("skillSuggestedToolReferences", 3)
                            .containsEntry("registeredSkillToolReferences", 1)
                            .containsEntry("canonicalSkillToolReferences", 2)
                            .containsEntry("unknownSkillToolReferences", 1)
                            .containsEntry("missingCanonicalSkillToolReferences", 1)
                            .containsEntry("highRiskTools", 1L);
                    assertThat(check.metadata().get("unknownSkillToolReferenceSamples").toString())
                            .contains("skill.broken -> unknown.tool");
                    assertThat(check.metadata().get("missingCanonicalSkillToolReferenceSamples").toString())
                            .contains("skill.broken -> shell.exec");
                    assertThat(check.metadata().get("toolCategories").toString())
                            .contains("COGNITION=2");
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("工具和技能"));
    }

    @Test
    void 生成报告_发现失败迁移时标记为错误() throws Exception {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("failed-migration.db"));
        createFailedFlywayHistory(dataSource);

        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.status()).isEqualTo("ERROR");
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("schema-migrations");
                    assertThat(check.status()).isEqualTo("ERROR");
                    assertThat(check.detail()).contains("存在失败的数据库迁移");
                    assertThat(check.metadata())
                            .containsEntry("latestVersion", "8")
                            .containsEntry("failedMigrationScripts", List.of("V8__broken_migration.sql"));
                    assertThat(check.metadata().get("failedMigrationSamples").toString())
                            .contains("V8__broken_migration.sql")
                            .contains("broken migration")
                            .contains("2026-07-04 10:00:00");
                });
        assertThat(report.hints()).anyMatch(hint -> hint.contains("数据迁移"));
    }

    @Test
    void 生成报告_数据库诊断包含文件和Wal信息() throws Exception {
        Path database = tempDir.resolve("diagnostic.db");
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);

        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO sample(value) VALUES ('测试')");

            var service = new LocalDiagnosticService(
                    new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                    new ObjectMapper(),
                    dataSource,
                    healthyPaths(),
                    emptySessions(),
                    emptyKnowledgeBases(),
                    enabledGenerationServices(),
                    null,
                    emptySkills(),
                    emptyTools(),
                    emptyWorkflows(),
                    null,
                    null
            );

            var report = service.createReport();

            assertThat(report.checks())
                    .anySatisfy(check -> {
                        assertThat(check.id()).isEqualTo("database");
                        assertThat(check.status()).isEqualTo("OK");
                        assertThat(check.detail()).contains("数据库文件可定位");
                        assertThat(check.metadata())
                                .containsEntry("databaseLocation", "file")
                                .containsEntry("databasePath", database.toAbsolutePath().normalize().toString())
                                .containsEntry("databaseExists", true)
                                .containsEntry("walPath", database.toAbsolutePath().normalize() + "-wal")
                                .containsEntry("shmPath", database.toAbsolutePath().normalize() + "-shm")
                                .containsEntry("journalMode", "wal");
                        assertThat((Long) check.metadata().get("databaseSizeBytes")).isGreaterThan(0L);
                        assertThat(check.metadata()).containsKeys(
                                "walExists",
                                "walSizeBytes",
                                "shmExists",
                                "shmSizeBytes",
                                "sqliteVersion",
                                "busyTimeoutMs");
                    });
        }
    }

    @Test
    void 生成报告_发现备份文件时标记备份可用() throws Exception {
        var backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);
        Files.writeString(backupDir.resolve("zhiwei-backup.zip"), "backup");
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");

        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("data-backups");
                    assertThat(check.status()).isEqualTo("OK");
                    assertThat(check.metadata()).containsEntry("fileCount", 1);
                    assertThat(check.metadata()).containsEntry("latestFile", "zhiwei-backup.zip");
                });
    }

    @Test
    void 生成报告_可选能力模块未启用时降级为警告而不是启动失败() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");

        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                null,
                enabledGenerationServices(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        var report = service.createReport();

        assertThat(report.status()).isEqualTo("WARN");
        assertThat(report.counts())
                .containsEntry("knowledgeBases.total", 0)
                .containsEntry("skills.total", 0)
                .containsEntry("tools.total", 0)
                .containsEntry("workflows.total", 0);
        assertThat(report.checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("knowledge-bases");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("知识库模块未启用");
                })
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("capabilities");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("能力未启用");
                })
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("workflows");
                    assertThat(check.status()).isEqualTo("WARN");
                    assertThat(check.detail()).contains("工作流模块未启用");
                });
    }

    @Test
    void 创建诊断包_写入报告清单和最近日志尾部() throws Exception {
        Path logs = tempDir.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("zhiwei.log"),
                "old-line\n".repeat(40_000) + "最近一次错误：模型服务连接失败\n",
                StandardCharsets.UTF_8);

        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        var service = new LocalDiagnosticService(
                new MockEnvironment().withProperty("spring.application.name", "zhiwei"),
                new ObjectMapper(),
                dataSource,
                healthyPaths(),
                emptySessions(),
                emptyKnowledgeBases(),
                enabledGenerationServices(),
                null,
                emptySkills(),
                emptyTools(),
                emptyWorkflows(),
                null,
                null
        );

        var bundle = service.createDiagnosticBundle();

        assertThat(bundle.fileName()).startsWith("zhiwei-diagnostic-").endsWith(".zip");
        assertThat(bundle.includedFileCount()).isEqualTo(4);
        assertThat(bundle.sizeBytes()).isPositive();
        Path bundlePath = Path.of(bundle.path());
        assertThat(bundlePath).exists().isRegularFile();
        assertThat(bundlePath.getParent()).isEqualTo(tempDir.resolve("diagnostics"));

        try (var zip = new ZipFile(bundlePath.toFile())) {
            assertThat(zip.getEntry("diagnostic-report.json")).isNotNull();
            assertThat(zip.getEntry("manifest.json")).isNotNull();
            assertThat(zip.getEntry("diagnostic-summary.txt")).isNotNull();
            var logEntry = zip.getEntry("logs/1-zhiwei.log.tail.log");
            assertThat(logEntry).isNotNull();

            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(manifest)
                    .contains("\"reportStatus\"")
                    .contains("\"summaryEntry\" : \"diagnostic-summary.txt\"")
                    .contains("\"includedLogs\"");

            String summary = new String(zip.getInputStream(zip.getEntry("diagnostic-summary.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(summary)
                    .contains("[知微诊断摘要]")
                    .contains("状态: WARN")
                    .contains("问题检查:")
                    .contains("建议:")
                    .contains("日志:")
                    .contains("logs/1-zhiwei.log.tail.log <-")
                    .contains("检查清单:");

            String logTail = new String(zip.getInputStream(logEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(logTail)
                    .contains("日志已截断")
                    .contains("最近一次错误：模型服务连接失败");
        }
    }

    private void createFailedFlywayHistory(SQLiteDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE flyway_schema_history (
                        installed_rank INTEGER PRIMARY KEY,
                        version TEXT,
                        description TEXT,
                        type TEXT,
                        script TEXT,
                        checksum INTEGER,
                        installed_by TEXT,
                        installed_on TEXT,
                        execution_time INTEGER,
                        success INTEGER
                    )
                    """);
            statement.execute("""
                    INSERT INTO flyway_schema_history (
                        installed_rank, version, description, type, script,
                        checksum, installed_by, installed_on, execution_time, success
                    ) VALUES (
                        8, '8', 'broken migration', 'SQL', 'V8__broken_migration.sql',
                        1, 'test', '2026-07-04 10:00:00', 12, 0
                    )
                    """);
        }
    }

    private ZhiweiPaths healthyPaths() {
        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(anyString())).thenAnswer(invocation -> tempDir.resolve(invocation.getArgument(0, String.class)));
        when(paths.workspace()).thenReturn(tempDir.resolve("workspace"));
        tempDir.resolve("workspace").toFile().mkdirs();
        tempDir.resolve("backups").toFile().mkdirs();
        return paths;
    }

    private ChatSessionRepository emptySessions() {
        var sessions = mock(ChatSessionRepository.class);
        when(sessions.findAll()).thenReturn(List.of());
        return sessions;
    }

    private KnowledgeBaseRepository emptyKnowledgeBases() {
        var knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(knowledgeBases.findAll()).thenReturn(List.of());
        return knowledgeBases;
    }

    private ModelServiceRepository enabledGenerationServices() {
        var modelServices = mock(ModelServiceRepository.class);
        when(modelServices.findAll()).thenReturn(List.of(new ModelServiceEntity(
                "generation-1",
                ModelServiceKind.GENERATION,
                "openai-official",
                "http://localhost",
                null,
                "gpt-test",
                30,
                0,
                true,
                false,
                ThinkingMode.AUTO,
                List.of("chat"),
                Set.of(),
                Map.of(),
                "测试模型",
                null
        )));
        return modelServices;
    }

    private ModelServiceRepository enabledGenerationAndEmbeddingServices() {
        var modelServices = mock(ModelServiceRepository.class);
        when(modelServices.findAll()).thenReturn(List.of(
                new ModelServiceEntity(
                        "generation-1",
                        ModelServiceKind.GENERATION,
                        "openai-official",
                        "http://localhost",
                        null,
                        "gpt-test",
                        30,
                        0,
                        true,
                        false,
                        ThinkingMode.AUTO,
                        List.of("chat"),
                        Set.of(),
                        Map.of(),
                        "测试模型",
                        null
                ),
                new ModelServiceEntity(
                        "embedding-1",
                        ModelServiceKind.EMBEDDING,
                        "tei-local",
                        "http://localhost:8081",
                        null,
                        "bge-m3",
                        30,
                        0,
                        true,
                        false,
                        ThinkingMode.AUTO,
                        List.of(),
                        Set.of(),
                        Map.of(),
                        "测试向量",
                        null
                )
        ));
        return modelServices;
    }

    private SkillRegistry emptySkills() {
        var skills = mock(SkillRegistry.class);
        when(skills.listAll()).thenReturn(List.of());
        return skills;
    }

    private BuiltinTool testTool(String id, RiskLevel riskLevel) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("测试工具 " + id)
                .tags(List.of("test", "diagnostic"))
                .category(ToolCategory.COGNITION)
                .riskLevel(riskLevel)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    private DynamicToolRegistry emptyTools() {
        var tools = mock(DynamicToolRegistry.class);
        when(tools.getToolSnapshot()).thenReturn(List.of());
        return tools;
    }

    private WorkflowRegistry emptyWorkflows() {
        var workflows = mock(WorkflowRegistry.class);
        when(workflows.listAll()).thenReturn(List.of());
        when(workflows.listEnabled()).thenReturn(List.of());
        return workflows;
    }
}
