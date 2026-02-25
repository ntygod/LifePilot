package com.lifepilot.skill.action;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险命令检测器 — 维护 Shell 命令黑名单模式。
 *
 * <p>通过正则模式匹配检测危险的 Shell 命令，包括但不限于：
 * rm -rf、sudo、chmod、chown、mkfs、dd if=、shutdown、reboot、fork bomb、
 * 重定向到 /dev/、format 等。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DangerousCommandDetector {

    /** 危险命令模式及其描述。 */
    private record DangerousPattern(Pattern pattern, String description) {}

    /** 危险命令模式列表。 */
    private static final List<DangerousPattern> DANGEROUS_PATTERNS = List.of(
            new DangerousPattern(Pattern.compile("\\brm\\s+-rf\\b"), "rm -rf（递归强制删除）"),
            new DangerousPattern(Pattern.compile("\\bsudo\\b"), "sudo（提权执行）"),
            new DangerousPattern(Pattern.compile("\\bchmod\\b"), "chmod（修改文件权限）"),
            new DangerousPattern(Pattern.compile("\\bchown\\b"), "chown（修改文件所有者）"),
            new DangerousPattern(Pattern.compile("\\bmkfs\\b"), "mkfs（格式化文件系统）"),
            new DangerousPattern(Pattern.compile("\\bdd\\b\\s+if="), "dd if=（磁盘复制）"),
            new DangerousPattern(Pattern.compile("\\bshutdown\\b"), "shutdown（关机）"),
            new DangerousPattern(Pattern.compile("\\breboot\\b"), "reboot（重启）"),
            new DangerousPattern(Pattern.compile(":\\(\\)\\{\\s*:\\|:\\&\\s*\\};:"), "fork bomb（fork 炸弹）"),
            new DangerousPattern(Pattern.compile(">\\s*/dev/"), "重定向到 /dev/（设备写入）"),
            new DangerousPattern(Pattern.compile("\\bformat\\b"), "format（格式化）")
    );

    /**
     * 检测命令是否包含危险模式。
     *
     * @param command Shell 命令
     * @return true 表示命令危险，应拒绝执行
     */
    public boolean isDangerous(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return DANGEROUS_PATTERNS.stream()
                .anyMatch(dp -> dp.pattern().matcher(command).find());
    }

    /**
     * 返回匹配的危险模式描述。
     *
     * @param command Shell 命令
     * @return 匹配的模式描述列表，安全命令返回空列表
     */
    public List<String> detectPatterns(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (DangerousPattern dp : DANGEROUS_PATTERNS) {
            if (dp.pattern().matcher(command).find()) {
                matched.add(dp.description());
            }
        }
        return List.copyOf(matched);
    }
}
