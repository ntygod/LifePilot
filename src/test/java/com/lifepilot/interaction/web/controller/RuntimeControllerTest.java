package com.lifepilot.interaction.web.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter;
import com.lifepilot.sandbox.runtime.RuntimeStatus;

/**
 * {@link RuntimeController} 单元测试 — MockMvc standalone 隔离 PythonRuntimeManager。
 *
 * <p>覆盖：6 个 REST 端点 + 状态机 5 个分支序列化 + 并发安装 409 + enable 自动触发 install。
 * SSE 进度流端点不在本类覆盖（留给集成测试）。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
@ExtendWith(MockitoExtension.class)
class RuntimeControllerTest {

    @Mock
    PythonRuntimeManager manager;

    @Mock
    RuntimeInstallProgressEmitter emitter;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new RuntimeController(manager, emitter);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionHandler())
                .build();
    }

    // ── GET /api/runtime/python/status ────────────────────────────

    @Test
    void 状态查询_Ready返回扁平DTO含version和diskBytes() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.13", 1_048_576L));

        mockMvc.perform(get("/api/runtime/python/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.status", is("READY")))
                .andExpect(jsonPath("$.data.version", is("3.12.13")))
                .andExpect(jsonPath("$.data.diskBytes", is(1_048_576)));
    }

    @Test
    void 状态查询_NotInstalled仅返回status() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.NotInstalled());

        mockMvc.perform(get("/api/runtime/python/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("NOT_INSTALLED")));
    }

    @Test
    void 状态查询_Disabled仅返回status() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.Disabled());

        mockMvc.perform(get("/api/runtime/python/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("DISABLED")));
    }

    @Test
    void 状态查询_Installing含phase和percent() throws Exception {
        when(manager.checkStatus()).thenReturn(
                new RuntimeStatus.Installing("downloading", 50_000_000L, 250_000_000L));

        mockMvc.perform(get("/api/runtime/python/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("INSTALLING")))
                .andExpect(jsonPath("$.data.phase", is("downloading")))
                .andExpect(jsonPath("$.data.bytesDownloaded", is(50_000_000)))
                .andExpect(jsonPath("$.data.totalBytes", is(250_000_000)))
                .andExpect(jsonPath("$.data.percent", is(20)));
    }

    @Test
    void 状态查询_InstallFailed含reason() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.InstallFailed("SHA-256 校验失败"));

        mockMvc.perform(get("/api/runtime/python/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("INSTALL_FAILED")))
                .andExpect(jsonPath("$.data.reason", is("SHA-256 校验失败")));
    }

    // ── POST /api/runtime/python/install ──────────────────────────

    @Test
    void 安装_首次返回ok并派发任务() throws Exception {
        when(manager.install(any())).thenReturn(new CompletableFuture<>());

        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.ok", is(true)));

        verify(manager, times(1)).install(any());
    }

    @Test
    void 安装_并发请求返回409且不重复派发() throws Exception {
        // 第一次返回 never-completing future，模拟仍在进行中
        when(manager.install(any())).thenReturn(new CompletableFuture<>());

        // 第一次成功
        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isOk());
        // 第二次因 installInProgress 已被占用，返回 409
        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)))
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(manager, times(1)).install(any());
    }

    @Test
    void 安装_任务完成后释放标志位允许后续安装() throws Exception {
        // 第一次返回已完成的 future，whenComplete 同步释放标志位
        when(manager.install(any())).thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isOk());
        // 第二次仍能成功（标志位已释放）
        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isOk());

        verify(manager, times(2)).install(any());
    }

    // ── POST /api/runtime/python/uninstall ────────────────────────

    @Test
    void 卸载_返回ok并代理到manager() throws Exception {
        mockMvc.perform(post("/api/runtime/python/uninstall"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok", is(true)));

        verify(manager).uninstall();
    }

    // ── POST /api/runtime/python/disable ──────────────────────────

    @Test
    void 禁用_返回ok并代理到manager() throws Exception {
        mockMvc.perform(post("/api/runtime/python/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok", is(true)));

        verify(manager).disable();
    }

    // ── POST /api/runtime/python/enable ───────────────────────────

    @Test
    void 启用_文件已存在时仅切标志不触发install() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.13", 1024L));

        mockMvc.perform(post("/api/runtime/python/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok", is(true)));

        verify(manager).enable();
        verify(manager, never()).install(any());
    }

    @Test
    void 启用_检测到未安装时自动触发install() throws Exception {
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.NotInstalled());
        when(manager.install(any())).thenReturn(new CompletableFuture<>());

        mockMvc.perform(post("/api/runtime/python/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok", is(true)))
                .andExpect(jsonPath("$.data.installTriggered", is(true)));

        verify(manager).enable();
        verify(manager).install(any());
    }

    @Test
    void 启用_检测未安装但install已在跑时返回ok不报错() throws Exception {
        // 第一次 install 占住 flag（never-completing future）
        when(manager.install(any())).thenReturn(new CompletableFuture<>());
        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isOk());

        // 用户点 enable，此时 status 仍是 NotInstalled
        when(manager.checkStatus()).thenReturn(new RuntimeStatus.NotInstalled());

        mockMvc.perform(post("/api/runtime/python/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok", is(true)))
                .andExpect(jsonPath("$.data.installSkipped", is(true)));

        // enable 实际被调，但 install 不会被第二次派发（dedupe）
        verify(manager).enable();
        verify(manager, times(1)).install(any());
    }

    @Test
    void 安装_manager同步抛异常时释放标志位且抛回() throws Exception {
        // 模拟 manager.install() 同步抛错（如 executor 创建失败）
        // 用 doThrow/doReturn 而非 when(...).thenXxx，避免后续重新 stub 时
        // when() 内部再次触发 install() 调用导致的"重新抛出旧 stub"问题。
        doThrow(new RuntimeException("dispatch fail")).when(manager).install(any());

        // 第一次 install 应抛 500（同步异常由 WebExceptionHandler 转）
        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().is5xxServerError());

        // 关键：第二次 install 不应被 409 永久 ban，因为 try/finally 已释放标志
        doReturn(new CompletableFuture<>()).when(manager).install(any());
        mockMvc.perform(post("/api/runtime/python/install"))
                .andExpect(status().isOk());
    }
}
