package com.lifepilot.marketplace.install;

import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Workflow 安装策略 — 下载 YAML 工作流定义文件并注册到 WorkflowRegistry。
 *
 * <p>下载远程 {@code .yml} 文件到 {@code installDir/{packageId}.yml}，
 * 通过 {@link WorkflowYamlParser#parse(String)} 解析后，
 * 持久化到 {@link WorkflowRepository} 并注册到 {@link WorkflowRegistry}。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public final class WorkflowInstallStrategy implements InstallStrategy {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInstallStrategy.class);

    private final WorkflowYamlParser workflowYamlParser;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRegistry workflowRegistry;
    private final Path installDir;

    /**
     * 构造 Workflow 安装策略。
     *
     * @param workflowYamlParser Workflow YAML 解析器
     * @param workflowRepository Workflow 持久化仓库
     * @param workflowRegistry   Workflow 注册中心
     * @param installDir         Workflow 安装目录
     */
    public WorkflowInstallStrategy(WorkflowYamlParser workflowYamlParser,
                                   WorkflowRepository workflowRepository,
                                   WorkflowRegistry workflowRegistry,
                                   Path installDir) {
        this.workflowYamlParser = workflowYamlParser;
        this.workflowRepository = workflowRepository;
        this.workflowRegistry = workflowRegistry;
        this.installDir = installDir;
    }

    /**
     * 下载 Workflow YAML 文件到本地 {@code installDir/{packageId}.yml}。
     *
     * @param pkg        扩展包元数据
     * @param restClient HTTP 客户端
     * @return 下载后的 .yml 文件路径
     * @throws IOException 下载或写入失败时抛出
     */
    @Override
    public Path download(ExtensionPackage pkg, RestClient restClient) throws IOException {
        Files.createDirectories(installDir);
        Path targetFile = installDir.resolve(pkg.id() + ".yml");

        String downloadUrl = pkg.repoUrl() + "/" + pkg.filePath();
        log.info("下载 Workflow: packageId={}, url={}", pkg.id(), downloadUrl);

        String content = restClient.get()
                .uri(downloadUrl)
                .retrieve()
                .body(String.class);

        if (content == null || content.isBlank()) {
            throw new IOException("下载的 Workflow 定义文件内容为空: packageId=" + pkg.id());
        }

        Files.writeString(targetFile, content);

        log.info("Workflow 下载完成: packageId={}, path={}", pkg.id(), targetFile);
        return targetFile;
    }

    /**
     * 解析 YAML 内容，持久化到 WorkflowRepository 并注册到 WorkflowRegistry。
     *
     * @param localPath Workflow .yml 文件路径
     * @param pkg       扩展包元数据
     */
    @Override
    public void register(Path localPath, ExtensionPackage pkg) {
        String yamlContent;
        try {
            yamlContent = Files.readString(localPath);
        } catch (IOException e) {
            log.warn("读取 Workflow 文件失败: packageId={}, path={}, error={}",
                    pkg.id(), localPath, e.getMessage());
            return;
        }

        var parseResult = workflowYamlParser.parse(yamlContent);

        switch (parseResult) {
            case Result.Ok<?, ?> ok -> {
                var definition = (com.lifepilot.workflow.model.WorkflowDefinition) ok.value();
                // 持久化到数据库
                workflowRepository.saveDefinition(definition, yamlContent);
                // 注册到内存注册中心
                boolean registered = workflowRegistry.register(definition);
                if (registered) {
                    log.info("Workflow 注册成功: packageId={}, workflowId={}", pkg.id(), definition.id());
                } else {
                    log.warn("Workflow 注册被拒绝: packageId={}, workflowId={}", pkg.id(), definition.id());
                }
            }
            case Result.Err<?, ?> err -> {
                log.warn("Workflow YAML 解析失败: packageId={}, errors={}", pkg.id(), err.error());
            }
        }
    }

    /**
     * 注销 Workflow，标记删除并删除本地 .yml 文件。
     *
     * @param installed 已安装扩展记录
     */
    @Override
    public void uninstall(InstalledExtension installed) {
        // 注销内存注册
        workflowRegistry.unregister(installed.packageId());
        // 标记数据库记录为已删除
        workflowRepository.markDefinitionDeleted(installed.packageId());
        log.info("Workflow 注销成功: packageId={}", installed.packageId());

        // 删除 .yml 文件
        Path targetFile = installDir.resolve(installed.packageId() + ".yml");
        try {
            Files.deleteIfExists(targetFile);
        } catch (IOException e) {
            log.warn("删除 Workflow 文件失败: path={}, error={}", targetFile, e.getMessage());
        }
    }
}
