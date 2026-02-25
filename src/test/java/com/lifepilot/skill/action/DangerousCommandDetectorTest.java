package com.lifepilot.skill.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DangerousCommandDetector} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class DangerousCommandDetectorTest {

    private DangerousCommandDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DangerousCommandDetector();
    }

    // ─────────────────────────────────────────────
    //  isDangerous — 危险命令检测
    // ─────────────────────────────────────────────

    @Test
    void isDangerous_检测rm_rf() {
        assertThat(detector.isDangerous("rm -rf /")).isTrue();
        assertThat(detector.isDangerous("rm -rf /home/user")).isTrue();
    }

    @Test
    void isDangerous_检测sudo() {
        assertThat(detector.isDangerous("sudo apt install vim")).isTrue();
        assertThat(detector.isDangerous("sudo rm file.txt")).isTrue();
    }

    @Test
    void isDangerous_检测chmod() {
        assertThat(detector.isDangerous("chmod 777 /etc/passwd")).isTrue();
        assertThat(detector.isDangerous("chmod +x script.sh")).isTrue();
    }

    @Test
    void isDangerous_检测chown() {
        assertThat(detector.isDangerous("chown root:root /etc/shadow")).isTrue();
    }

    @Test
    void isDangerous_检测mkfs() {
        assertThat(detector.isDangerous("mkfs.ext4 /dev/sda1")).isTrue();
    }

    @Test
    void isDangerous_检测dd_if() {
        assertThat(detector.isDangerous("dd if=/dev/zero of=/dev/sda")).isTrue();
    }

    @Test
    void isDangerous_检测shutdown() {
        assertThat(detector.isDangerous("shutdown -h now")).isTrue();
        assertThat(detector.isDangerous("shutdown -r +5")).isTrue();
    }

    @Test
    void isDangerous_检测reboot() {
        assertThat(detector.isDangerous("reboot")).isTrue();
    }

    @Test
    void isDangerous_检测fork_bomb() {
        assertThat(detector.isDangerous(":(){ :|:& };:")).isTrue();
    }

    @Test
    void isDangerous_检测重定向到dev() {
        assertThat(detector.isDangerous("echo data > /dev/sda")).isTrue();
        assertThat(detector.isDangerous("cat file > /dev/null")).isTrue();
    }

    @Test
    void isDangerous_检测format() {
        assertThat(detector.isDangerous("format C:")).isTrue();
    }

    // ─────────────────────────────────────────────
    //  isDangerous — 安全命令
    // ─────────────────────────────────────────────

    @Test
    void isDangerous_安全命令ls返回false() {
        assertThat(detector.isDangerous("ls -la /home")).isFalse();
    }

    @Test
    void isDangerous_安全命令echo返回false() {
        assertThat(detector.isDangerous("echo hello world")).isFalse();
    }

    @Test
    void isDangerous_安全命令cat返回false() {
        assertThat(detector.isDangerous("cat /etc/hostname")).isFalse();
    }

    @Test
    void isDangerous_空命令返回false() {
        assertThat(detector.isDangerous("")).isFalse();
        assertThat(detector.isDangerous("   ")).isFalse();
    }

    @Test
    void isDangerous_null命令返回false() {
        assertThat(detector.isDangerous(null)).isFalse();
    }

    // ─────────────────────────────────────────────
    //  detectPatterns — 匹配模式描述
    // ─────────────────────────────────────────────

    @Test
    void detectPatterns_返回匹配的危险模式描述() {
        List<String> patterns = detector.detectPatterns("sudo rm -rf /");
        assertThat(patterns).hasSize(2);
        assertThat(patterns).anyMatch(p -> p.contains("rm -rf"));
        assertThat(patterns).anyMatch(p -> p.contains("sudo"));
    }

    @Test
    void detectPatterns_单个匹配返回单个描述() {
        List<String> patterns = detector.detectPatterns("chmod 755 file.sh");
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst()).contains("chmod");
    }

    @Test
    void detectPatterns_安全命令返回空列表() {
        assertThat(detector.detectPatterns("ls -la")).isEmpty();
        assertThat(detector.detectPatterns("echo hello")).isEmpty();
        assertThat(detector.detectPatterns("cat file.txt")).isEmpty();
    }

    @Test
    void detectPatterns_空命令返回空列表() {
        assertThat(detector.detectPatterns("")).isEmpty();
        assertThat(detector.detectPatterns("   ")).isEmpty();
    }

    @Test
    void detectPatterns_null命令返回空列表() {
        assertThat(detector.detectPatterns(null)).isEmpty();
    }

    @Test
    void detectPatterns_返回不可变列表() {
        List<String> patterns = detector.detectPatterns("sudo chmod 777 /");
        assertThat(patterns).isUnmodifiable();
    }
}
