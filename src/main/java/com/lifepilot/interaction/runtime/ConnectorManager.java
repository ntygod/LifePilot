package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.config.ConnectorManagerProperties;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 官方 connector 托管器。
 *
 * <p>优先复用用户显式配置的 {@code baseUrl}；当实例未配置地址且插件声明支持官方托管时，
 * 主服务会自动尝试拉起本地 connector 进程并回填可访问地址。</p>
 *
 * @author zsg
 * @since 2026-03-30
 */
public class ConnectorManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectorManager.class);

    private static final Map<String, ManagedConnectorDefaults> OFFICIAL_DEFAULTS = Map.of(
            "feishu", new ManagedConnectorDefaults("dist/feishu-connector.jar", "connectors/feishu-connector", 19091, "/actuator/health"),
            "wecom", new ManagedConnectorDefaults("dist/wecom-connector.jar", "connectors/wecom-connector", 19092, "/actuator/health"),
            "dingtalk", new ManagedConnectorDefaults("dist/dingtalk-connector.jar", "connectors/dingtalk-connector", 19093, "/actuator/health"),
            "qq", new ManagedConnectorDefaults("dist/qq-connector.jar", "connectors/qq-connector", 19094, "/actuator/health")
    );

    /** 已安装 JAR 启动策略。 */
    static final String STRATEGY_INSTALLED_JAR = "installed-jar";
    /** 工作区 Maven 启动策略。 */
    static final String STRATEGY_WORKSPACE_MAVEN = "workspace-maven";

    private final ConnectorManagerProperties properties;
    private final Environment environment;
    private final RestClient restClient;
    @Nullable
    private final InstalledExtensionRepository installedExtensionRepository;
    private final ConcurrentHashMap<String, ManagedConnectorProcess> managedProcesses = new ConcurrentHashMap<>();

    public ConnectorManager(ConnectorManagerProperties properties,
                            Environment environment,
                            RestClient restClient,
                            @Nullable InstalledExtensionRepository installedExtensionRepository) {
        this.properties = properties;
        this.environment = environment;
        this.restClient = restClient;
        this.installedExtensionRepository = installedExtensionRepository;
    }

    public ChannelPluginDescriptor decorate(ChannelPluginDescriptor descriptor) {
        if (descriptor.connectorMode() != ConnectorMode.EXTERNAL) {
            return descriptor;
        }
        Optional<ManagedConnectorSpec> managedSpec = resolveManagedSpec(descriptor);
        if (managedSpec.isEmpty()) {
            return descriptor;
        }
        Map<String, Object> connectorSpec = descriptor.connectorSpec() != null
                ? new LinkedHashMap<>(descriptor.connectorSpec())
                : new LinkedHashMap<>();
        Map<String, Object> managed = readManagedConfig(descriptor.connectorSpec());
        ManagedConnectorSpec spec = managedSpec.get();
        ConnectorLaunchTarget launchTarget = resolveLaunchTarget(spec).orElse(null);
        managed.putIfAbsent("strategy", spec.strategy());
        if (spec.artifactPath() != null && !spec.artifactPath().isBlank()) {
            managed.putIfAbsent("artifactPath", spec.artifactPath());
        }
        if (spec.workspace() != null && !spec.workspace().isBlank()) {
            managed.putIfAbsent("workspace", spec.workspace());
        }
        managed.putIfAbsent("healthPath", spec.healthPath());
        managed.putIfAbsent("preferredPort", spec.preferredPort());
        managed.put("available", spec.available());
        managed.put("resolution", launchTarget != null ? launchTarget.resolution() : "unavailable");
        connectorSpec.put("managed", Map.copyOf(managed));
        return new ChannelPluginDescriptor(
                descriptor.pluginId(),
                descriptor.name(),
                descriptor.version(),
                descriptor.vendor(),
                descriptor.platform(),
                descriptor.connectorMode(),
                Map.copyOf(connectorSpec),
                descriptor.capabilities(),
                descriptor.configSchema(),
                descriptor.secretFields(),
                descriptor.setupGuide(),
                descriptor.resources(),
                descriptor.operationDescriptors()
        );
    }

    @Nullable
    public synchronized String resolveBaseUrl(ChannelInstance instance,
                                 ChannelPluginDescriptor descriptor,
                                 boolean ensureStarted) {
        String manualBaseUrl = readManualBaseUrl(instance.config());
        if (manualBaseUrl != null) {
            return manualBaseUrl;
        }
        if (!properties.isEnabled() || !properties.isAutoManageOfficial()) {
            return null;
        }
        ManagedConnectorSpec spec = resolveManagedSpec(descriptor)
                .orElse(null);
        if (spec == null) {
            return null;
        }
        if (!spec.available()) {
            if (ensureStarted) {
                throw new IllegalStateException(
                        "官方 connector 当前不可自动托管，请配置 Connector Base URL，或设置 lifepilot.gateway.channels.connector-manager.workspace-root 后重试: pluginId="
                                + descriptor.pluginId());
            }
            return buildBaseUrl(spec.preferredPort());
        }
        if (!ensureStarted) {
            ManagedConnectorProcess existing = managedProcesses.get(descriptor.pluginId());
            if (existing != null && existing.process().isAlive()) {
                return existing.baseUrl();
            }
            return buildBaseUrl(spec.preferredPort());
        }
        return ensureProcess(spec).baseUrl();
    }

    public boolean isManagedAvailable(ChannelPluginDescriptor descriptor) {
        return resolveManagedSpec(descriptor)
                .map(ManagedConnectorSpec::available)
                .orElse(false);
    }

    @PreDestroy
    public void shutdown() {
        managedProcesses.values().forEach(this::destroyProcessQuietly);
        managedProcesses.clear();
    }

    private synchronized ManagedConnectorProcess ensureProcess(ManagedConnectorSpec spec) {
        ManagedConnectorProcess existing = managedProcesses.get(spec.pluginId());
        if (existing != null && existing.process().isAlive()) {
            return existing;
        }

        ConnectorLaunchTarget launchTarget = resolveLaunchTarget(spec)
                .orElseThrow(() -> new IllegalStateException("未找到可启动的官方 connector 产物: pluginId=" + spec.pluginId()));
        int port = existing != null ? existing.port() : allocatePort(spec);
        String baseUrl = buildBaseUrl(port);
        List<String> command = buildCommand(launchTarget, port);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(launchTarget.workingDirectory().toFile());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            ManagedConnectorProcess managedProcess = new ManagedConnectorProcess(
                    spec.pluginId(),
                    spec,
                    launchTarget.launchPath(),
                    port,
                    baseUrl,
                    process,
                    Instant.now()
            );
            managedProcesses.put(spec.pluginId(), managedProcess);
            streamLogs(managedProcess);
            waitUntilHealthy(managedProcess);
            log.info("官方 connector 已自动托管: pluginId={}, baseUrl={}, mode={}, launchPath={}",
                    spec.pluginId(), baseUrl, launchTarget.resolution(), launchTarget.launchPath());
            return managedProcess;
        } catch (IOException e) {
            throw new IllegalStateException("启动官方 connector 失败: pluginId=" + spec.pluginId(), e);
        } catch (RuntimeException e) {
            ManagedConnectorProcess failed = managedProcesses.remove(spec.pluginId());
            if (failed != null) {
                destroyProcessQuietly(failed);
            }
            throw e;
        }
    }

    private void waitUntilHealthy(ManagedConnectorProcess managedProcess) {
        Duration timeout = properties.getStartupTimeout();
        Instant deadline = Instant.now().plus(timeout);
        String healthUrl = managedProcess.baseUrl() + managedProcess.spec().healthPath();
        RuntimeException lastError = null;

        while (Instant.now().isBefore(deadline)) {
            if (!managedProcess.process().isAlive()) {
                throw new IllegalStateException("官方 connector 启动后提前退出: pluginId=" + managedProcess.pluginId());
            }
            try {
                restClient.get()
                        .uri(healthUrl)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RuntimeException e) {
                lastError = e;
                sleepSilently(1000);
            }
        }
        throw new IllegalStateException(
                "官方 connector 启动超时，健康检查未通过: pluginId=%s, baseUrl=%s"
                        .formatted(managedProcess.pluginId(), managedProcess.baseUrl()),
                lastError
        );
    }

    private void streamLogs(ManagedConnectorProcess managedProcess) {
        Thread.ofVirtual()
                .name("connector-log-" + managedProcess.pluginId())
                .start(() -> {
                    try (var reader = new BufferedReader(new InputStreamReader(
                            managedProcess.process().getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            log.info("[connector:{}] {}", managedProcess.pluginId(), line);
                        }
                    } catch (IOException e) {
                        log.debug("读取 connector 日志结束: pluginId={}, error={}",
                                managedProcess.pluginId(), e.getMessage());
                    }
                });
    }

    private void destroyProcessQuietly(ManagedConnectorProcess managedProcess) {
        Process process = managedProcess.process();
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.info("官方 connector 已停止: pluginId={}", managedProcess.pluginId());
    }

    private List<String> buildCommand(ConnectorLaunchTarget launchTarget, int port) {
        if (STRATEGY_INSTALLED_JAR.equals(launchTarget.launchMode())) {
            return List.of(
                    javaCommand(),
                    "-jar",
                    launchTarget.launchPath().toString(),
                    "--server.port=%d".formatted(port),
                    "--connector.runtime.zhiwei-base-url=%s".formatted(runtimeBaseUrl())
            );
        }
        String mavenCommand = isWindows() ? "mvn.cmd" : "mvn";
        return List.of(
                mavenCommand,
                "-q",
                "spring-boot:run",
                "-Dspring-boot.run.arguments=--server.port=%d --connector.runtime.zhiwei-base-url=%s"
                        .formatted(port, runtimeBaseUrl())
        );
    }

    private String runtimeBaseUrl() {
        if (properties.getZhiweiBaseUrl() != null && !properties.getZhiweiBaseUrl().isBlank()) {
            return trimTrailingSlash(properties.getZhiweiBaseUrl());
        }
        String port = environment.getProperty("local.server.port");
        if (port == null || port.isBlank() || "0".equals(port)) {
            port = environment.getProperty("server.port", "8080");
        }
        return "http://127.0.0.1:" + port;
    }

    private Optional<ManagedConnectorSpec> resolveManagedSpec(ChannelPluginDescriptor descriptor) {
        if (descriptor.connectorMode() != ConnectorMode.EXTERNAL) {
            return Optional.empty();
        }

        ManagedConnectorDefaults defaults = defaultSpec(descriptor);
        Map<String, Object> managed = readManagedConfig(descriptor.connectorSpec());
        if (managed.isEmpty() && defaults == null) {
            return Optional.empty();
        }

        String strategy = textValue(managed.get("strategy"));
        String artifactPath = textValue(managed.get("artifactPath"));
        if ((artifactPath == null || artifactPath.isBlank()) && defaults != null) {
            artifactPath = defaults.artifactPath();
        }
        String workspace = textValue(managed.get("workspace"));
        if ((workspace == null || workspace.isBlank()) && defaults != null) {
            workspace = defaults.workspace();
        }
        if (strategy == null) {
            if (artifactPath != null && !artifactPath.isBlank()) {
                strategy = STRATEGY_INSTALLED_JAR;
            } else if (defaults != null || (workspace != null && !workspace.isBlank())) {
                strategy = STRATEGY_WORKSPACE_MAVEN;
            }
        }
        String healthPath = textValue(managed.get("healthPath"));
        if ((healthPath == null || healthPath.isBlank()) && defaults != null) {
            healthPath = defaults.healthPath();
        }
        int preferredPort = intValue(managed.get("preferredPort"), defaults != null ? defaults.preferredPort() : properties.getPortRangeStart());

        if (strategy == null || (artifactPath == null || artifactPath.isBlank()) && (workspace == null || workspace.isBlank())) {
            return Optional.empty();
        }

        ManagedConnectorSpec spec = new ManagedConnectorSpec(
                descriptor.pluginId(),
                strategy,
                artifactPath,
                workspace,
                healthPath != null && !healthPath.isBlank() ? healthPath : "/actuator/health",
                preferredPort,
                false
        );

        return Optional.of(new ManagedConnectorSpec(
                spec.pluginId(),
                spec.strategy(),
                spec.artifactPath(),
                spec.workspace(),
                spec.healthPath(),
                spec.preferredPort(),
                resolveLaunchTarget(spec).isPresent()
        ));
    }

    @Nullable
    private ManagedConnectorDefaults defaultSpec(ChannelPluginDescriptor descriptor) {
        if (!isOfficialPlugin(descriptor)) {
            return null;
        }
        return OFFICIAL_DEFAULTS.get(descriptor.pluginId());
    }

    private boolean isOfficialPlugin(ChannelPluginDescriptor descriptor) {
        if (descriptor.vendor() == null || descriptor.vendor().isBlank()) {
            return false;
        }
        return "zhiwei".equalsIgnoreCase(descriptor.vendor())
                || "zhiwei-official".equalsIgnoreCase(descriptor.vendor());
    }

    private Optional<ConnectorLaunchTarget> resolveLaunchTarget(ManagedConnectorSpec spec) {
        if (STRATEGY_INSTALLED_JAR.equals(spec.strategy())) {
            Optional<Path> installedArtifact = resolveInstalledArtifactPath(spec.pluginId(), spec.artifactPath());
            if (installedArtifact.isPresent()) {
                Path launchPath = installedArtifact.get();
                return Optional.of(new ConnectorLaunchTarget(
                        "installed-jar",
                        "installed-artifact",
                        launchPath,
                        launchPath.getParent() != null ? launchPath.getParent() : launchPath.toAbsolutePath().getParent()
                ));
            }
            if (spec.workspace() != null && !spec.workspace().isBlank()) {
                return resolveWorkspacePath(spec.workspace())
                        .map(path -> new ConnectorLaunchTarget(
                                "workspace-maven",
                                "workspace-fallback",
                                path,
                                path
                        ));
            }
            return Optional.empty();
        }
        if (STRATEGY_WORKSPACE_MAVEN.equals(spec.strategy()) && spec.workspace() != null && !spec.workspace().isBlank()) {
            return resolveWorkspacePath(spec.workspace())
                    .map(path -> new ConnectorLaunchTarget(
                            "workspace-maven",
                            "workspace",
                            path,
                            path
                    ));
        }
        return Optional.empty();
    }

    private Map<String, Object> readManagedConfig(@Nullable Map<String, Object> connectorSpec) {
        if (connectorSpec == null) {
            return new LinkedHashMap<>();
        }
        Object managed = connectorSpec.get("managed");
        if (!(managed instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    @Nullable
    private String readManualBaseUrl(Map<String, Object> config) {
        Object rawBaseUrl = config.get("baseUrl");
        if (rawBaseUrl instanceof String value && !value.isBlank()) {
            return trimTrailingSlash(value);
        }
        Object rawConnectorBaseUrl = config.get("connectorBaseUrl");
        if (rawConnectorBaseUrl instanceof String value && !value.isBlank()) {
            return trimTrailingSlash(value);
        }
        return null;
    }

    private int allocatePort(ManagedConnectorSpec spec) {
        if (isWithinRange(spec.preferredPort()) && isPortAvailable(spec.preferredPort())) {
            return spec.preferredPort();
        }
        for (int candidate = properties.getPortRangeStart(); candidate <= properties.getPortRangeEnd(); candidate++) {
            if (isPortAvailable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("没有可用的 connector 端口: pluginId=" + spec.pluginId());
    }

    private boolean isWithinRange(int port) {
        return port >= properties.getPortRangeStart() && port <= properties.getPortRangeEnd();
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<Path> resolveWorkspacePath(String workspace) {
        try {
            Path path = Path.of(workspace);
            if (path.isAbsolute()) {
                return isValidWorkspace(path) ? Optional.of(path.normalize()) : Optional.empty();
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        for (Path root : discoverWorkspaceRoots()) {
            Path candidate = root.resolve(workspace).normalize();
            if (isValidWorkspace(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<Path> discoverWorkspaceRoots() {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        if (properties.getWorkspaceRoot() != null && !properties.getWorkspaceRoot().isBlank()) {
            candidates.add(expandWorkspaceRoot(properties.getWorkspaceRoot()));
        }

        Path userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        candidates.add(userDir.resolveSibling("ZhiWei-plugins"));
        candidates.add(userDir.resolve("ZhiWei-plugins"));
        if (userDir.getParent() != null) {
            candidates.add(userDir.getParent().resolve("ZhiWei-plugins"));
        }
        return candidates.stream()
                .map(Path::normalize)
                .toList();
    }

    private Optional<Path> resolveInstalledArtifactPath(String pluginId, @Nullable String artifactPath) {
        if (installedExtensionRepository == null || artifactPath == null || artifactPath.isBlank()) {
            return Optional.empty();
        }
        return installedExtensionRepository.findByPackageId(pluginId)
                .flatMap(installed -> resolveInstalledArtifactFromInstallRoot(installed.installRootPath(), artifactPath));
    }

    private Optional<Path> resolveInstalledArtifactFromInstallRoot(String installRootPath, String artifactPath) {
        try {
            Path installRoot = Path.of(installRootPath).toAbsolutePath().normalize();
            Path candidate = installRoot.resolve(artifactPath).normalize();
            if (!candidate.startsWith(installRoot)) {
                return Optional.empty();
            }
            return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Path expandWorkspaceRoot(String raw) {
        String normalized = raw.replace("${user.home}", System.getProperty("user.home"));
        return Path.of(normalized).toAbsolutePath().normalize();
    }

    private boolean isValidWorkspace(Path path) {
        return Files.isDirectory(path) && Files.exists(path.resolve("pom.xml"));
    }

    private String buildBaseUrl(int port) {
        return "http://" + properties.getHost() + ":" + port;
    }

    private String javaCommand() {
        Path javaHome = Path.of(System.getProperty("java.home", ""));
        String executable = isWindows() ? "java.exe" : "java";
        Path candidate = javaHome.resolve("bin").resolve(executable);
        return Files.isRegularFile(candidate) ? candidate.toString() : "java";
    }

    private String trimTrailingSlash(String raw) {
        String value = raw.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Nullable
    private String textValue(@Nullable Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    private int intValue(@Nullable Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record ManagedConnectorDefaults(
            String artifactPath,
            String workspace,
            int preferredPort,
            String healthPath
    ) {
    }

    private record ConnectorLaunchTarget(
            String launchMode,
            String resolution,
            Path launchPath,
            Path workingDirectory
    ) {
    }

    private record ManagedConnectorProcess(
            String pluginId,
            ManagedConnectorSpec spec,
            Path launchPath,
            int port,
            String baseUrl,
            Process process,
            Instant startedAt
    ) {
    }
}
