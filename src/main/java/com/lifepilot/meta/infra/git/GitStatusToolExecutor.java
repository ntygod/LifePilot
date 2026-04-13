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
 * Git Status 工具执行器 — 解析 {@code git status --porcelain=v2 --branch} 输出为结构化 JSON。
 *
 * <p>返回 branch、upstream、ahead/behind 计数、staged/unstaged/untracked 文件列表。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitStatusToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitStatusToolExecutor.class);

    private final GitCommandExecutor gitCmd;

    public GitStatusToolExecutor(GitCommandExecutor gitCmd) {
        this.gitCmd = gitCmd;
    }

    /**
     * 获取 Git 仓库状态。
     *
     * @param input 工具输入，可选参数 path
     * @return 结构化状态信息
     */
    public ToolResult execute(ToolInput input) {
        try {
            String pathStr = input.getOptionalParam("path", String.class)
                    .orElse(null);
            if (pathStr == null) {
                return ToolResult.error("必须指定 path 参数（Git 仓库路径）");
            }
            Path repoPath = Path.of(pathStr).toAbsolutePath().normalize();

            if (!gitCmd.isGitRepo(repoPath)) {
                return ToolResult.error("指定路径不是 Git 仓库: " + repoPath);
            }

            var result = gitCmd.execute(repoPath, "status", "--porcelain=v2", "--branch");
            if (!result.ok()) {
                return ToolResult.error("git status 执行失败: " + result.stderr());
            }

            return parseStatus(result.stdout());

        } catch (Exception e) {
            log.error("git status 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git status 执行失败: " + e.getMessage());
        }
    }

    /**
     * 解析 porcelain v2 格式的 status 输出。
     */
    private ToolResult parseStatus(String output) {
        String branch = "";
        String upstream = "";
        int ahead = 0;
        int behind = 0;
        List<Map<String, String>> staged = new ArrayList<>();
        List<Map<String, String>> unstaged = new ArrayList<>();
        List<Map<String, String>> untracked = new ArrayList<>();

        if (output == null || output.isBlank()) {
            return ToolResult.success(buildResultMap(branch, upstream, ahead, behind, staged, unstaged, untracked));
        }

        for (String line : output.split("\n")) {
            if (line.startsWith("# branch.head ")) {
                branch = line.substring("# branch.head ".length()).trim();
            } else if (line.startsWith("# branch.upstream ")) {
                upstream = line.substring("# branch.upstream ".length()).trim();
            } else if (line.startsWith("# branch.ab ")) {
                // 格式：# branch.ab +<ahead> -<behind>
                String ab = line.substring("# branch.ab ".length()).trim();
                String[] parts = ab.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        ahead = Integer.parseInt(parts[0].replace("+", ""));
                        behind = Math.abs(Integer.parseInt(parts[1].replace("-", "")));
                    } catch (NumberFormatException e) {
                        log.debug("解析 ahead/behind 失败: {}", ab);
                    }
                }
            } else if (line.startsWith("1 ") || line.startsWith("2 ")) {
                // 普通变更条目或重命名条目
                parseChangeEntry(line, staged, unstaged);
            } else if (line.startsWith("? ")) {
                // 未跟踪文件
                String path = line.substring(2).trim();
                untracked.add(Map.of("path", path, "status", "untracked"));
            } else if (line.startsWith("u ")) {
                // 未合并条目
                parseUnmergedEntry(line, staged);
            }
        }

        return ToolResult.success(buildResultMap(branch, upstream, ahead, behind, staged, unstaged, untracked));
    }

    /**
     * 解析普通变更条目。
     *
     * <p>格式：{@code 1 XY sub mH mI mW hH hI path}
     * 或重命名：{@code 2 XY sub mH mI mW hH hI X<score> path\torigPath}</p>
     */
    private void parseChangeEntry(String line, List<Map<String, String>> staged, List<Map<String, String>> unstaged) {
        String[] parts = line.split("\\s+", 9);
        if (parts.length < 9) {
            return;
        }

        String xy = parts[1];
        // 最后一个字段可能包含路径（处理重命名时的 tab 分隔）
        String pathField = parts[8];
        String path = pathField.contains("\t") ? pathField.split("\t")[0] : pathField;

        char indexStatus = xy.charAt(0);
        char workTreeStatus = xy.length() > 1 ? xy.charAt(1) : '.';

        // 暂存区变更
        if (indexStatus != '.') {
            staged.add(Map.of("path", path, "status", describeStatus(indexStatus)));
        }

        // 工作区变更
        if (workTreeStatus != '.') {
            unstaged.add(Map.of("path", path, "status", describeStatus(workTreeStatus)));
        }
    }

    /**
     * 解析未合并条目。
     */
    private void parseUnmergedEntry(String line, List<Map<String, String>> staged) {
        String[] parts = line.split("\\s+", 11);
        if (parts.length < 11) {
            return;
        }
        String path = parts[10];
        staged.add(Map.of("path", path, "status", "unmerged"));
    }

    /**
     * 将状态字符转换为可读描述。
     */
    private String describeStatus(char status) {
        return switch (status) {
            case 'M' -> "modified";
            case 'T' -> "type-changed";
            case 'A' -> "added";
            case 'D' -> "deleted";
            case 'R' -> "renamed";
            case 'C' -> "copied";
            case 'U' -> "unmerged";
            default -> String.valueOf(status);
        };
    }

    private Map<String, Object> buildResultMap(String branch, String upstream, int ahead, int behind,
                                                List<Map<String, String>> staged,
                                                List<Map<String, String>> unstaged,
                                                List<Map<String, String>> untracked) {
        var data = new LinkedHashMap<String, Object>();
        data.put("branch", branch);
        data.put("upstream", upstream);
        data.put("ahead", ahead);
        data.put("behind", behind);
        data.put("staged", staged);
        data.put("unstaged", unstaged);
        data.put("untracked", untracked);
        return data;
    }
}
