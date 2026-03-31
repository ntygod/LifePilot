package com.lifepilot.marketplace.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.repository.ChannelPluginRepository;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.marketplace.security.ChannelPluginManifestValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 渠道插件安装策略。
 *
 * <p>当前先以下载并注册 {@code channel-plugin.json} 为主，
 * connector 运行时拉起由控制面后续阶段负责。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public final class ChannelInstallStrategy implements InstallStrategy {

    private static final Logger log = LoggerFactory.getLogger(ChannelInstallStrategy.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelPluginRepository channelPluginRepository;
    private final ChannelInstanceService channelInstanceService;
    private final ObjectMapper objectMapper;
    private final Path installDir;
    private final MarketplaceProperties properties;

    public ChannelInstallStrategy(ChannelRegistry channelRegistry,
                                  ChannelPluginRepository channelPluginRepository,
                                  ChannelInstanceService channelInstanceService,
                                  ObjectMapper objectMapper,
                                  Path installDir,
                                  MarketplaceProperties properties) {
        this.channelRegistry = channelRegistry;
        this.channelPluginRepository = channelPluginRepository;
        this.channelInstanceService = channelInstanceService;
        this.objectMapper = objectMapper;
        this.installDir = installDir;
        this.properties = properties;
    }

    @Override
    public Path download(ExtensionPackage pkg, RestClient restClient) throws IOException {
        Path pluginFolder = installDir.resolve(pkg.id());
        Path targetFile = pluginFolder.resolve("channel-plugin.json");
        String manifestUrl = buildDownloadUrl(pkg.repoUrl(), pkg.filePath());
        log.info("下载渠道插件 manifest: packageId={}, url={}", pkg.id(), manifestUrl);

        String content = downloadText(restClient, manifestUrl);

        if (content.isBlank()) {
            throw new IOException("下载的 channel-plugin.json 内容为空: packageId=" + pkg.id());
        }

        Files.createDirectories(pluginFolder);
        Files.writeString(targetFile, content);
        downloadResources(pkg, restClient, pluginFolder, content);
        return targetFile;
    }

    @Override
    public void register(Path localPath, ExtensionPackage pkg) {
        try {
            ChannelPluginDescriptor descriptor = objectMapper.readValue(
                    Files.readString(localPath), ChannelPluginDescriptor.class);
            var issues = ChannelPluginManifestValidator.validateForMarketplace(pkg, descriptor);
            if (!issues.isEmpty()) {
                String summary = issues.stream()
                        .map(issue -> "[%s] %s".formatted(issue.category(), issue.message()))
                        .collect(Collectors.joining("；"));
                throw new IllegalArgumentException("渠道插件 manifest 校验失败: " + summary);
            }
            channelPluginRepository.save(descriptor);
            channelRegistry.register(descriptor);
            log.info("渠道插件注册成功: pluginId={}, platform={}",
                    descriptor.pluginId(), descriptor.platform());
        } catch (Exception e) {
            throw new IllegalStateException("注册渠道插件失败: " + pkg.id(), e);
        }
    }

    @Override
    public void uninstall(InstalledExtension installed) {
        if (!channelInstanceService.listByPlugin(installed.packageId()).isEmpty()) {
            throw new IllegalStateException("渠道插件下仍存在实例，不能卸载: " + installed.packageId());
        }

        channelRegistry.unregister(installed.packageId());
        channelPluginRepository.deleteByPluginId(installed.packageId());
        Path pluginFolder = installDir.resolve(installed.packageId());
        deleteFolderQuietly(pluginFolder);
        log.info("渠道插件已卸载: pluginId={}", installed.packageId());
    }

    private void deleteFolderQuietly(Path folder) {
        try {
            if (Files.exists(folder)) {
                try (Stream<Path> walk = Files.walk(folder)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("删除渠道插件文件失败: path={}, error={}", path, e.getMessage());
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("删除渠道插件目录失败: folder={}, error={}", folder, e.getMessage());
        }
    }

    private void downloadResources(ExtensionPackage pkg,
                                   RestClient restClient,
                                   Path pluginFolder,
                                   String manifestContent) throws IOException {
        ChannelPluginDescriptor descriptor = objectMapper.readValue(manifestContent, ChannelPluginDescriptor.class);
        List<String> resourcePaths = descriptor.resources() != null
                ? descriptor.resources().referencedPaths()
                : List.of();
        for (String relativePath : resourcePaths) {
            Path safeRelativePath = normalizeRelativePath(relativePath);
            Path localTarget = pluginFolder.resolve(safeRelativePath).normalize();
            if (!localTarget.startsWith(pluginFolder)) {
                throw new IOException("渠道插件资源路径越界: " + relativePath);
            }
            if (localTarget.getParent() != null) {
                Files.createDirectories(localTarget.getParent());
            }
            String resourceUrl = buildDownloadUrl(pkg.repoUrl(), relativePath);
            byte[] payload = downloadBytes(restClient, resourceUrl);
            if (payload.length == 0) {
                throw new IOException("下载的渠道插件资源内容为空: packageId=%s, path=%s"
                        .formatted(pkg.id(), relativePath));
            }
            Files.write(localTarget, payload);
            log.info("渠道插件资源下载完成: packageId={}, path={}", pkg.id(), safeRelativePath);
        }
    }

    private String downloadText(RestClient restClient, String url) throws IOException {
        return downloadWithRetry(restClient, url, String.class);
    }

    private byte[] downloadBytes(RestClient restClient, String url) throws IOException {
        return downloadWithRetry(restClient, url, byte[].class);
    }

    private <T> T downloadWithRetry(RestClient restClient,
                                    String sourceUrl,
                                    Class<T> bodyType) throws IOException {
        List<String> candidateUrls = candidateUrls(sourceUrl);
        int maxAttempts = Math.max(1, properties.getHttp().getMaxAttempts());
        long backoffMillis = Math.max(0, properties.getHttp().getRetryBackoffMillis());
        IOException lastError = null;
        for (String candidateUrl : candidateUrls) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    T body = restClient.get()
                            .uri(candidateUrl)
                            .retrieve()
                            .body(bodyType);
                    if (body == null) {
                        throw new IOException("远程响应内容为空");
                    }
                    if (!Objects.equals(candidateUrl, sourceUrl)) {
                        log.info("渠道插件下载命中回退地址: sourceUrl={}, candidateUrl={}", sourceUrl, candidateUrl);
                    }
                    return body;
                } catch (Exception e) {
                    lastError = new IOException(
                            "下载远程资源失败: url=%s, attempt=%d/%d, error=%s"
                                    .formatted(candidateUrl, attempt, maxAttempts, e.getMessage()),
                            e
                    );
                    if (attempt < maxAttempts) {
                        log.warn("下载远程资源失败，准备重试: url={}, attempt={}/{}, error={}",
                                candidateUrl, attempt, maxAttempts, e.getMessage());
                        sleepQuietly(backoffMillis);
                    }
                }
            }
        }
        throw lastError != null ? lastError : new IOException("下载远程资源失败: " + sourceUrl);
    }

    private List<String> candidateUrls(String sourceUrl) {
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(sourceUrl);
        if (properties.getHttp().isEnableGithubMirrorFallback()) {
            String jsDelivrUrl = toJsDelivrUrl(sourceUrl);
            if (jsDelivrUrl != null && !candidates.contains(jsDelivrUrl)) {
                candidates.add(jsDelivrUrl);
            }
        }
        return List.copyOf(candidates);
    }

    private String toJsDelivrUrl(String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl);
            if (!"raw.githubusercontent.com".equalsIgnoreCase(uri.getHost())) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            String[] segments = normalized.split("/", 4);
            if (segments.length < 4) {
                return null;
            }
            return "https://cdn.jsdelivr.net/gh/%s/%s@%s/%s"
                    .formatted(segments[0], segments[1], segments[2], segments[3]);
        } catch (Exception e) {
            return null;
        }
    }

    private void sleepQuietly(long backoffMillis) throws IOException {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofMillis(backoffMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载重试等待被中断", e);
        }
    }

    private String buildDownloadUrl(String repoUrl, String relativePath) {
        return repoUrl + "/" + relativePath;
    }

    private Path normalizeRelativePath(String relativePath) throws IOException {
        try {
            Path normalized = Path.of(relativePath).normalize();
            String text = normalized.toString();
            if (normalized.isAbsolute()
                    || text.isBlank()
                    || text.startsWith("..")
                    || relativePath.startsWith("/")
                    || relativePath.startsWith("\\")
                    || relativePath.matches("^[A-Za-z]:.*")) {
                throw new IOException("非法渠道插件资源路径: " + relativePath);
            }
            return normalized;
        } catch (InvalidPathException e) {
            throw new IOException("非法渠道插件资源路径: " + relativePath, e);
        }
    }
}
