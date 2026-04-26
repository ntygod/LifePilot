package com.lifepilot.sandbox.guard;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HARDLINE 规则集测试 — 验证 11 条无条件硬阻断规则命中和误伤。
 *
 * @author zsg
 * @since 2026-04-26
 */
class CommandGuard_HARDLINE测试 {

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /",
        "rm -rf /home",
        "rm -rf /etc",
        "rm -rf /usr",
        "rm -rf /var",
        "rm -rf /boot",
        "rm -rf /bin",
        "rm -rf /sbin",
        "rm -rf /lib",
        "rm -rf $HOME",
        "rm -rf ~",
        "mkfs.ext4 /dev/sda1",
        "mkfs.btrfs /dev/nvme0n1",
        "dd if=/dev/zero of=/dev/sda",
        "dd if=/etc/passwd of=/dev/nvme0n1",
        ":(){ :|:& };:",
        "kill -1",
        "kill -9 -1",
        "shutdown -h now",
        "reboot",
        "halt",
        "poweroff",
        "init 0",
        "init 6",
        "systemctl poweroff",
        "systemctl reboot",
        "systemctl kexec",
        "telinit 0",
        "telinit 6",
        "chmod -R 000 /",
        // 新增 && / || 分隔符场景
        "cd / && rm -rf /home",
        "false || shutdown",
        // 新增 ; 边界（boundary 扩展验证）
        "shutdown;",
        "reboot;",
        "kill -1;",
        // 新增正则边界 case
        "rm -rf /;",
        "chmod -R 000 /;"
    })
    void HARDLINE规则命中拦截(String code) {
        var result = HardlineRules.check(code);
        assertThat(result.decision()).isEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
        assertThat(result.matchedRule()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "echo 'rm -rf /'",
        "ls /home",
        "rm /tmp/foo.txt",
        "echo reboot",
        "# rm -rf / 注释",
        // 验证 write-block-device CMDPOS-equivalent 修复后的安全场景
        "echo '> /dev/sda'",
        "# > /dev/sda 注释",
        "echo \"warning: > /dev/sda will wipe disk\""
    })
    void 安全场景不被HARDLINE误伤(String code) {
        var result = HardlineRules.check(code);
        assertThat(result.decision()).isNotEqualTo(GuardResult.Decision.BLOCKED_HARDLINE);
    }
}
