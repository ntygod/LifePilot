package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.interaction.web.model.DiagnosticBundleInfo;
import com.lifepilot.interaction.web.model.DiagnosticCheckInfo;
import com.lifepilot.interaction.web.model.DiagnosticReportInfo;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.mcp.registry.McpServerEntry;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.service.ModelServiceRuntimeHealth;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeStatusJson;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillToolReferenceCatalog;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 本地诊断报告服务。
 *
 * <p>只读取本地状态，不触发模型探测、MCP 连接或后台任务，避免诊断接口本身影响主对话体验。</p>
 *
 * @author zsg
 * @since 2026-07-04
 */
@Service
public class LocalDiagnosticService {

    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    private static final int DIAGNOSTIC_SAMPLE_LIMIT = 5;
    private static final String DIAGNOSTIC_BUNDLES_DIR = "diagnostics";
    private static final int DIAGNOSTIC_LOG_LIMIT = 3;
    private static final int DIAGNOSTIC_LOG_TAIL_BYTES = 256 * 1024;
    private static final DateTimeFormatter DIAGNOSTIC_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final ZhiweiPaths zhiweiPaths;
    private final ChatSessionRepository chatSessionRepository;
    @Nullable
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ModelServiceRepository modelServiceRepository;
    @Nullable
    private final ModelServiceRuntimeHealth modelServiceRuntimeHealth;
    @Nullable
    private final SkillRegistry skillRegistry;
    @Nullable
    private final DynamicToolRegistry toolRegistry;
    @Nullable
    private final WorkflowRegistry workflowRegistry;
    @Nullable
    private final McpServerRegistry mcpServerRegistry;
    @Nullable
    private final PythonRuntimeManager pythonRuntimeManager;

    public LocalDiagnosticService(Environment environment,
                                  ObjectMapper objectMapper,
                                  DataSource dataSource,
                                  ZhiweiPaths zhiweiPaths,
                                  ChatSessionRepository chatSessionRepository,
                                  @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
                                  ModelServiceRepository modelServiceRepository,
                                  @Nullable ModelServiceRuntimeHealth modelServiceRuntimeHealth,
                                  @Nullable SkillRegistry skillRegistry,
                                  @Nullable DynamicToolRegistry toolRegistry,
                                  @Nullable WorkflowRegistry workflowRegistry,
                                  @Nullable McpServerRegistry mcpServerRegistry,
                                  @Nullable PythonRuntimeManager pythonRuntimeManager) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.zhiweiPaths = zhiweiPaths;
        this.chatSessionRepository = chatSessionRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.modelServiceRepository = modelServiceRepository;
        this.modelServiceRuntimeHealth = modelServiceRuntimeHealth;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.workflowRegistry = workflowRegistry;
        this.mcpServerRegistry = mcpServerRegistry;
        this.pythonRuntimeManager = pythonRuntimeManager;
    }

    /**
     * 生成当前本地诊断报告。
     */
    public DiagnosticReportInfo createReport() {
        var checks = new ArrayList<DiagnosticCheckInfo>();
        var counts = new LinkedHashMap<String, Object>();
        var hints = new ArrayList<String>();

        checks.add(safeCheck("database", "数据库", this::checkDatabase));
        checks.add(safeCheck("schema-migrations", "数据迁移", this::checkSchemaMigrations));
        checks.add(safeCheck("data-backups", "数据备份", this::checkDataBackups));
        checks.add(safeCheck("desktop-distribution", "安装与更新", this::checkDesktopDistribution));
        checks.add(safeCheck("paths", "数据目录", this::checkPaths));
        checks.add(safeCheck("model-services", "模型服务", () -> checkModelServices(counts)));
        checks.add(safeCheck("intelligence", "智能增强", () -> checkIntelligence(counts)));
        checks.add(safeCheck("knowledge-bases", "知识库", () -> checkKnowledgeBases(counts)));
        checks.add(safeCheck("sessions", "会话", () -> checkSessions(counts)));
        checks.add(safeCheck("capabilities", "工具和技能", () -> checkCapabilities(counts)));
        checks.add(safeCheck("workflows", "工作流", () -> checkWorkflows(counts)));
        checks.add(safeCheck("mcp", "MCP 服务", () -> checkMcp(counts)));
        checks.add(safeCheck("python-runtime", "Python 运行时", this::checkPythonRuntime));

        String status = summarizeStatus(checks);
        collectHints(checks, hints);

        return new DiagnosticReportInfo(
                Instant.now(),
                status,
                switch (status) {
                    case ERROR -> "本地服务存在需要处理的错误";
                    case WARN -> "本地服务可用，但有配置或运行时风险";
                    default -> "本地服务状态正常";
                },
                buildAppInfo(),
                buildRuntimeInfo(),
                Map.copyOf(counts),
                List.copyOf(checks),
                List.copyOf(hints)
        );
    }

    /**
     * 导出本地诊断包，便于用户在排障时提供结构化状态和最近日志。
     */
    public DiagnosticBundleInfo createDiagnosticBundle() throws IOException {
        Path bundleDirectory = zhiweiPaths.home(DIAGNOSTIC_BUNDLES_DIR).toAbsolutePath().normalize();
        Files.createDirectories(bundleDirectory);

        Instant now = Instant.now();
        String fileName = "zhiwei-diagnostic-" + DIAGNOSTIC_TIME_FORMAT.format(now)
                + "-" + now.toEpochMilli() + ".zip";
        Path target = bundleDirectory.resolve(fileName).normalize();
        Path temp = bundleDirectory.resolve(fileName + ".tmp").normalize();

        if (!target.startsWith(bundleDirectory) || !temp.startsWith(bundleDirectory)) {
            throw new IOException("诊断包路径逃逸 HOME/diagnostics 目录");
        }

        DiagnosticReportInfo report = createReport();
        List<LogSnippet> logSnippets = collectLogSnippets();
        AtomicInteger fileCount = new AtomicInteger();

        try (var output = new ZipOutputStream(Files.newOutputStream(temp))) {
            writeJsonEntry(output, "diagnostic-report.json", report, fileCount);
            writeJsonEntry(output, "manifest.json", buildDiagnosticBundleManifest(now, report, logSnippets), fileCount);
            writeTextEntry(output, "diagnostic-summary.txt",
                    buildDiagnosticSummaryText(now, report, logSnippets), fileCount);
            for (int index = 0; index < logSnippets.size(); index++) {
                LogSnippet snippet = logSnippets.get(index);
                writeTextEntry(output, diagnosticLogEntryName(index, snippet), snippet.content(), fileCount);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new DiagnosticBundleInfo(
                now,
                fileName,
                target.toString(),
                Files.size(target),
                fileCount.get()
        );
    }

    private DiagnosticCheckInfo checkDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            try (var resultSet = statement.executeQuery("SELECT 1")) {
                boolean ok = resultSet.next() && resultSet.getInt(1) == 1;
                if (!ok) {
                    return check("database", "数据库", ERROR, "SQLite 探活查询没有返回预期结果", Map.of());
                }
            }
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("catalog", connection.getCatalog());
            metadata.put("readOnly", connection.isReadOnly());
            metadata.put("autoCommit", connection.getAutoCommit());
            addDatabaseRuntimeMetadata(connection, statement, metadata);
            if ("file".equals(metadata.get("databaseLocation"))
                    && Boolean.FALSE.equals(metadata.get("databaseExists"))) {
                return check("database", "数据库", ERROR,
                        "SQLite 连接可用，但数据库文件无法定位，请检查 HOME 与启动配置", metadata);
            }
            String detail = "memory".equals(metadata.get("databaseLocation"))
                    ? "SQLite 连接可用（内存库）"
                    : "SQLite 连接可用，数据库文件可定位";
            return check("database", "数据库", OK, detail, metadata);
        }
    }

    private void addDatabaseRuntimeMetadata(Connection connection,
                                            java.sql.Statement statement,
                                            Map<String, Object> metadata) {
        String jdbcUrl = readJdbcUrl(connection);
        metadata.put("jdbcUrl", sanitizeJdbcUrl(jdbcUrl));
        metadata.put("sqliteVersion", queryFirstValue(statement, "SELECT sqlite_version()"));
        metadata.put("journalMode", queryFirstValue(statement, "PRAGMA journal_mode"));
        metadata.put("synchronous", queryFirstValue(statement, "PRAGMA synchronous"));
        metadata.put("busyTimeoutMs", queryFirstValue(statement, "PRAGMA busy_timeout"));
        metadata.put("foreignKeys", queryFirstValue(statement, "PRAGMA foreign_keys"));
        metadata.put("pageSize", queryFirstValue(statement, "PRAGMA page_size"));
        metadata.put("pageCount", queryFirstValue(statement, "PRAGMA page_count"));

        DatabaseFileRef databaseFile = resolveDatabaseFile(jdbcUrl);
        metadata.put("databaseLocation", databaseFile.location());
        if (databaseFile.path() != null) {
            Path databasePath = databaseFile.path();
            addFileMetadata(metadata, "database", databasePath);
            addFileMetadata(metadata, "wal", Path.of(databasePath + "-wal"));
            addFileMetadata(metadata, "shm", Path.of(databasePath + "-shm"));
        }
    }

    private String readJdbcUrl(Connection connection) {
        try {
            var metadata = connection.getMetaData();
            return metadata == null ? "" : metadata.getURL();
        } catch (Exception e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private String queryFirstValue(java.sql.Statement statement, String sql) {
        try (var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? String.valueOf(resultSet.getObject(1)) : "-";
        } catch (Exception e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private DatabaseFileRef resolveDatabaseFile(String jdbcUrl) {
        String prefix = "jdbc:sqlite:";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return new DatabaseFileRef("unknown", null);
        }
        String raw = jdbcUrl.substring(prefix.length()).trim();
        if (raw.isBlank() || ":memory:".equals(raw) || raw.contains("mode=memory")) {
            return new DatabaseFileRef("memory", null);
        }
        int queryIndex = raw.indexOf('?');
        if (queryIndex >= 0) {
            raw = raw.substring(0, queryIndex);
        }
        if (raw.startsWith("file:")) {
            raw = raw.substring("file:".length());
        }
        if (raw.isBlank() || ":memory:".equals(raw)) {
            return new DatabaseFileRef("memory", null);
        }
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            return new DatabaseFileRef("file", Path.of(decoded).toAbsolutePath().normalize());
        } catch (Exception e) {
            return new DatabaseFileRef("unknown", null);
        }
    }

    private String sanitizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "-";
        }
        return jdbcUrl.replaceAll("(?i)(password|token|key|secret)=([^;&]+)", "$1=***");
    }

    private void addFileMetadata(Map<String, Object> metadata, String prefix, Path path) {
        metadata.put(prefix + "Path", path.toString());
        boolean exists = Files.exists(path);
        metadata.put(prefix + "Exists", exists);
        if (!exists) {
            metadata.put(prefix + "SizeBytes", 0L);
            return;
        }
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            metadata.put(prefix + "SizeBytes", attrs.size());
            metadata.put(prefix + "ModifiedAt", attrs.lastModifiedTime().toInstant().toString());
        } catch (IOException e) {
            metadata.put(prefix + "Readable", false);
            metadata.put(prefix + "ReadError", e.getClass().getSimpleName());
        }
    }

    private DiagnosticCheckInfo checkSchemaMigrations() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            try (var tableResult = statement.executeQuery("""
                    SELECT name FROM sqlite_master
                    WHERE type = 'table' AND name = 'flyway_schema_history'
                    """)) {
                if (!tableResult.next()) {
                    return check("schema-migrations", "数据迁移", WARN,
                            "未发现 Flyway 迁移历史，可能是新库尚未初始化",
                            Map.of("historyTable", false));
                }
            }

            var metadata = new LinkedHashMap<String, Object>();
            try (var countResult = statement.executeQuery("""
                    SELECT
                        COUNT(*) AS total,
                        SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed
                    FROM flyway_schema_history
                    """)) {
                if (countResult.next()) {
                    metadata.put("total", countResult.getLong("total"));
                    metadata.put("failed", countResult.getLong("failed"));
                }
            }
            addFailedMigrationMetadata(statement, metadata);

            try (var latestResult = statement.executeQuery("""
                    SELECT installed_rank, version, description, script, success, installed_on
                    FROM flyway_schema_history
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)) {
                if (!latestResult.next()) {
                    return check("schema-migrations", "数据迁移", WARN,
                            "Flyway 迁移历史为空", metadata);
                }
                boolean latestSuccess = latestResult.getInt("success") == 1;
                metadata.put("latestRank", latestResult.getInt("installed_rank"));
                metadata.put("latestVersion", latestResult.getString("version"));
                metadata.put("latestDescription", latestResult.getString("description"));
                metadata.put("latestScript", latestResult.getString("script"));
                metadata.put("latestSuccess", latestSuccess);
                metadata.put("latestInstalledOn", latestResult.getString("installed_on"));

                long failed = metadata.get("failed") instanceof Number value ? value.longValue() : 0L;
                if (!latestSuccess || failed > 0) {
                    return check("schema-migrations", "数据迁移", ERROR,
                            "存在失败的数据库迁移，请检查启动日志和 Flyway 历史", metadata);
                }
                return check("schema-migrations", "数据迁移", OK,
                        "数据库迁移历史正常", metadata);
            }
        }
    }

    private void addFailedMigrationMetadata(java.sql.Statement statement,
                                            Map<String, Object> metadata) throws Exception {
        var failedMigrations = new ArrayList<Map<String, Object>>();
        try (var failedResult = statement.executeQuery("""
                SELECT installed_rank, version, description, script, installed_on
                FROM flyway_schema_history
                WHERE success = 0
                ORDER BY installed_rank DESC
                LIMIT 5
                """)) {
            while (failedResult.next()) {
                failedMigrations.add(mapOf(
                        "rank", failedResult.getInt("installed_rank"),
                        "version", failedResult.getString("version"),
                        "description", failedResult.getString("description"),
                        "script", failedResult.getString("script"),
                        "installedOn", failedResult.getString("installed_on")
                ));
            }
        }
        metadata.put("failedMigrationSamples", failedMigrations);
        metadata.put("failedMigrationScripts", failedMigrations.stream()
                .map(item -> String.valueOf(item.get("script")))
                .filter(script -> !script.isBlank() && !"null".equals(script))
                .toList());
    }

    private DiagnosticCheckInfo checkDesktopDistribution() throws Exception {
        var metadata = new LinkedHashMap<String, Object>();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        metadata.put("workingDirectory", workingDirectory.toString());
        metadata.put("backendVersion", resolveBackendVersion(workingDirectory));
        metadata.put("codeSource", resolveCodeSource());
        metadata.put("runningFromJar", isRunningFromJar());

        Path tauriConfig = findFirstExisting(
                workingDirectory.resolve("zhiwei-web/src-tauri/tauri.conf.json"),
                workingDirectory.resolve("src-tauri/tauri.conf.json")
        );
        if (tauriConfig == null) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "未发现桌面端打包配置，当前可能是纯后端或开发运行", metadata);
        }

        JsonNode root = objectMapper.readTree(tauriConfig.toFile());
        metadata.put("tauriConfig", tauriConfig.toString());
        metadata.put("productName", root.path("productName").asText("-"));
        metadata.put("identifier", root.path("identifier").asText("-"));
        metadata.put("tauriVersion", root.path("version").asText("-"));
        boolean bundleActive = root.path("bundle").path("active").asBoolean(false);
        metadata.put("bundleActive", bundleActive);
        metadata.put("bundleTargets", readStringArray(root.path("bundle").path("targets")));

        Path webRoot = tauriConfig.getParent().getParent();
        Path packageJson = webRoot.resolve("package.json");
        String packageVersion = readPackageVersion(packageJson);
        String cargoVersion = readCargoPackageVersion(tauriConfig.getParent().resolve("Cargo.toml"));
        metadata.put("packageVersion", packageVersion);
        metadata.put("cargoVersion", cargoVersion);
        addDistributionScriptMetadata(root, packageJson, metadata);
        addBuildToolchainMetadata(metadata);

        boolean versionsAligned = versionsAligned(
                String.valueOf(metadata.get("backendVersion")),
                packageVersion,
                root.path("version").asText(null),
                cargoVersion
        );
        metadata.put("versionsAligned", versionsAligned);

        JsonNode updaterConfig = updaterConfigNode(root);
        boolean updaterConfigured = updaterConfig.isObject();
        boolean updaterDependency = hasText(tauriConfig.getParent().resolve("Cargo.toml"), "tauri-plugin-updater");
        metadata.put("updaterConfigured", updaterConfigured);
        metadata.put("updaterDependency", updaterDependency);
        addUpdaterConfigurationMetadata(root, updaterConfig, metadata);
        addEmbeddedJreMetadata(tauriConfig.getParent(), metadata);

        Path bundleDirectory = tauriConfig.getParent().resolve("target/release/bundle").normalize();
        metadata.put("packageArtifactDirectory", bundleDirectory.toString());
        List<DistributionArtifactInfo> artifacts = listDistributionArtifacts(bundleDirectory);
        metadata.put("packageArtifactCount", artifacts.size());
        artifacts.stream()
                .max(Comparator.comparing(DistributionArtifactInfo::lastModified))
                .ifPresent(latest -> {
                    metadata.put("latestPackageArtifact", latest.path().getFileName().toString());
                    metadata.put("latestPackageArtifactPath", latest.path().toString());
                    metadata.put("latestPackageArtifactSizeBytes", latest.sizeBytes());
                    metadata.put("latestPackageArtifactModifiedAt", latest.lastModified().toString());
                });

        if (!versionsAligned) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "桌面端版本号未同步，发布前需要对齐 pom、package、Tauri 和 Cargo", metadata);
        }
        if (!bundleActive) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "桌面端打包未启用", metadata);
        }
        if (!Boolean.TRUE.equals(metadata.get("buildScriptsAligned"))) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "桌面端构建脚本未对齐，发布命令可能不能生成安装包", metadata);
        }
        if (!Boolean.TRUE.equals(metadata.get("buildToolchainReady"))) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "桌面端构建工具链不完整，打包前需要 Maven、Cargo 和 rustc 可用", metadata);
        }
        if (!Boolean.TRUE.equals(metadata.get("embeddedJreReady"))) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "Windows 安装包缺少可用的内嵌 JRE 22，当前打包会依赖用户本机 Java 或被发布检查拦截", metadata);
        }
        if (!Boolean.TRUE.equals(metadata.get("updaterReady"))) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "桌面端可打包，但自动更新配置不完整", metadata);
        }
        if (artifacts.isEmpty()) {
            return check("desktop-distribution", "安装与更新", WARN,
                    "桌面端配置可读取，但尚未发现安装包产物", metadata);
        }
        return check("desktop-distribution", "安装与更新", OK,
                "桌面端打包、产物和自动更新配置可读取", metadata);
    }

    private DiagnosticCheckInfo checkDataBackups() throws Exception {
        Path backupDirectory = zhiweiPaths.home(ZhiweiPaths.DIR_BACKUPS);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("directory", backupDirectory.toString());
        metadata.put("exists", Files.exists(backupDirectory));
        metadata.put("writable", Files.exists(backupDirectory) && Files.isWritable(backupDirectory));

        if (!Files.exists(backupDirectory)) {
            return check("data-backups", "数据备份", WARN,
                    "尚未发现备份目录，升级或迁移前建议先创建备份", metadata);
        }
        if (!Files.isWritable(backupDirectory)) {
            return check("data-backups", "数据备份", ERROR,
                    "备份目录不可写，无法可靠保存本地数据备份", metadata);
        }

        List<BackupFileInfo> backups = listBackupFiles(backupDirectory);
        long totalBytes = backups.stream().mapToLong(BackupFileInfo::sizeBytes).sum();
        metadata.put("fileCount", backups.size());
        metadata.put("totalBytes", totalBytes);
        backups.stream()
                .max(Comparator.comparing(BackupFileInfo::lastModified))
                .ifPresent(latest -> {
                    metadata.put("latestFile", latest.path().getFileName().toString());
                    metadata.put("latestModifiedAt", latest.lastModified().toString());
                    metadata.put("latestSizeBytes", latest.sizeBytes());
                });

        if (backups.isEmpty()) {
            return check("data-backups", "数据备份", WARN,
                    "尚未发现本地备份文件，升级或迁移前建议先备份 HOME 目录", metadata);
        }
        return check("data-backups", "数据备份", OK,
                "已发现本地备份文件", metadata);
    }

    private DiagnosticCheckInfo checkPaths() throws Exception {
        Path home = zhiweiPaths.home();
        Path workspace = zhiweiPaths.workspace();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("home", home.toString());
        metadata.put("homeExists", Files.exists(home));
        metadata.put("homeWritable", Files.isWritable(home));
        metadata.put("workspace", workspace.toString());
        metadata.put("workspaceExists", Files.exists(workspace));
        metadata.put("workspaceWritable", Files.isWritable(workspace));
        try {
            var fileStore = Files.getFileStore(home);
            metadata.put("usableBytes", fileStore.getUsableSpace());
            metadata.put("totalBytes", fileStore.getTotalSpace());
        } catch (Exception ignored) {
            metadata.put("diskSpaceReadable", false);
        }

        boolean ok = Boolean.TRUE.equals(metadata.get("homeExists"))
                && Boolean.TRUE.equals(metadata.get("homeWritable"))
                && Boolean.TRUE.equals(metadata.get("workspaceExists"))
                && Boolean.TRUE.equals(metadata.get("workspaceWritable"));
        return check("paths", "数据目录", ok ? OK : ERROR,
                ok ? "数据目录可读写" : "数据目录不可用或不可写", metadata);
    }

    private DiagnosticCheckInfo checkModelServices(Map<String, Object> counts) {
        List<ModelServiceEntity> services = modelServiceRepository.findAll();
        long enabled = services.stream().filter(ModelServiceEntity::enabled).count();
        long generationEnabled = services.stream()
                .filter(service -> service.enabled() && service.kind() == ModelServiceKind.GENERATION)
                .count();
        long embeddingEnabled = services.stream()
                .filter(service -> service.enabled() && service.kind() == ModelServiceKind.EMBEDDING)
                .count();
        long rerankEnabled = services.stream()
                .filter(service -> service.enabled() && service.kind() == ModelServiceKind.RERANK)
                .count();
        Map<String, Long> byKind = services.stream()
                .collect(Collectors.groupingBy(
                        service -> service.kind().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> enabledByKind = services.stream()
                .filter(ModelServiceEntity::enabled)
                .collect(Collectors.groupingBy(
                        service -> service.kind().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        Map<String, ModelServiceRuntimeHealth.ProviderHealth> healthByProviderId =
                modelServiceRuntimeHealth == null
                        ? Map.of()
                        : modelServiceRuntimeHealth.snapshot().stream().collect(Collectors.toMap(
                                ModelServiceRuntimeHealth.ProviderHealth::providerId,
                                health -> health,
                                (left, right) -> right,
                                LinkedHashMap::new));
        List<String> generationProviderIds = enabledServiceIds(services, ModelServiceKind.GENERATION);
        List<String> embeddingProviderIds = enabledServiceIds(services, ModelServiceKind.EMBEDDING);
        long generationUnhealthy = unhealthyEnabledProviderCount(generationProviderIds, healthByProviderId);
        long embeddingUnhealthy = unhealthyEnabledProviderCount(embeddingProviderIds, healthByProviderId);

        counts.put("modelServices.total", services.size());
        counts.put("modelServices.enabled", enabled);
        counts.put("modelServices.generationEnabled", generationEnabled);
        counts.put("modelServices.embeddingEnabled", embeddingEnabled);
        counts.put("modelServices.rerankEnabled", rerankEnabled);
        counts.put("modelServices.generationUnhealthy", generationUnhealthy);
        counts.put("modelServices.embeddingUnhealthy", embeddingUnhealthy);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("total", services.size());
        metadata.put("enabled", enabled);
        metadata.put("generationEnabled", generationEnabled);
        metadata.put("embeddingEnabled", embeddingEnabled);
        metadata.put("rerankEnabled", rerankEnabled);
        metadata.put("generationUnhealthy", generationUnhealthy);
        metadata.put("embeddingUnhealthy", embeddingUnhealthy);
        metadata.put("byKind", byKind);
        metadata.put("enabledByKind", enabledByKind);
        metadata.put("generationProviderIds", generationProviderIds);
        metadata.put("embeddingProviderIds", embeddingProviderIds);
        metadata.put("runtimeHealth", healthByProviderId.values().stream()
                .map(this::toProviderHealthMetadata)
                .toList());

        if (generationEnabled == 0) {
            return check("model-services", "模型服务", WARN,
                    "没有启用的生成模型服务，主对话可能无法回答", metadata);
        }
        if (generationUnhealthy > 0) {
            return check("model-services", "模型服务", WARN,
                    "生成模型服务启动预热异常，主对话可能不稳定", metadata);
        }
        if (embeddingEnabled == 0) {
            return check("model-services", "模型服务", WARN,
                    "主对话模型可用；未启用向量服务，知识库、记忆和语义召回会降级", metadata);
        }
        if (embeddingUnhealthy > 0) {
            return check("model-services", "模型服务", WARN,
                    "主对话模型可用；向量服务启动预热异常，知识库、记忆和语义召回会降级",
                    metadata);
        }
        return check("model-services", "模型服务", OK,
                "生成模型和向量服务已启用，主对话与记忆/知识库增强可用", metadata);
    }

    private List<String> enabledServiceIds(List<ModelServiceEntity> services, ModelServiceKind kind) {
        return services.stream()
                .filter(ModelServiceEntity::enabled)
                .filter(service -> service.kind() == kind)
                .map(ModelServiceEntity::id)
                .toList();
    }

    private long unhealthyEnabledProviderCount(
            List<String> providerIds,
            Map<String, ModelServiceRuntimeHealth.ProviderHealth> healthByProviderId) {
        return providerIds.stream()
                .map(healthByProviderId::get)
                .filter(health -> health != null
                        && health.state() == ModelServiceRuntimeHealth.HealthState.UNHEALTHY)
                .count();
    }

    private Map<String, Object> toProviderHealthMetadata(ModelServiceRuntimeHealth.ProviderHealth health) {
        return mapOf(
                "providerId", health.providerId(),
                "capabilities", health.capabilities(),
                "state", health.state().name(),
                "detail", health.detail(),
                "checkedAt", health.checkedAt().toString()
        );
    }

    private DiagnosticCheckInfo checkIntelligence(Map<String, Object> counts) {
        boolean enabled = environment.getProperty("lifepilot.intelligence.enabled", Boolean.class, true);
        boolean decisionSignalEnabled = environment.getProperty(
                "lifepilot.intelligence.decision-signal-enabled", Boolean.class, true);
        long experienceMatchTimeoutMs = environment.getProperty(
                "lifepilot.intelligence.experience-match-timeout-ms", Long.class, 0L);
        long experienceMatchForegroundWaitCapMs = environment.getProperty(
                "lifepilot.intelligence.experience-match-foreground-wait-cap-ms", Long.class, 80L);
        long experienceMatchBackgroundTimeoutMs = environment.getProperty(
                "lifepilot.intelligence.experience-match-background-timeout-ms", Long.class, 1200L);
        long effectiveExperienceMatchTimeoutMs = effectiveExperienceMatchTimeoutMs(
                experienceMatchTimeoutMs,
                experienceMatchForegroundWaitCapMs);
        String experienceMatchTrigger = environment.getProperty(
                "lifepilot.intelligence.experience-match-trigger", "task-like");
        int experienceMatchMinGoalChars = environment.getProperty(
                "lifepilot.intelligence.experience-match-min-goal-chars", Integer.class, 6);
        int experienceMatchMaxGoalChars = environment.getProperty(
                "lifepilot.intelligence.experience-match-max-goal-chars", Integer.class, 240);
        int experienceMatchMaxPending = environment.getProperty(
                "lifepilot.intelligence.experience-match-max-pending", Integer.class, 1);
        int experienceMatchRecentTtlSeconds = environment.getProperty(
                "lifepilot.intelligence.experience-match-recent-ttl-seconds", Integer.class, 300);
        int experienceMatchRecentMax = environment.getProperty(
                "lifepilot.intelligence.experience-match-recent-max", Integer.class, 8);
        long decisionSignalContextTimeoutMs = environment.getProperty(
                "lifepilot.agent.context.decision-signal-timeout-ms", Long.class, 0L);
        int maxPendingToolExperienceRecords = environment.getProperty(
                "lifepilot.agent.loop.max-pending-tool-experience-records", Integer.class, 4);
        long toolExperienceRecordTimeoutMs = environment.getProperty(
                "lifepilot.agent.loop.tool-experience-record-timeout-ms", Long.class, 1200L);
        boolean capabilityDiscoveryEnabled = environment.getProperty(
                "lifepilot.agent.capability-discovery.enabled", Boolean.class, true);
        int capabilityDiscoveryControlPrefixChars = environment.getProperty(
                "lifepilot.agent.capability-discovery.control-prefix-chars", Integer.class, 96);
        int capabilityDiscoveryPlanningProbeMaxChars = environment.getProperty(
                "lifepilot.agent.capability-discovery.planning-probe-max-chars", Integer.class, 320);

        counts.put("intelligence.enabled", enabled);
        counts.put("intelligence.decisionSignalEnabled", decisionSignalEnabled);
        counts.put("intelligence.experienceMatchTimeoutMs", experienceMatchTimeoutMs);
        counts.put("intelligence.effectiveExperienceMatchTimeoutMs", effectiveExperienceMatchTimeoutMs);
        counts.put("intelligence.experienceMatchBackgroundTimeoutMs", experienceMatchBackgroundTimeoutMs);
        counts.put("intelligence.experienceMatchTrigger", experienceMatchTrigger);
        counts.put("agentLoop.decisionSignalContextTimeoutMs", decisionSignalContextTimeoutMs);
        counts.put("agentLoop.maxPendingToolExperienceRecords", maxPendingToolExperienceRecords);
        counts.put("agentLoop.toolExperienceRecordTimeoutMs", toolExperienceRecordTimeoutMs);
        counts.put("agentLoop.capabilityDiscoveryEnabled", capabilityDiscoveryEnabled);
        counts.put("agentLoop.capabilityDiscoveryControlPrefixChars", capabilityDiscoveryControlPrefixChars);
        counts.put("agentLoop.capabilityDiscoveryPlanningProbeMaxChars", capabilityDiscoveryPlanningProbeMaxChars);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("enabled", enabled);
        metadata.put("decisionSignalEnabled", decisionSignalEnabled);
        metadata.put("experienceMatchTimeoutMs", experienceMatchTimeoutMs);
        metadata.put("experienceMatchForegroundWaitCapMs", experienceMatchForegroundWaitCapMs);
        metadata.put("experienceMatchBackgroundTimeoutMs", experienceMatchBackgroundTimeoutMs);
        metadata.put("effectiveExperienceMatchTimeoutMs", effectiveExperienceMatchTimeoutMs);
        metadata.put("experienceMatchTrigger", experienceMatchTrigger);
        metadata.put("experienceMatchMinGoalChars", experienceMatchMinGoalChars);
        metadata.put("experienceMatchMaxGoalChars", experienceMatchMaxGoalChars);
        metadata.put("experienceMatchMaxPending", experienceMatchMaxPending);
        metadata.put("experienceMatchRecentTtlSeconds", experienceMatchRecentTtlSeconds);
        metadata.put("experienceMatchRecentMax", experienceMatchRecentMax);
        metadata.put("decisionSignalContextTimeoutMs", decisionSignalContextTimeoutMs);
        metadata.put("maxPendingToolExperienceRecords", maxPendingToolExperienceRecords);
        metadata.put("toolExperienceRecordTimeoutMs", toolExperienceRecordTimeoutMs);
        metadata.put("capabilityDiscoveryEnabled", capabilityDiscoveryEnabled);
        metadata.put("capabilityDiscoveryControlPrefixChars", capabilityDiscoveryControlPrefixChars);
        metadata.put("capabilityDiscoveryPlanningProbeMaxChars", capabilityDiscoveryPlanningProbeMaxChars);

        if (!enabled) {
            return check("intelligence", "智能增强", WARN,
                    "智能层已关闭，主对话不会注入工具健康、环境和经验信号", metadata);
        }
        if (!decisionSignalEnabled) {
            return check("intelligence", "智能增强", WARN,
                    "决策信号已关闭，主对话不会获得经验、工具健康和环境提示", metadata);
        }
        if (experienceMatchTimeoutMs < 0 || experienceMatchForegroundWaitCapMs < 0
                || experienceMatchBackgroundTimeoutMs < 0
                || experienceMatchMaxPending < 0
                || experienceMatchMinGoalChars < 0 || experienceMatchMaxGoalChars < 20
                || decisionSignalContextTimeoutMs < 0
                || maxPendingToolExperienceRecords < 0 || toolExperienceRecordTimeoutMs < 0
                || capabilityDiscoveryControlPrefixChars <= 0
                || capabilityDiscoveryPlanningProbeMaxChars < 20) {
            return check("intelligence", "智能增强", ERROR,
                    "智能增强配置存在非法值，请检查 lifepilot.intelligence、lifepilot.agent.context 和 lifepilot.agent.capability-discovery 配置",
                    metadata);
        }
        if (effectiveExperienceMatchTimeoutMs > 0) {
            return check("intelligence", "智能增强", WARN,
                    "经验匹配最多等待 " + effectiveExperienceMatchTimeoutMs + "ms；慢结果会转入后台复用",
                    metadata);
        }
        if (decisionSignalContextTimeoutMs > 0) {
            return check("intelligence", "智能增强", WARN,
                    "决策信号最多等待 " + decisionSignalContextTimeoutMs + "ms；建议设为 0，让主对话只做后台热身",
                    metadata);
        }
        if (maxPendingToolExperienceRecords > 0 && toolExperienceRecordTimeoutMs == 0) {
            return check("intelligence", "智能增强", WARN,
                    "工具经验记录未设置后台超时，慢探针可能长期占用后台名额",
                    metadata);
        }
        if ("disabled".equalsIgnoreCase(experienceMatchTrigger)) {
            if (!capabilityDiscoveryEnabled) {
                return check("intelligence", "智能增强", OK,
                        "经验匹配与能力预发现均已关闭，主对话不会做前置意图探针", metadata);
            }
            return check("intelligence", "智能增强", OK,
                    "经验匹配已关闭，主对话只使用工具健康和环境信号", metadata);
        }
        return check("intelligence", "智能增强", OK,
                "经验匹配和决策信号不占用主对话等待，慢结果只做后台增强，能力预发现仅使用轻量规则探针", metadata);
    }

    private long effectiveExperienceMatchTimeoutMs(long configuredTimeoutMs, long foregroundWaitCapMs) {
        if (configuredTimeoutMs <= 0 || foregroundWaitCapMs <= 0) {
            return 0;
        }
        return Math.min(configuredTimeoutMs, foregroundWaitCapMs);
    }

    private DiagnosticCheckInfo checkKnowledgeBases(Map<String, Object> counts) {
        if (knowledgeBaseRepository == null) {
            counts.put("knowledgeBases.total", 0);
            counts.put("knowledgeBases.documents", 0L);
            counts.put("knowledgeBases.chunks", 0L);
            return check("knowledge-bases", "知识库", WARN,
                    "知识库模块未启用，资料检索能力暂不可用",
                    mapOf("enabled", false, "total", 0, "documents", 0L, "chunks", 0L));
        }
        var knowledgeBases = knowledgeBaseRepository.findAll();
        long documents = knowledgeBases.stream().mapToLong(kb -> Math.max(0, kb.documentCount())).sum();
        long chunks = knowledgeBases.stream().mapToLong(kb -> Math.max(0, kb.totalChunks())).sum();
        counts.put("knowledgeBases.total", knowledgeBases.size());
        counts.put("knowledgeBases.documents", documents);
        counts.put("knowledgeBases.chunks", chunks);
        return check("knowledge-bases", "知识库", OK,
                knowledgeBases.isEmpty() ? "尚未建立知识库" : "知识库索引可读取",
                mapOf("total", knowledgeBases.size(), "documents", documents, "chunks", chunks));
    }

    private DiagnosticCheckInfo checkSessions(Map<String, Object> counts) {
        var sessions = chatSessionRepository.findAll();
        counts.put("sessions.total", sessions.size());
        long archived = sessions.stream().filter(session -> Boolean.TRUE.equals(session.archived())).count();
        return check("sessions", "会话", OK, "会话索引可读取",
                mapOf("total", sessions.size(), "archived", archived));
    }

    private DiagnosticCheckInfo checkCapabilities(Map<String, Object> counts) {
        List<SkillDefinition> skills = skillRegistry == null ? List.of() : skillRegistry.listAll();
        List<ToolContract> tools = toolRegistry == null ? List.of() : toolRegistry.getToolSnapshot();
        int skillCount = skills.size();
        int toolCount = tools.size();
        Set<String> registeredToolIds = tools.stream()
                .map(ToolContract::id)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long highRiskTools = tools.stream()
                .filter(tool -> {
                    RiskLevel riskLevel = tool.riskLevel();
                    return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
                })
                .count();
        Map<String, Long> toolCategories = tools.stream()
                .collect(Collectors.groupingBy(
                        tool -> tool.category() == null ? "UNKNOWN" : tool.category().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        var skillToolReferences = inspectSkillToolReferences(skills, registeredToolIds);

        counts.put("skills.total", skillCount);
        counts.put("tools.total", toolCount);
        counts.put("skills.suggestedToolReferences", skillToolReferences.total());
        counts.put("skills.registeredToolReferences", skillToolReferences.registered());
        counts.put("skills.canonicalToolReferences", skillToolReferences.canonical());
        counts.put("skills.unknownToolReferences", skillToolReferences.unknown());
        counts.put("skills.missingCanonicalToolReferences", skillToolReferences.missingCanonical());
        counts.put("tools.highRisk", highRiskTools);

        var metadata = mapOf(
                "skills", skillCount,
                "tools", toolCount,
                "skillRegistryEnabled", skillRegistry != null,
                "toolRegistryEnabled", toolRegistry != null,
                "registeredToolIdSamples", registeredToolIds.stream()
                        .limit(DIAGNOSTIC_SAMPLE_LIMIT)
                        .toList(),
                "skillSuggestedToolReferences", skillToolReferences.total(),
                "registeredSkillToolReferences", skillToolReferences.registered(),
                "canonicalSkillToolReferences", skillToolReferences.canonical(),
                "unknownSkillToolReferences", skillToolReferences.unknown(),
                "missingCanonicalSkillToolReferences", skillToolReferences.missingCanonical(),
                "unknownSkillToolReferenceSamples", skillToolReferences.unknownSamples(),
                "missingCanonicalSkillToolReferenceSamples", skillToolReferences.missingCanonicalSamples(),
                "highRiskTools", highRiskTools,
                "toolCategories", toolCategories
        );

        if (skillRegistry == null || toolRegistry == null) {
            return check("capabilities", "工具和技能", WARN,
                    "工具或技能能力未启用，复杂任务执行能力会受限",
                    metadata);
        }
        String status;
        String detail;
        if (toolCount == 0) {
            status = WARN;
            detail = "没有可用工具，复杂任务执行能力会受限";
        } else if (skillToolReferences.unknown() > 0 && skillToolReferences.missingCanonical() > 0) {
            status = WARN;
            detail = "部分技能引用了未知工具，且有核心工具当前不可用，相关任务会降级或需要修复";
        } else if (skillToolReferences.unknown() > 0) {
            status = WARN;
            detail = "部分技能引用了未知工具，执行前需要修正 Skill 元数据";
        } else if (skillToolReferences.missingCanonical() > 0) {
            status = WARN;
            detail = "部分技能引用的核心工具当前不可用，相关任务会降级或需要修复";
        } else {
            status = OK;
            detail = "工具和技能可用，技能引用的工具可被识别";
        }
        return check("capabilities", "工具和技能", status, detail, metadata);
    }

    private SkillToolReferenceInspection inspectSkillToolReferences(List<SkillDefinition> skills,
                                                                    Set<String> registeredToolIds) {
        int total = 0;
        int registered = 0;
        int canonical = 0;
        int unknown = 0;
        int missingCanonical = 0;
        var unknownSamples = new ArrayList<String>();
        var missingCanonicalSamples = new ArrayList<String>();

        for (SkillDefinition skill : skills) {
            for (String rawToolId : skill.suggestedTools()) {
                String toolId = rawToolId == null ? "" : rawToolId.trim();
                if (toolId.isBlank()) {
                    continue;
                }
                total++;
                boolean registeredTool = registeredToolIds.contains(toolId);
                boolean canonicalTool = SkillToolReferenceCatalog.isCanonicalToolId(toolId);
                if (registeredTool) {
                    registered++;
                }
                if (canonicalTool) {
                    canonical++;
                }
                if (!registeredTool && canonicalTool) {
                    missingCanonical++;
                    addDiagnosticSample(missingCanonicalSamples, skill.id() + " -> " + toolId);
                } else if (!registeredTool) {
                    unknown++;
                    addDiagnosticSample(unknownSamples, skill.id() + " -> " + toolId);
                }
            }
        }

        return new SkillToolReferenceInspection(
                total,
                registered,
                canonical,
                unknown,
                missingCanonical,
                List.copyOf(unknownSamples),
                List.copyOf(missingCanonicalSamples)
        );
    }

    private void addDiagnosticSample(List<String> samples, String value) {
        if (samples.size() < DIAGNOSTIC_SAMPLE_LIMIT) {
            samples.add(value);
        }
    }

    private DiagnosticCheckInfo checkWorkflows(Map<String, Object> counts) {
        if (workflowRegistry == null) {
            counts.put("workflows.total", 0);
            counts.put("workflows.enabled", 0);
            return check("workflows", "工作流", WARN,
                    "工作流模块未启用，自动化编排能力暂不可用",
                    mapOf("enabled", false, "total", 0, "active", 0));
        }
        int total = workflowRegistry.listAll().size();
        int enabled = workflowRegistry.listEnabled().size();
        counts.put("workflows.total", total);
        counts.put("workflows.enabled", enabled);
        return check("workflows", "工作流", OK,
                total == 0 ? "尚未加载工作流" : "工作流注册表可读取",
                mapOf("total", total, "enabled", enabled));
    }

    private DiagnosticCheckInfo checkMcp(Map<String, Object> counts) {
        if (mcpServerRegistry == null) {
            return check("mcp", "MCP 服务", WARN, "MCP 注册中心未启用", Map.of());
        }
        List<McpServerEntry> servers = mcpServerRegistry.listServers();
        long available = servers.stream().filter(server -> server.state().isAvailable()).count();
        long withError = servers.stream()
                .filter(server -> server.lastError() != null && !server.lastError().isBlank())
                .count();
        counts.put("mcpServers.total", servers.size());
        counts.put("mcpServers.available", available);
        counts.put("mcpServers.withError", withError);
        String status = withError > 0 ? WARN : OK;
        String detail = withError > 0 ? "部分 MCP 服务最近出现错误" : "MCP 服务状态可读取";
        return check("mcp", "MCP 服务", status, detail,
                mapOf("total", servers.size(), "available", available, "withError", withError));
    }

    private DiagnosticCheckInfo checkPythonRuntime() {
        if (pythonRuntimeManager == null) {
            return check("python-runtime", "Python 运行时", WARN, "Python 运行时模块未启用", Map.of());
        }
        Map<String, Object> metadata = RuntimeStatusJson.toMap(pythonRuntimeManager.checkStatus());
        Object statusValue = metadata.get("status");
        String runtimeStatus = statusValue == null ? "UNKNOWN" : statusValue.toString();
        String status = switch (runtimeStatus) {
            case "READY" -> OK;
            case "INSTALL_FAILED" -> ERROR;
            default -> WARN;
        };
        String detail = switch (runtimeStatus) {
            case "READY" -> "Python 运行时可用";
            case "INSTALL_FAILED" -> "Python 运行时安装失败";
            case "INSTALLING" -> "Python 运行时正在安装";
            case "DISABLED" -> "Python 运行时已禁用";
            case "NOT_INSTALLED" -> "Python 运行时尚未安装";
            default -> "Python 运行时状态未知";
        };
        return check("python-runtime", "Python 运行时", status, detail, metadata);
    }

    private DiagnosticCheckInfo safeCheck(String id, String label, ThrowingSupplier<DiagnosticCheckInfo> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return check(id, label, ERROR, "诊断读取失败：" + e.getMessage(),
                    mapOf("exception", e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> buildAppInfo() {
        return mapOf(
                "name", environment.getProperty("spring.application.name", "zhiwei"),
                "version", resolveBackendVersion(Path.of("").toAbsolutePath().normalize()),
                "configVersion", environment.getProperty("lifepilot.config-version", "unknown")
        );
    }

    private Map<String, Object> buildRuntimeInfo() {
        return mapOf(
                "javaVersion", System.getProperty("java.version"),
                "javaVendor", System.getProperty("java.vendor"),
                "osName", System.getProperty("os.name"),
                "osVersion", System.getProperty("os.version"),
                "osArch", System.getProperty("os.arch"),
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "maxMemoryBytes", Runtime.getRuntime().maxMemory(),
                "home", zhiweiPaths.home().toString(),
                "workspace", zhiweiPaths.workspace().toString()
        );
    }

    private void collectHints(List<DiagnosticCheckInfo> checks, List<String> hints) {
        for (DiagnosticCheckInfo check : checks) {
            if (OK.equals(check.status())) continue;
            hints.add(check.label() + "：" + check.detail());
        }
    }

    private String summarizeStatus(List<DiagnosticCheckInfo> checks) {
        if (checks.stream().anyMatch(check -> ERROR.equals(check.status()))) {
            return ERROR;
        }
        if (checks.stream().anyMatch(check -> WARN.equals(check.status()))) {
            return WARN;
        }
        return OK;
    }

    private DiagnosticCheckInfo check(String id,
                                      String label,
                                      String status,
                                      String detail,
                                      Map<String, Object> metadata) {
        var safeMetadata = new LinkedHashMap<String, Object>();
        metadata.forEach((key, value) -> safeMetadata.put(key, value == null ? "-" : value));
        return new DiagnosticCheckInfo(id, label, status, detail, Map.copyOf(safeMetadata));
    }

    private String resolveBackendVersion(Path workingDirectory) {
        String implementationVersion = LocalDiagnosticService.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            return implementationVersion;
        }
        String pomVersion = readPomVersion(workingDirectory.resolve("pom.xml"));
        return pomVersion != null ? pomVersion : "unknown";
    }

    private String resolveCodeSource() {
        try {
            var codeSource = LocalDiagnosticService.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return "-";
            }
            return Path.of(codeSource.getLocation().toURI()).toString();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    private boolean isRunningFromJar() {
        try {
            var codeSource = LocalDiagnosticService.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return false;
            }
            Path path = Path.of(codeSource.getLocation().toURI());
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }

    private Path findFirstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    private JsonNode updaterConfigNode(JsonNode tauriRoot) {
        JsonNode pluginConfig = tauriRoot.path("plugins").path("updater");
        if (pluginConfig.isObject()) {
            return pluginConfig;
        }
        JsonNode legacyConfig = tauriRoot.path("updater");
        return legacyConfig.isObject() ? legacyConfig : objectMapper.createObjectNode();
    }

    private void addUpdaterConfigurationMetadata(JsonNode tauriRoot,
                                                 JsonNode updaterConfig,
                                                 Map<String, Object> metadata) {
        JsonNode artifacts = tauriRoot.path("bundle").path("createUpdaterArtifacts");
        List<String> endpoints = readStringArray(updaterConfig.path("endpoints"));
        boolean artifactsConfigured = updaterArtifactsConfigured(artifacts);
        boolean pubkeyConfigured = !updaterConfig.path("pubkey").asText("").isBlank();
        boolean endpointsConfigured = !endpoints.isEmpty();

        metadata.put("updaterArtifactsConfigured", artifactsConfigured);
        metadata.put("updaterArtifactsMode", updaterArtifactsMode(artifacts));
        metadata.put("updaterPubkeyConfigured", pubkeyConfigured);
        metadata.put("updaterEndpointCount", endpoints.size());
        metadata.put("updaterEndpointsConfigured", endpointsConfigured);
        metadata.put("updaterInstallMode",
                updaterConfig.path("windows").path("installMode").asText("default"));
        metadata.put("updaterReady",
                Boolean.TRUE.equals(metadata.get("updaterDependency"))
                        && Boolean.TRUE.equals(metadata.get("updaterConfigured"))
                        && artifactsConfigured
                        && pubkeyConfigured
                        && endpointsConfigured);
    }

    private boolean updaterArtifactsConfigured(JsonNode artifacts) {
        if (artifacts == null || artifacts.isMissingNode() || artifacts.isNull()) {
            return false;
        }
        if (artifacts.isBoolean()) {
            return artifacts.asBoolean();
        }
        if (artifacts.isTextual()) {
            return !artifacts.asText("").isBlank();
        }
        return true;
    }

    private String updaterArtifactsMode(JsonNode artifacts) {
        if (artifacts == null || artifacts.isMissingNode() || artifacts.isNull()) {
            return "missing";
        }
        if (artifacts.isTextual()) {
            String value = artifacts.asText("").strip();
            return value.isBlank() ? "missing" : value;
        }
        if (artifacts.isBoolean()) {
            return artifacts.asBoolean() ? "true" : "false";
        }
        return artifacts.toString();
    }

    private String readPackageVersion(Path packageJson) {
        if (!Files.exists(packageJson)) {
            return "unknown";
        }
        try {
            return objectMapper.readTree(packageJson.toFile()).path("version").asText("unknown");
        } catch (Exception e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private void addDistributionScriptMetadata(JsonNode tauriRoot,
                                               Path packageJson,
                                               Map<String, Object> metadata) {
        Map<String, String> scripts = readPackageScripts(packageJson);
        String beforeBuildCommand = tauriRoot.path("build").path("beforeBuildCommand").asText("");
        String prepareScript = scripts.getOrDefault("tauri:prepare", "");
        String buildScript = scripts.getOrDefault("tauri:build", "");
        String windowsBuildScript = scripts.getOrDefault("tauri:build:windows", "");

        boolean beforeBuildUsesPrepare = "npm run tauri:prepare".equals(beforeBuildCommand);
        boolean prepareScriptValid = containsAll(prepareScript, "prepare-backend.mjs", "vue-tsc", "vite build");
        boolean buildScriptValid = containsAll(buildScript, "check-tauri-build-env.mjs", "tauri build");
        boolean windowsBuildScriptValid = containsAll(windowsBuildScript,
                "check-tauri-build-env.mjs", "tauri build", "--bundles nsis", "--ci");
        boolean buildScriptsAligned = beforeBuildUsesPrepare
                && prepareScriptValid
                && buildScriptValid
                && windowsBuildScriptValid;

        metadata.put("beforeBuildCommand", beforeBuildCommand.isBlank() ? "unknown" : beforeBuildCommand);
        metadata.put("tauriPrepareScript", prepareScript.isBlank() ? "missing" : prepareScript);
        metadata.put("tauriBuildScript", buildScript.isBlank() ? "missing" : buildScript);
        metadata.put("tauriWindowsBuildScript", windowsBuildScript.isBlank() ? "missing" : windowsBuildScript);
        metadata.put("beforeBuildUsesPrepare", beforeBuildUsesPrepare);
        metadata.put("tauriPrepareScriptValid", prepareScriptValid);
        metadata.put("tauriBuildScriptValid", buildScriptValid);
        metadata.put("tauriWindowsBuildScriptValid", windowsBuildScriptValid);
        metadata.put("buildScriptsAligned", buildScriptsAligned);
        metadata.put("backendBuildCommand", "mvn clean package -DskipTests");
        metadata.put("frontendBuildCommand", "cd zhiwei-web && npm run build");
        metadata.put("desktopBuildCommand", "cd zhiwei-web && npm run tauri:build");
        metadata.put("windowsBuildCommand", "cd zhiwei-web && npm run tauri:build:windows");
        metadata.put("embeddedJrePrepareCommand", "cd zhiwei-web && npm run tauri:prepare:jre");
    }

    private void addEmbeddedJreMetadata(Path tauriRoot, Map<String, Object> metadata) {
        Path jreDirectory = tauriRoot.resolve("resources").resolve("jre").toAbsolutePath().normalize();
        Path javaExecutable = jreDirectory.resolve("bin").resolve(windows() ? "java.exe" : "java");
        JavaRuntimeProbe probe = probeJavaRuntime(javaExecutable);
        metadata.put("embeddedJreDirectory", jreDirectory.toString());
        metadata.put("embeddedJreJava", javaExecutable.toString());
        metadata.put("embeddedJreAvailable", probe.available());
        metadata.put("embeddedJreVersion", probe.detail());
        metadata.put("embeddedJreMajorVersion", probe.majorVersion());
        metadata.put("embeddedJreReady", probe.available() && probe.majorVersion() >= 22);
        metadata.put("embeddedJreRequiredForWindowsBuild", true);
    }

    private void addBuildToolchainMetadata(Map<String, Object> metadata) {
        CommandProbe maven = probeCommandVersion(windows() ? "mvn.cmd" : "mvn", "--version");
        CommandProbe cargo = probeCommandVersion("cargo", "--version");
        CommandProbe rustc = probeCommandVersion("rustc", "--version");
        metadata.put("mavenCliAvailable", maven.available());
        metadata.put("mavenCliVersion", maven.detail());
        metadata.put("cargoCliAvailable", cargo.available());
        metadata.put("cargoCliVersion", cargo.detail());
        metadata.put("rustcCliAvailable", rustc.available());
        metadata.put("rustcCliVersion", rustc.detail());
        metadata.put("buildToolchainReady", maven.available() && cargo.available() && rustc.available());
    }

    private boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private CommandProbe probeCommandVersion(String command, String... args) {
        var commandLine = new ArrayList<String>();
        commandLine.add(command);
        commandLine.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(commandLine)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandProbe(false, "timeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0) {
                return new CommandProbe(true, output.isBlank() ? "available" : firstLine(output));
            }
            return new CommandProbe(false, output.isBlank() ? "exit=" + process.exitValue() : firstLine(output));
        } catch (IOException e) {
            return new CommandProbe(false, "missing");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandProbe(false, "interrupted");
        }
    }

    private JavaRuntimeProbe probeJavaRuntime(Path javaExecutable) {
        if (!Files.isRegularFile(javaExecutable)) {
            return new JavaRuntimeProbe(false, "missing", -1);
        }
        try {
            Process process = new ProcessBuilder(javaExecutable.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new JavaRuntimeProbe(false, "timeout", -1);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String detail = output.isBlank() ? "available" : firstLine(output);
            int majorVersion = parseJavaMajorVersion(output);
            return new JavaRuntimeProbe(process.exitValue() == 0 && majorVersion >= 0, detail, majorVersion);
        } catch (IOException e) {
            return new JavaRuntimeProbe(false, "missing", -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new JavaRuntimeProbe(false, "interrupted", -1);
        }
    }

    private int parseJavaMajorVersion(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        var matcher = Pattern.compile("version\\s+\"([^\"]+)\"").matcher(text);
        if (!matcher.find()) {
            return -1;
        }
        String version = matcher.group(1);
        String major = version.startsWith("1.")
                ? version.substring(2).split("\\.")[0]
                : version.split("\\.")[0];
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String firstLine(String text) {
        int newlineIndex = text.indexOf('\n');
        return newlineIndex >= 0 ? text.substring(0, newlineIndex).trim() : text.trim();
    }

    private Map<String, String> readPackageScripts(Path packageJson) {
        if (!Files.exists(packageJson)) {
            return Map.of();
        }
        try {
            JsonNode scriptsNode = objectMapper.readTree(packageJson.toFile()).path("scripts");
            if (!scriptsNode.isObject()) {
                return Map.of();
            }
            var scripts = new LinkedHashMap<String, String>();
            scriptsNode.fields().forEachRemaining(entry ->
                    scripts.put(entry.getKey(), entry.getValue().asText(""))
            );
            return Map.copyOf(scripts);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean containsAll(String text, String... expectedParts) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String expectedPart : expectedParts) {
            if (!text.contains(expectedPart)) {
                return false;
            }
        }
        return true;
    }

    private String readCargoPackageVersion(Path cargoToml) {
        if (!Files.exists(cargoToml)) {
            return "unknown";
        }
        try {
            String content = Files.readString(cargoToml);
            var matcher = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"").matcher(content);
            return matcher.find() ? matcher.group(1) : "unknown";
        } catch (Exception e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private String readPomVersion(Path pomXml) {
        if (!Files.exists(pomXml)) {
            return null;
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(pomXml.toFile());
            var root = document.getDocumentElement();
            var children = root.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                var child = children.item(index);
                if ("version".equals(child.getNodeName()) || "version".equals(child.getLocalName())) {
                    String value = child.getTextContent();
                    return value == null || value.isBlank() ? null : value.trim();
                }
            }
            return null;
        } catch (Exception e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    private boolean versionsAligned(String... versions) {
        String baseline = null;
        for (String version : versions) {
            if (version == null || version.isBlank() || version.startsWith("unknown") || version.startsWith("unreadable")) {
                return false;
            }
            String normalized = normalizeVersion(version);
            if (baseline == null) {
                baseline = normalized;
            } else if (!baseline.equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeVersion(String version) {
        return version.trim()
                .replaceFirst("(?i)-SNAPSHOT$", "")
                .replaceFirst("^v", "");
    }

    private boolean hasText(Path file, String text) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return Files.readString(file).contains(text);
        } catch (Exception e) {
            return false;
        }
    }

    private List<DistributionArtifactInfo> listDistributionArtifacts(Path bundleDirectory) throws IOException {
        if (!Files.exists(bundleDirectory)) {
            return List.of();
        }
        try (var stream = Files.walk(bundleDirectory, 5)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::looksLikeDistributionArtifact)
                    .map(this::toDistributionArtifactInfo)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    private boolean looksLikeDistributionArtifact(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".exe")
                || name.endsWith(".msi")
                || name.endsWith(".dmg")
                || name.endsWith(".deb")
                || name.endsWith(".rpm")
                || name.endsWith(".pkg")
                || name.endsWith(".appimage")
                || name.endsWith(".tar.gz")
                || name.endsWith(".zip");
    }

    private List<DistributionArtifactInfo> toDistributionArtifactInfo(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return List.of(new DistributionArtifactInfo(
                    file.toAbsolutePath().normalize(),
                    attrs.size(),
                    attrs.lastModifiedTime().toInstant()
            ));
        } catch (IOException e) {
            return List.of();
        }
    }

    private Map<String, Object> buildDiagnosticBundleManifest(Instant createdAt,
                                                              DiagnosticReportInfo report,
                                                              List<LogSnippet> logSnippets) {
        return mapOf(
                "formatVersion", "1",
                "createdAt", createdAt.toString(),
                "reportStatus", report.status(),
                "reportSummary", report.summary(),
                "summaryEntry", "diagnostic-summary.txt",
                "home", zhiweiPaths.home().toString(),
                "workspace", zhiweiPaths.workspace().toString(),
                "logTailBytesPerFile", DIAGNOSTIC_LOG_TAIL_BYTES,
                "includedLogs", logSnippets.stream()
                        .map(snippet -> mapOf(
                                "fileName", snippet.fileName(),
                                "path", snippet.path().toString(),
                                "sizeBytes", snippet.sizeBytes(),
                                "truncated", snippet.truncated()
                        ))
                        .toList()
        );
    }

    private String buildDiagnosticSummaryText(Instant createdAt,
                                              DiagnosticReportInfo report,
                                              List<LogSnippet> logSnippets) {
        var lines = new ArrayList<String>();
        lines.add("[知微诊断摘要]");
        lines.add("生成时间: " + createdAt);
        lines.add("报告时间: " + report.generatedAt());
        lines.add("状态: " + report.status() + " - " + report.summary());
        lines.add("应用: " + compactMapLine(report.app(), "name", "version", "configVersion"));
        lines.add("运行时: " + compactMapLine(report.runtime(), "javaVersion", "osName", "home", "workspace"));
        lines.add("");

        lines.add("问题检查:");
        List<DiagnosticCheckInfo> problemChecks = report.checks().stream()
                .filter(check -> !OK.equalsIgnoreCase(check.status()))
                .toList();
        if (problemChecks.isEmpty()) {
            lines.add("- 无");
        } else {
            problemChecks.forEach(check -> lines.add("- [" + check.status() + "] "
                    + check.label() + "(" + check.id() + "): " + check.detail()
                    + compactProblemMetadata(check)));
        }
        lines.add("");

        lines.add("建议:");
        if (report.hints().isEmpty()) {
            lines.add("- 暂无额外建议");
        } else {
            report.hints().forEach(hint -> lines.add("- " + hint));
        }
        lines.add("");

        lines.add("日志:");
        if (logSnippets.isEmpty()) {
            lines.add("- 未包含日志");
        } else {
            for (int index = 0; index < logSnippets.size(); index++) {
                LogSnippet snippet = logSnippets.get(index);
                lines.add("- " + diagnosticLogEntryName(index, snippet)
                        + " <- " + snippet.path()
                        + " (" + snippet.sizeBytes() + " bytes"
                        + (snippet.truncated() ? ", 已截断" : "")
                        + ")");
            }
        }
        lines.add("");

        lines.add("检查清单:");
        report.checks().forEach(check -> lines.add("- "
                + check.label() + "(" + check.id() + ")=" + check.status()));
        return String.join("\n", lines) + "\n";
    }

    private String compactMapLine(Map<String, Object> metadata, String... keys) {
        return List.of(keys).stream()
                .map(key -> {
                    Object value = metadata.get(key);
                    return value == null ? null : key + "=" + value;
                })
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("; "));
    }

    private String compactProblemMetadata(DiagnosticCheckInfo check) {
        Map<String, Object> metadata = check.metadata();
        return switch (check.id()) {
            case "desktop-distribution" -> compactMetadataSuffix(metadata,
                    "buildToolchainReady",
                    "embeddedJreReady",
                    "updaterReady",
                    "packageArtifactCount",
                    "desktopBuildCommand",
                    "windowsBuildCommand");
            case "capabilities" -> compactMetadataSuffix(metadata,
                    "skillSuggestedToolReferences",
                    "unknownSkillToolReferences",
                    "missingCanonicalSkillToolReferences",
                    "missingCanonicalSkillToolReferenceSamples",
                    "unknownSkillToolReferenceSamples");
            case "model-services" -> compactMetadataSuffix(metadata,
                    "generationEnabled",
                    "generationUnhealthy",
                    "embeddingEnabled",
                    "embeddingUnhealthy");
            case "schema-migrations" -> compactMetadataSuffix(metadata,
                    "total",
                    "failed",
                    "latestVersion",
                    "latestScript",
                    "failedMigrationScripts");
            default -> "";
        };
    }

    private String compactMetadataSuffix(Map<String, Object> metadata, String... keys) {
        String detail = compactMapLine(metadata, keys);
        return detail.isBlank() ? "" : " [" + detail + "]";
    }

    private String diagnosticLogEntryName(int index, LogSnippet snippet) {
        return "logs/" + (index + 1) + "-" + sanitizeZipEntryName(snippet.fileName()) + ".tail.log";
    }

    private List<LogSnippet> collectLogSnippets() throws IOException {
        Path logDirectory = zhiweiPaths.home(ZhiweiPaths.DIR_LOGS).toAbsolutePath().normalize();
        if (!Files.exists(logDirectory)) {
            return List.of();
        }

        try (var stream = Files.list(logDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::looksLikeDiagnosticLog)
                    .map(this::toLogFileInfo)
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(LogFileInfo::lastModified).reversed())
                    .limit(DIAGNOSTIC_LOG_LIMIT)
                    .map(this::toLogSnippet)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    private boolean looksLikeDiagnosticLog(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".log") || name.endsWith(".txt");
    }

    private List<LogFileInfo> toLogFileInfo(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return List.of(new LogFileInfo(
                    file.toAbsolutePath().normalize(),
                    attrs.size(),
                    attrs.lastModifiedTime().toInstant()
            ));
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<LogSnippet> toLogSnippet(LogFileInfo file) {
        try {
            long start = Math.max(0, file.sizeBytes() - DIAGNOSTIC_LOG_TAIL_BYTES);
            int bufferSize = Math.toIntExact(file.sizeBytes() - start);
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            try (SeekableByteChannel channel = Files.newByteChannel(file.path(), StandardOpenOption.READ)) {
                channel.position(start);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // 持续读取到尾部或缓冲区满。
                }
            }
            String content = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            boolean truncated = start > 0;
            if (truncated) {
                content = "[日志已截断，仅保留最后 " + DIAGNOSTIC_LOG_TAIL_BYTES + " 字节]\n" + content;
            }
            return List.of(new LogSnippet(
                    file.path().getFileName().toString(),
                    file.path(),
                    file.sizeBytes(),
                    truncated,
                    content
            ));
        } catch (Exception e) {
            return List.of(new LogSnippet(
                    file.path().getFileName().toString(),
                    file.path(),
                    file.sizeBytes(),
                    false,
                    "读取日志失败：" + e.getMessage()
            ));
        }
    }

    private void writeJsonEntry(ZipOutputStream output,
                                String entryName,
                                Object value,
                                AtomicInteger fileCount) throws IOException {
        writeBytesEntry(output, entryName, objectMapper.copy()
                        .findAndRegisterModules()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(value),
                fileCount);
    }

    private void writeTextEntry(ZipOutputStream output,
                                String entryName,
                                String content,
                                AtomicInteger fileCount) throws IOException {
        writeBytesEntry(output, entryName, content.getBytes(StandardCharsets.UTF_8), fileCount);
    }

    private void writeBytesEntry(ZipOutputStream output,
                                 String entryName,
                                 byte[] content,
                                 AtomicInteger fileCount) throws IOException {
        output.putNextEntry(new ZipEntry(entryName));
        output.write(content);
        output.closeEntry();
        fileCount.incrementAndGet();
    }

    private String sanitizeZipEntryName(String fileName) {
        String safeName = fileName.replace('\\', '_').replace('/', '_');
        return safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private List<BackupFileInfo> listBackupFiles(Path backupDirectory) throws IOException {
        try (var stream = Files.walk(backupDirectory, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::looksLikeBackupFile)
                    .map(this::toBackupFileInfo)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    private boolean looksLikeBackupFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip")
                || name.endsWith(".db")
                || name.endsWith(".sqlite")
                || name.endsWith(".sqlite3")
                || name.endsWith(".tar")
                || name.endsWith(".tar.gz")
                || name.endsWith(".tgz")
                || name.endsWith(".7z");
    }

    private List<BackupFileInfo> toBackupFileInfo(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return List.of(new BackupFileInfo(file, attrs.size(), attrs.lastModifiedTime().toInstant()));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return map;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record BackupFileInfo(Path path, long sizeBytes, Instant lastModified) {
    }

    private record DistributionArtifactInfo(Path path, long sizeBytes, Instant lastModified) {
    }

    private record LogFileInfo(Path path, long sizeBytes, Instant lastModified) {
    }

    private record LogSnippet(String fileName, Path path, long sizeBytes, boolean truncated, String content) {
    }

    private record DatabaseFileRef(String location, @Nullable Path path) {
    }

    private record SkillToolReferenceInspection(int total,
                                                int registered,
                                                int canonical,
                                                int unknown,
                                                int missingCanonical,
                                                List<String> unknownSamples,
                                                List<String> missingCanonicalSamples) {
    }

    private record CommandProbe(boolean available, String detail) {
    }

    private record JavaRuntimeProbe(boolean available, String detail, int majorVersion) {
    }
}
