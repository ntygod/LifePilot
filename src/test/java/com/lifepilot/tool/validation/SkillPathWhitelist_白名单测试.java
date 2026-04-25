package com.lifepilot.tool.validation;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.skill.config.SkillConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * {@link SkillPathWhitelist} 路径白名单校验测试。
 *
 * <p>覆盖：合法 skills 根、合法工作区根、越界系统路径、path traversal 攻击、
 * 相对路径规范化、空配置兜底等场景。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillPathWhitelist_白名单测试 {

    @TempDir
    Path skillsDir;

    @TempDir
    Path workspaceDir;

    @Mock
    WorkspaceResolver workspaceResolver;

    private SkillConfigProperties skillConfig;
    private SkillPathWhitelist whitelist;

    @BeforeEach
    void setUp() {
        skillConfig = new SkillConfigProperties();
        skillConfig.setDirectory(skillsDir.toString());
        lenient().when(workspaceResolver.getDefaultDir()).thenReturn(workspaceDir.toString());
        whitelist = new SkillPathWhitelist(skillConfig, workspaceResolver);
    }

    @Test
    void 合法skills路径应通过() {
        assertThatCode(() -> whitelist.validate(
                skillsDir.resolve("some-skill/SKILL.md").toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void 合法工作区路径应通过() {
        assertThatCode(() -> whitelist.validate(
                workspaceDir.resolve("sub/report.md").toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void 非法系统路径应拒绝() {
        // Windows 和 Linux 都选一个绝对不会落在 tempDir 里的路径
        String forbidden = isWindows()
                ? "C:\\Windows\\System32\\drivers\\etc\\hosts"
                : "/etc/passwd";

        assertThatThrownBy(() -> whitelist.validate(forbidden))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("超出白名单");
    }

    @Test
    void path_traversal应拒绝() {
        // skills 目录 + ../../../etc/passwd —— normalize 后跳出所有白名单根
        Path traversal = skillsDir.resolve("../../../../etc/passwd");

        assertThatThrownBy(() -> whitelist.validate(traversal.toString()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("超出白名单");
    }

    @Test
    void 相对路径应正确规范化() {
        // 使用进程工作目录的兜底分支 —— 相对路径 resolve 后必定落在 user.dir 内
        String cwd = System.getProperty("user.dir");
        var bareWhitelist = new SkillPathWhitelist(null, null);

        assertThatCode(() -> bareWhitelist.validate("./relative/file.txt"))
                .doesNotThrowAnyException();
        // 但绝对的系统路径仍应被拒
        String forbidden = isWindows()
                ? "C:\\Windows\\System32\\drivers\\etc\\hosts"
                : "/etc/passwd";
        // 仅当 cwd 不覆盖 forbidden 时断言拒绝（CI 环境 cwd 不会是 /etc 或 System32）
        if (!Path.of(forbidden).toAbsolutePath().normalize()
                .startsWith(Path.of(cwd).toAbsolutePath().normalize())) {
            assertThatThrownBy(() -> bareWhitelist.validate(forbidden))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void 空路径应拒绝() {
        assertThatThrownBy(() -> whitelist.validate(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");

        assertThatThrownBy(() -> whitelist.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空白只有_WorkspaceResolver时仍覆盖工作区与进程目录() {
        // SkillConfigProperties 为 null，此时仅工作区 + user.dir 作为白名单
        var bareSkill = new SkillPathWhitelist(null, workspaceResolver);

        assertThatCode(() -> bareSkill.validate(
                workspaceDir.resolve("a.md").toString()))
                .doesNotThrowAnyException();

        // skills 根在此实例里并不存在，skillsDir 下的路径不再合法
        String forbidden = isWindows()
                ? "C:\\Windows\\System32\\drivers\\etc\\hosts"
                : "/etc/passwd";
        assertThatThrownBy(() -> bareSkill.validate(forbidden))
                .isInstanceOf(SecurityException.class);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
