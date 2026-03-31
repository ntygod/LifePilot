package com.lifepilot.meta.infra.git;

import com.lifepilot.meta.config.MetaProperties;
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
 * Git Diff 工具执行器 — 执行 {@code git diff} 并按行截断输出。
 *
 * <p>支持 staged 参数查看暂存区差异，path 参数限定文件范围。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitDiffToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitDiffToolExecutor.class);

    private final GitCommandExecutor gitCmd;
    private final int maxDiffLines;

    public GitDiffToolExecutor(GitCommandExecutor gitCmd, MetaProperties.Infra.Git gitConfig) {
        this.gitCmd = gitCmd;
        this.maxDiffLines = gitConfig.getMaxDiffLines();
    }

    /**
     * 获取 Git diff 输出。
     *
     * @param input 工具输入，可选参数 path、staged、filePath
     * @return 结构化 diff 信息
     */
    public ToolResult execute(ToolInput input) {
        try {
            String pathStr = input.getOptionalParam("path", String.class)
                    .orElse(System.getProperty("user.dir"));
            Path repoPath = Path.of(pathStr).toAbsolutePath().normalize();

            if (!gitCmd.isGitRepo(repoPath)) {
                return ToolResult.error("指定路径不是 Git 仓库: " + repoPath);
            }

            boolean staged = input.getOptionalParam("staged", Boolean.class).orElse(false);
            String filePath = input.getOptionalParam("filePath", String.class).orElse(null);

            var args = new ArrayList<String>();
            args.add("diff");
            if (staged) {
                args.add("--staged");
            }
            if (filePath != null && !filePath.isBlank()) {
                args.add("--");
                args.add(filePath);
            }

            var result = gitCmd.execute(repoPath, args.toArray(String[]::new));
            if (!result.ok()) {
                return ToolResult.error("git diff 执行失败: " + result.stderr());
            }

            String diffOutput = result.stdout();
            String[] lines = diffOutput.isEmpty() ? new String[0] : diffOutput.split("\n", -1);
            int linesTotal = lines.length;
            boolean truncated = linesTotal > maxDiffLines;

            String finalDiff;
            if (truncated) {
                var sb = new StringBuilder();
                for (int i = 0; i < maxDiffLines; i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    sb.append(lines[i]);
                }
                sb.append("\n...[diff 已截断，共 ").append(linesTotal).append(" 行，最大显示 ").append(maxDiffLines).append(" 行]");
                finalDiff = sb.toString();
            } else {
                finalDiff = diffOutput;
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("diff", finalDiff);
            data.put("truncated", truncated);
            data.put("linesTotal", linesTotal);
            return ToolResult.success(data);

        } catch (Exception e) {
            log.error("git diff 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git diff 执行失败: " + e.getMessage());
        }
    }
}
