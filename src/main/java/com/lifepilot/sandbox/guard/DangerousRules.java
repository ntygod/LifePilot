package com.lifepilot.sandbox.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * DANGEROUS 规则集 — 高风险但可恢复的软阻断规则。
 *
 * <p>这些规则匹配的命令风险较高，但通常具备可恢复性（区别于 HARDLINE）。
 * yolo 模式启用时放行；关闭时由 CommandGuard 拦截。容器后端可 bypass。</p>
 *
 * <p>采用命令位置锚定（行首或 ; / && / | 之后），避免 echo / 注释误伤。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public final class DangerousRules {

    /** 命令位置锚：行首、或 ; / && / | 等命令分隔符之后。 */
    private static final String CMDPOS = "(?:^|[\\n;&|]+\\s*)";

    private record Rule(String name, Pattern pattern, String description) {}

    private static final List<Rule> RULES = List.of(
        rule("rm-rf-subtree",
            CMDPOS + "rm\\s+(-[a-zA-Z]*[rRf][a-zA-Z]*\\s+)+\\S+",
            "递归删除子树"),
        rule("chmod-777",
            CMDPOS + "chmod\\s+(-R\\s+)?7[57]7\\s+",
            "设置 777 权限"),
        rule("git-reset-hard",
            CMDPOS + "git\\s+reset\\s+--hard\\b",
            "硬重置 git"),
        rule("git-push-force",
            CMDPOS + "git\\s+push\\s+(-f|--force)\\b",
            "强推 git"),
        rule("git-clean-fdx",
            CMDPOS + "git\\s+clean\\s+-[fdxRr]+\\b",
            "清理 git 工作区"),
        rule("curl-pipe-sh",
            CMDPOS + "(curl|wget)\\s+[^|]*\\|\\s*(sh|bash|zsh)\\b",
            "管道执行远程脚本"),
        rule("heredoc-bash",
            "<<\\s*\\w+[\\s\\S]*?\\|\\s*(bash|sh|zsh)\\b",
            "heredoc 执行 shell"),
        rule("sql-drop",
            CMDPOS + "DROP\\s+(TABLE|DATABASE|SCHEMA)\\b",
            "SQL DROP 操作"),
        rule("sql-delete-all",
            CMDPOS + "DELETE\\s+FROM\\s+\\w+\\s*(;|$|WHERE\\s+TRUE)",
            "SQL 删除全表或恒真条件"),
        rule("write-etc",
            "[^#'\"\\n]*(>|>>)\\s*/etc/\\w+",
            "写入 /etc/"),
        rule("modify-self-zhiwei",
            "(rm|mv|>|>>)\\s+.*~/\\.zhiwei(/|\\s|$)",
            "改动知微自身目录"),
        rule("tar-extract-root",
            CMDPOS + "tar\\s+x[fzj]?\\s+\\S+\\s+(-C\\s+)?/(\\s|$)",
            "解压到根目录"),
        rule("sudo-su",
            CMDPOS + "(sudo|su)\\b",
            "提权命令")
    );

    private DangerousRules() {}

    /**
     * 检查命令是否命中 DANGEROUS 规则。
     *
     * @param rawCode 原始命令字符串
     * @return 命中则返回 dangerous 结果，否则返回 approved
     */
    public static GuardResult check(String rawCode) {
        String normalized = CommandNormalizer.normalize(rawCode);
        for (Rule r : RULES) {
            if (r.pattern.matcher(normalized).find()) {
                return GuardResult.dangerous(r.name, r.description);
            }
        }
        return GuardResult.approved();
    }

    private static Rule rule(String name, String regex, String description) {
        return new Rule(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), description);
    }
}
