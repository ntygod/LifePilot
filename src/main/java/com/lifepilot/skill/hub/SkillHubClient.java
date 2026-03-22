package com.lifepilot.skill.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 腾讯 SkillHub CLI 客户端 — 通过 skillhub 命令行工具搜索和安装 Skill。
 *
 * <p>SkillHub 提供 CLI 工具而非 REST API：
 * <ul>
 *   <li>{@code skillhub search <关键词>} — 搜索 Skill</li>
 *   <li>{@code skillhub install <名称>} — 安装 Skill 到当前 workspace</li>
 * </ul>
 *
 * <p>安装方式：{@code curl -fsSL https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/install/install.sh | bash -s -- --cli-only}</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public class SkillHubClient {

    private static final Logger log = LoggerFactory.getLogger(SkillHubClient.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final Path skillsDirectory;

    public SkillHubClient(String skillsDirectory) {
        this.skillsDirectory = Path.of(skillsDirectory);
    }

    /**
     * 搜索 SkillHub 中的 Skill。
     *
     * @param query 搜索关键词
     * @return 搜索结果文本，失败返回 null
     */
    public String search(String query) {
        return executeCommand("skillhub", "search", query);
    }

    /**
     * 安装 Skill 到用户 Skill 目录。
     *
     * @param skillName Skill 名称
     * @return 安装输出文本，失败返回 null
     */
    public String install(String skillName) {
        return executeCommand("skillhub", "install", skillName);
    }

    /**
     * 检查 skillhub CLI 是否已安装。
     *
     * @return 已安装返回 true
     */
    public boolean isAvailable() {
        try {
            var pb = new ProcessBuilder("skillhub", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 SkillHub CLI 安装命令。
     *
     * @param cliOnly 是否只安装 CLI（不安装默认 Skill）
     * @return 安装命令字符串
     */
    public static String getInstallCommand(boolean cliOnly) {
        String base = "curl -fsSL https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/install/install.sh | bash";
        return cliOnly ? base + " -s -- --cli-only" : base;
    }

    private String executeCommand(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(skillsDirectory.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("SkillHub CLI 命令超时: command={}", String.join(" ", command));
                return null;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                log.warn("SkillHub CLI 命令失败: command={}, exitCode={}, output={}",
                        String.join(" ", command), process.exitValue(), output);
                return null;
            }

            return output;

        } catch (IOException e) {
            log.debug("SkillHub CLI 不可用: command={}, error={}", String.join(" ", command), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
