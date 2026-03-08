package com.lifepilot.marketplace.install;

import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.InstalledExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 扩展安装策略密封接口 — 按扩展类型分派下载、注册、卸载逻辑。
 *
 * <p>三种实现分别对应 Skill / Agent / Workflow 三种扩展类型，
 * 由 {@code ExtensionInstaller} 根据 {@code ExtensionType} 分派调用。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public sealed interface InstallStrategy
        permits SkillInstallStrategy, AgentInstallStrategy, WorkflowInstallStrategy {

    /**
     * 下载远程文件到本地目标目录，返回本地路径。
     *
     * @param pkg        扩展包元数据
     * @param restClient HTTP 客户端
     * @return 下载后的本地路径
     * @throws IOException 下载或写入失败时抛出
     */
    Path download(ExtensionPackage pkg, RestClient restClient) throws IOException;

    /**
     * 注册到对应 Registry，使扩展立即可用。
     *
     * @param localPath 本地文件/文件夹路径
     * @param pkg       扩展包元数据
     */
    void register(Path localPath, ExtensionPackage pkg);

    /**
     * 注销并删除本地文件。
     *
     * @param installed 已安装扩展记录
     */
    void uninstall(InstalledExtension installed);
}
