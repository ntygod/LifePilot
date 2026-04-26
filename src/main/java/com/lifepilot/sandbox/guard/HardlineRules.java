package com.lifepilot.sandbox.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * HARDLINE 规则集 — 11 条无条件硬阻断规则。
 *
 * <p>这些规则匹配的命令不可恢复，无论 yolo 模式如何均拦截，
 * 仅容器后端可 bypass（参考 Hermes approval.py 范式）。</p>
 *
 * <p>采用命令位置锚定（行首或 ; / && / | 之后），避免 echo / 注释误伤。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public final class HardlineRules {

    /** 命令位置锚：行首、或 ; / && / | 等命令分隔符之后。 */
    private static final String CMDPOS = "(?:^|[\\n;&|]+\\s*)";

    private record Rule(String name, Pattern pattern, String description) {}

    private static final List<Rule> RULES = List.of(
        rule("rm-root-or-system",
            CMDPOS + "rm\\s+(-[a-zA-Z]*[rRf][a-zA-Z]*\\s+)+(/|/home|/etc|/usr|/var|/boot|/bin|/sbin|/lib|~|\\$HOME)(\\s|;|&|\\||$)",
            "递归删除根/系统目录/家目录"),
        rule("mkfs",
            CMDPOS + "mkfs(\\.[a-z0-9]+)?\\s+",
            "格式化文件系统"),
        rule("dd-block-device",
            CMDPOS + "dd\\s+.*of=/dev/(sd|nvme|hd|vd)[a-z0-9]+",
            "写裸块设备"),
        rule("fork-bomb",
            ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:",
            "fork bomb"),
        rule("kill-all",
            CMDPOS + "kill\\s+(-1|-9\\s+-1)(\\s|;|&|\\||$)",
            "kill 所有进程"),
        rule("shutdown",
            CMDPOS + "(shutdown|reboot|halt|poweroff)(\\s|;|&|\\||$)",
            "关机或重启"),
        rule("init",
            CMDPOS + "init\\s+[06](\\s|;|&|\\||$)",
            "通过 init 关机重启"),
        rule("systemctl-power",
            CMDPOS + "systemctl\\s+(poweroff|reboot|halt|kexec)(\\s|;|&|\\||$)",
            "通过 systemctl 关机重启"),
        rule("telinit",
            CMDPOS + "telinit\\s+[06](\\s|;|&|\\||$)",
            "通过 telinit 关机重启"),
        rule("chmod-system-readonly",
            CMDPOS + "chmod\\s+(-R\\s+)?000\\s+/(\\s|;|&|\\||$)",
            "锁死系统权限"),
        rule("write-block-device",
            "(?:^|[\\n;&|]+\\s*)[^#'\"\\n]*(>|>>)\\s*/dev/(sda|nvme0n1|hda|vda)(\\s|$)",
            "重定向写入块设备")
    );

    private HardlineRules() {}

    /**
     * 检查命令是否命中 HARDLINE 规则。
     *
     * @param rawCode 原始命令字符串
     * @return 命中则返回 hardline 结果，否则返回 approved
     */
    public static GuardResult check(String rawCode) {
        String normalized = CommandNormalizer.normalize(rawCode);
        for (Rule r : RULES) {
            if (r.pattern.matcher(normalized).find()) {
                return GuardResult.hardline(r.name, r.description);
            }
        }
        return GuardResult.approved();
    }

    private static Rule rule(String name, String regex, String description) {
        return new Rule(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), description);
    }
}
