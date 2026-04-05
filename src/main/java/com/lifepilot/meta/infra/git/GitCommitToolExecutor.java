package com.lifepilot.meta.infra.git;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git Commit 工具执行器 — 执行 {@code git commit}，可选先暂存指定文件。
 *
 * <p>安全机制：提交前检查暂存区是否有变更，避免空提交。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitCommitToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitCommitToolExecutor.class);

    private final GitCommandExecutor gitCmd;

    public GitCommitToolExecutor(GitCommandExecutor gitCmd) {
        this.gitCmd = gitCmd;
    }

    /**
     * 执行 Git 提交。
     *
     * @param input 工具输入，必需参数 message，可选参数 path、files
     * @return 提交结果（hash、message、filesCommitted）
     */
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInput input) {
        try {
            String message;
            try {
                message = input.getParam("message", String.class);
            } catch (IllegalArgumentException e) {
                return ToolResult.error("缺少必需参数: message");
            }

            String pathStr = input.getOptionalParam("path", String.class)
                    .orElse(System.getProperty("user.dir"));
            Path repoPath = Path.of(pathStr).toAbsolutePath().normalize();

            if (!gitCmd.isGitRepo(repoPath)) {
                return ToolResult.error("指定路径不是 Git 仓库: " + repoPath);
            }

            // 如果指定了文件列表，先执行 git add
            var filesOpt = input.getOptionalParam("files", List.class);
            if (filesOpt.isPresent()) {
                List<String> files = (List<String>) filesOpt.get();
                if (!files.isEmpty()) {
                    var addArgs = new ArrayList<String>();
                    addArgs.add("add");
                    addArgs.addAll(files);
                    var addResult = gitCmd.execute(repoPath, addArgs.toArray(String[]::new));
                    if (!addResult.ok()) {
                        return ToolResult.error("git add 执行失败: " + addResult.stderr());
                    }
                }
            }

            // 检查暂存区是否有变更
            var statResult = gitCmd.execute(repoPath, "diff", "--cached", "--stat");
            if (statResult.ok() && statResult.stdout().isBlank()) {
                return ToolResult.error("暂存区没有变更，无法提交。请先使用 git add 暂存文件。");
            }

            // 获取暂存的文件列表
            var nameResult = gitCmd.execute(repoPath, "diff", "--cached", "--name-only");
            List<String> filesCommitted = new ArrayList<>();
            if (nameResult.ok() && !nameResult.stdout().isBlank()) {
                for (String line : nameResult.stdout().split("\n")) {
                    if (!line.isBlank()) {
                        filesCommitted.add(line.trim());
                    }
                }
            }

            // 执行提交
            var commitResult = gitCmd.execute(repoPath, "commit", "-m", message);
            if (!commitResult.ok()) {
                return ToolResult.error("git commit 执行失败: " + commitResult.stderr());
            }

            // 获取提交 hash
            var hashResult = gitCmd.execute(repoPath, "rev-parse", "HEAD");
            String hash = hashResult.ok() ? hashResult.stdout().trim() : "unknown";

            var data = new LinkedHashMap<String, Object>();
            data.put("hash", hash);
            data.put("message", message);
            data.put("filesCommitted", filesCommitted);
            return ToolResult.success(data);

        } catch (Exception e) {
            log.error("git commit 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git commit 执行失败: " + e.getMessage());
        }
    }
}
