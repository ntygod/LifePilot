package com.lifepilot.sandbox.session;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.sandbox.booter.ProcessBooter;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * {@link SandboxSessionManager} 单元测试。
 *
 * <p>覆盖会话创建/复用、TTL 过期清理、最大会话数限制和并发场景。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
class SandboxSessionManagerTest {

    private SandboxConfigProperties config;
    private SandboxSessionManager manager;
    private SharedScheduler sharedScheduler;
    private ScheduledExecutorService cleanupExecutor;

    @BeforeEach
    void setUp() {
        config = new SandboxConfigProperties();
        config.getSession().setMaxActiveSessions(3);
        config.getSession().setTtlSeconds(2);
        config.getSession().setCleanupIntervalSeconds(600); // 测试中手动触发清理

        // mock SharedScheduler，返回真实的 ScheduledExecutorService
        sharedScheduler = mock(SharedScheduler.class);
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
        when(sharedScheduler.cleanup()).thenReturn(cleanupExecutor);

        // 使用 ProcessBooter 作为模板（Task 11 起 ProcessBooter 强依赖 PythonRuntimeManager，
        // 桩出 Ready 状态避免依赖真实捆绑运行时；SandboxSessionManager 直接注入同一 mock 实例，
        // 派生会话时复用，保证 installingState 共享状态唯一）
        var runtimeManager = mock(PythonRuntimeManager.class);
        when(runtimeManager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.13", 0L));
        SandboxBooter template = new ProcessBooter(config, runtimeManager);
        var zhiweiPaths = mock(com.lifepilot.config.path.ZhiweiPaths.class);
        when(zhiweiPaths.workspace()).thenReturn(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
        var workspaceResolver = new WorkspaceResolver(null, zhiweiPaths);
        manager = new SandboxSessionManager(config, template, sharedScheduler, workspaceResolver, runtimeManager);
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
        cleanupExecutor.shutdownNow();
    }

    // ─────────────────────────────────────────────
    //  创建与复用
    // ─────────────────────────────────────────────

    @Test
    void 首次获取创建新会话() {
        SandboxBooter booter = manager.getOrCreate("session-1");

        assertThat(booter).isNotNull();
        assertThat(booter.type()).isEqualTo("process");
        assertThat(booter.workingDirectory()).isNotNull();
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    @Test
    void 相同sessionId复用已有实例() {
        SandboxBooter first = manager.getOrCreate("session-1");
        SandboxBooter second = manager.getOrCreate("session-1");

        assertThat(second).isSameAs(first);
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    @Test
    void 不同sessionId创建不同实例() {
        SandboxBooter booter1 = manager.getOrCreate("session-1");
        SandboxBooter booter2 = manager.getOrCreate("session-2");

        assertThat(booter1).isNotSameAs(booter2);
        assertThat(manager.activeCount()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────
    //  最大会话数限制
    // ─────────────────────────────────────────────

    @Test
    void 超过最大会话数抛异常() {
        manager.getOrCreate("session-1");
        manager.getOrCreate("session-2");
        manager.getOrCreate("session-3");

        assertThatThrownBy(() -> manager.getOrCreate("session-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上限");

        assertThat(manager.activeCount()).isEqualTo(3);
    }

    @Test
    void 销毁后可创建新会话() {
        manager.getOrCreate("session-1");
        manager.getOrCreate("session-2");
        manager.getOrCreate("session-3");

        manager.destroy("session-1");
        assertThat(manager.activeCount()).isEqualTo(2);

        // 现在可以创建新会话
        SandboxBooter booter = manager.getOrCreate("session-4");
        assertThat(booter).isNotNull();
        assertThat(manager.activeCount()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────
    //  销毁
    // ─────────────────────────────────────────────

    @Test
    void 销毁已有会话() {
        manager.getOrCreate("session-1");
        assertThat(manager.activeCount()).isEqualTo(1);

        manager.destroy("session-1");
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    void 销毁不存在的会话不报错() {
        manager.destroy("nonexistent");
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    void 销毁后清理工作目录() {
        SandboxBooter booter = manager.getOrCreate("session-1");
        Path workDir = booter.workingDirectory();

        manager.destroy("session-1");

        // shutdown 会清理工作目录
        assertThat(manager.activeCount()).isZero();
    }

    // ─────────────────────────────────────────────
    //  TTL 过期清理
    // ─────────────────────────────────────────────

    @Test
    void 过期会话被清理() throws InterruptedException {
        config.getSession().setTtlSeconds(1);

        manager.getOrCreate("session-1");
        assertThat(manager.activeCount()).isEqualTo(1);

        // 等待超过 TTL
        Thread.sleep(1500);

        manager.cleanupExpired();
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    void 访问续期后不被清理() throws InterruptedException {
        config.getSession().setTtlSeconds(1);

        manager.getOrCreate("session-1");

        // 在 TTL 内访问续期
        Thread.sleep(600);
        manager.getOrCreate("session-1"); // 续期

        Thread.sleep(600);
        manager.cleanupExpired();

        // 续期后不应被清理
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    @Test
    void 仅清理过期会话_保留活跃会话() throws InterruptedException {
        config.getSession().setTtlSeconds(1);

        manager.getOrCreate("session-old");

        Thread.sleep(1500);

        // 创建新会话（此时 session-old 已过期）
        manager.getOrCreate("session-new");

        manager.cleanupExpired();

        assertThat(manager.activeCount()).isEqualTo(1);
        // session-new 仍可用
        SandboxBooter booter = manager.getOrCreate("session-new");
        assertThat(booter).isNotNull();
    }

    // ─────────────────────────────────────────────
    //  shutdownAll
    // ─────────────────────────────────────────────

    @Test
    void shutdownAll关闭所有会话() {
        manager.getOrCreate("session-1");
        manager.getOrCreate("session-2");
        assertThat(manager.activeCount()).isEqualTo(2);

        manager.shutdownAll();
        assertThat(manager.activeCount()).isZero();
    }
}
