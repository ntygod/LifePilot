package com.lifepilot.meta.infra.code.kernel;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link PersistentKernelManager} 单元测试。
 *
 * <p>使用 mock 配置（不启动真实 Python/Node 进程），验证管理器的
 * 并发控制、复用和关闭逻辑。{@link PythonRuntimeManager} 通过 Mockito 桩出
 * Ready 状态并返回系统 {@code python3} 路径，避免依赖真实捆绑运行时。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistentKernelManagerTest {

    @Mock
    private PythonRuntimeManager runtimeManager;

    private MetaProperties.Infra.Kernel config;
    private PersistentKernelManager manager;

    @BeforeEach
    void setUp() {
        config = new MetaProperties.Infra.Kernel();
        config.setMaxConcurrentKernels(2);
        config.setTtlMinutes(30);
        config.setCleanupIntervalSeconds(3600); // 测试中设置长间隔，避免自动清理干扰
        config.setExecutionTimeoutSeconds(60);
        config.setNodeRuntime("node");
        config.setMaxOutputChars(50000);

        // 测试不依赖真实捆绑 Python，桩出 Ready 状态 + 系统 python3 路径
        // Shell 内核场景下不会调用 runtimeManager，但 LENIENT 模式允许未使用的 stub
        when(runtimeManager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.13", 0L));
        when(runtimeManager.getPythonExecutable()).thenReturn(Paths.get("python3"));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void getOrCreate_超过最大并发数应拒绝() {
        // Shell 内核不需要真实进程，可安全创建
        manager = new PersistentKernelManager(config, null, runtimeManager);

        // 创建 2 个内核（达到上限），Shell 内核在 tmux 不可用时状态为 ERROR 但仍计数
        manager.getOrCreate("kernel-1", "shell");
        manager.getOrCreate("kernel-2", "shell");

        // 第 3 个应该被拒绝
        assertThatThrownBy(() -> manager.getOrCreate("kernel-3", "shell"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("持久内核数已达上限");
    }

    @Test
    void closeKernel_应关闭并移除() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        var kernel = manager.getOrCreate("kernel-1", "shell");
        assertThat(kernel).isNotNull();
        assertThat(kernel.kernelId()).isEqualTo("kernel-1");

        manager.closeKernel("kernel-1");

        // 关闭后应能重新创建（不再占用配额）
        var kernel2 = manager.getOrCreate("kernel-1", "shell");
        assertThat(kernel2).isNotNull();
    }

    @Test
    void getOrCreate_已存在内核应复用() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        var kernel1 = manager.getOrCreate("kernel-1", "shell");
        var kernel2 = manager.getOrCreate("kernel-1", "shell");

        // 应该是同一个实例
        assertThat(kernel1).isSameAs(kernel2);
    }

    @Test
    void resetKernel_不存在的内核应抛异常() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        assertThatThrownBy(() -> manager.resetKernel("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内核不存在");
    }

    @Test
    void inspectKernel_不存在的内核应抛异常() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        assertThatThrownBy(() -> manager.inspectKernel("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内核不存在");
    }

    @Test
    void getOrCreate_不支持的语言应抛异常() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        assertThatThrownBy(() -> manager.getOrCreate("kernel-1", "ruby"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的内核语言");
    }

    @Test
    void shutdown_应关闭所有内核() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        manager.getOrCreate("kernel-1", "shell");
        manager.getOrCreate("kernel-2", "shell");
        manager.shutdown();

        // shutdown 后应能重新使用（需新建 manager）
        manager = new PersistentKernelManager(config, null, runtimeManager);
        var kernel = manager.getOrCreate("kernel-1", "shell");
        assertThat(kernel).isNotNull();
    }

    // ─────────────────────────────────────────────
    //  listKernels 测试
    // ─────────────────────────────────────────────

    @Test
    void listKernels_空时返回空列表() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        var list = manager.listKernels();
        assertThat(list).isEmpty();
    }

    @Test
    void listKernels_有内核时返回正确信息() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        manager.getOrCreate("kernel-1", "shell");
        manager.getOrCreate("kernel-2", "shell");

        var list = manager.listKernels();
        assertThat(list).hasSize(2);
        assertThat(list).extracting(PersistentKernelManager.KernelInfo::kernelId)
                .containsExactlyInAnyOrder("kernel-1", "kernel-2");
        // 所有内核的 idleSeconds 应非负（刚创建，应接近 0）
        assertThat(list).allSatisfy(info -> {
            assertThat(info.idleSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(info.state()).isNotNull();
        });
    }

    @Test
    void listKernels_关闭内核后列表减少() {
        manager = new PersistentKernelManager(config, null, runtimeManager);

        manager.getOrCreate("kernel-1", "shell");
        manager.getOrCreate("kernel-2", "shell");
        manager.closeKernel("kernel-1");

        var list = manager.listKernels();
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().kernelId()).isEqualTo("kernel-2");
    }
}
