package com.lifepilot.meta.infra.shell.session;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.meta.config.MetaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link TmuxSessionManager} 单元测试。
 *
 * <p>使用 mock 的 {@link TmuxCommandExecutor} 避免真实 tmux 依赖。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
class TmuxSessionManagerTest {

    private TmuxCommandExecutor tmuxCmd;
    private MetaProperties.Infra.ShellSession config;
    private TmuxSessionManager manager;

    @BeforeEach
    void setUp() {
        tmuxCmd = mock(TmuxCommandExecutor.class);
        config = new MetaProperties.Infra.ShellSession();
        config.setMaxConcurrentSessions(3);
        config.setTtlMinutes(30);
        config.setDefaultCols(120);
        config.setDefaultRows(40);
        config.setHistoryLines(2000);
        config.setExecTimeoutSeconds(120);
        config.setOutputMaxChars(50000);
        config.setCleanupIntervalSeconds(3600); // 测试中设置长间隔，避免自动清理干扰
        var workspaceResolver = new WorkspaceResolver(null, "");
        manager = new TmuxSessionManager(tmuxCmd, config, workspaceResolver);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void createSession_超过最大并发数应拒绝() throws Exception {
        // 创建 3 个会话（达到上限）
        when(tmuxCmd.newSession(anyString(), anyString(), anyInt(), anyInt())).thenReturn("");
        manager.createSession(null, null);
        manager.createSession(null, null);
        manager.createSession(null, null);

        // 第 4 个应该被拒绝
        assertThatThrownBy(() -> manager.createSession(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("持久会话数已达上限");
    }

    @Test
    void closeSession_应清理tmux会话() throws Exception {
        when(tmuxCmd.newSession(anyString(), anyString(), anyInt(), anyInt())).thenReturn("");
        doNothing().when(tmuxCmd).killSession(anyString());

        String sessionId = manager.createSession(null, null);
        assertThat(manager.listSessions()).hasSize(1);

        manager.closeSession(sessionId);

        assertThat(manager.listSessions()).isEmpty();
        // 验证 killSession 被调用
        verify(tmuxCmd).killSession(argThat(name -> name.startsWith("zhiwei-")));
    }

    @Test
    void listSessions_应返回活跃会话列表() throws Exception {
        when(tmuxCmd.newSession(anyString(), anyString(), anyInt(), anyInt())).thenReturn("");

        manager.createSession(null, null);
        manager.createSession(null, null);

        var sessions = manager.listSessions();
        assertThat(sessions).hasSize(2);
        for (var session : sessions) {
            assertThat(session.sessionId()).isNotBlank();
            assertThat(session.name()).startsWith("zhiwei-");
            assertThat(session.state()).isEqualTo(SessionState.IDLE);
            assertThat(session.createdAt()).isNotNull();
            assertThat(session.lastAccessTime()).isNotNull();
        }
    }

    @Test
    void closeSession_不存在的sessionId应抛异常() {
        assertThatThrownBy(() -> manager.closeSession("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("持久会话不存在");
    }

    @Test
    void createSession_应生成8位sessionId() throws Exception {
        when(tmuxCmd.newSession(anyString(), anyString(), anyInt(), anyInt())).thenReturn("");

        String sessionId = manager.createSession(null, null);
        assertThat(sessionId).isNotBlank().hasSize(8);
    }

    @Test
    void createSession_tmux失败应抛异常() throws Exception {
        when(tmuxCmd.newSession(anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new IOException("tmux 命令失败"));

        assertThatThrownBy(() -> manager.createSession(null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("tmux 命令失败");
    }
}
