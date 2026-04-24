package com.lifepilot.tool.tier1;

import com.lifepilot.tool.pipeline.ToolInvocationEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ToolUsageStatsRecorder 单元测试 — 覆盖事件监听和幂等首次检测逻辑。
 *
 * @author zsg
 * @since 2026-04-23
 */
class ToolUsageStatsRecorder_记录测试 {

    @Test
    void 成功调用_记录为首次() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);

        recorder.onInvocation(new ToolInvocationEvent(
                "file.read", "session-1", true, 100L, Instant.now()));

        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(true));
    }

    @Test
    void 同session同日同工具_第二次firstTime为false() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);

        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", true, 1L, Instant.now()));
        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", true, 1L, Instant.now()));

        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(true));
        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(false));
    }

    @Test
    void 失败调用_不记录() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);
        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", false, 1L, Instant.now()));
        verifyNoInteractions(repo);
    }

    @Test
    void 无sessionId_不记录() {
        var repo = mock(ToolUsageStatsRepository.class);
        var recorder = new ToolUsageStatsRecorder(repo);
        recorder.onInvocation(new ToolInvocationEvent("file.read", null, true, 1L, Instant.now()));
        recorder.onInvocation(new ToolInvocationEvent("file.read", "", true, 1L, Instant.now()));
        verifyNoInteractions(repo);
    }

    @Test
    void repository抛异常_不向上传播() {
        var repo = mock(ToolUsageStatsRepository.class);
        doThrow(new RuntimeException("db down")).when(repo)
                .recordInvocation(anyString(), anyString(), anyBoolean());
        var recorder = new ToolUsageStatsRecorder(repo);
        // 不应抛
        recorder.onInvocation(new ToolInvocationEvent("file.read", "s1", true, 1L, Instant.now()));
        verify(repo).recordInvocation(eq("file.read"), anyString(), eq(true));
    }
}
