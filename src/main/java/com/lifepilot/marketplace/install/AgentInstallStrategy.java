package com.lifepilot.marketplace.install;

import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.multiagent.loader.AgentMarkdownLoader;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Agent 安装策略 — 下载 Agent Markdown 定义文件并注册到 AgentRegistry。
 *
 * <p>下载远程 {@code .md} 文件到 {@code installDir/{packageId}.md}，
 * 通过 {@link AgentMarkdownLoader#loadFromFile(Path)} 解析后，
 * 将 source 替换为 {@link AgentSource.Marketplace} 再注册到 {@link AgentRegistry}。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public final class AgentInstallStrategy implements InstallStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentInstallStrategy.class);

    private final AgentMarkdownLoader agentMarkdownLoader;
    private final AgentRegistry agentRegistry;
    private final Path installDir;

    /**
     * 构造 Agent 安装策略。
     *
     * @param agentMarkdownLoader Agent Markdown 加载器
     * @param agentRegistry       Agent 注册中心
     * @param installDir          Agent 安装目录
     */
    public AgentInstallStrategy(AgentMarkdownLoader agentMarkdownLoader,
                                AgentRegistry agentRegistry,
                                Path installDir) {
        this.agentMarkdownLoader = agentMarkdownLoader;
        this.agentRegistry = agentRegistry;
        this.installDir = installDir;
    }

    /**
     * 下载 Agent 定义 .md 文件到本地 {@code installDir/{packageId}.md}。
     *
     * @param pkg        扩展包元数据
     * @param restClient HTTP 客户端
     * @return 下载后的 .md 文件路径
     * @throws IOException 下载或写入失败时抛出
     */
    @Override
    public Path download(ExtensionPackage pkg, RestClient restClient) throws IOException {
        Files.createDirectories(installDir);
        Path targetFile = installDir.resolve(pkg.id() + ".md");

        String downloadUrl = pkg.repoUrl() + "/" + pkg.filePath();
        log.info("下载 Agent: packageId={}, url={}", pkg.id(), downloadUrl);

        String content = restClient.get()
                .uri(downloadUrl)
                .retrieve()
                .body(String.class);

        if (content == null || content.isBlank()) {
            throw new IOException("下载的 Agent 定义文件内容为空: packageId=" + pkg.id());
        }

        Files.writeString(targetFile, content);

        log.info("Agent 下载完成: packageId={}, path={}", pkg.id(), targetFile);
        return targetFile;
    }

    /**
     * 通过 AgentMarkdownLoader 加载，替换 source 为 Marketplace 后注册到 AgentRegistry。
     *
     * @param localPath Agent .md 文件路径
     * @param pkg       扩展包元数据
     */
    @Override
    public void register(Path localPath, ExtensionPackage pkg) {
        var definitionOpt = agentMarkdownLoader.loadFromFile(localPath);
        if (definitionOpt.isEmpty()) {
            log.warn("Agent 加载失败，无法注册: packageId={}, path={}", pkg.id(), localPath);
            return;
        }

        // 替换 source 为 Marketplace（loadFromFile 返回的 source 为 MarkdownDefined）
        var definition = definitionOpt.get().toBuilder()
                .source(new AgentSource.Marketplace(pkg.id(), pkg.version()))
                .build();

        boolean registered = agentRegistry.register(definition);
        if (registered) {
            log.info("Agent 注册成功: packageId={}, agentId={}", pkg.id(), definition.id());
        } else {
            log.warn("Agent 注册被拒绝: packageId={}, agentId={}", pkg.id(), definition.id());
        }
    }

    /**
     * 注销 Agent 并删除本地 .md 文件。
     *
     * @param installed 已安装扩展记录
     */
    @Override
    public void uninstall(InstalledExtension installed) {
        // 注销 — 使用 packageId 作为 agentId（通常一致）
        agentRegistry.unregister(installed.packageId());
        log.info("Agent 注销成功: packageId={}", installed.packageId());

        // 删除 .md 文件
        Path targetFile = installDir.resolve(installed.packageId() + ".md");
        try {
            Files.deleteIfExists(targetFile);
        } catch (IOException e) {
            log.warn("删除 Agent 文件失败: path={}, error={}", targetFile, e.getMessage());
        }
    }
}
