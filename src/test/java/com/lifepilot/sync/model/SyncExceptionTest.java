package com.lifepilot.sync.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyncException sealed 异常层次单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class SyncExceptionTest {

    @Test
    void ConnectionException_携带消息() {
        var ex = new SyncException.ConnectionException("CalDAV 服务器连接超时");
        assertEquals("CalDAV 服务器连接超时", ex.getMessage());
        assertNull(ex.getCause());
        assertInstanceOf(SyncException.class, ex);
    }

    @Test
    void ConnectionException_携带消息和原因() {
        var cause = new java.io.IOException("网络不可达");
        var ex = new SyncException.ConnectionException("CalDAV 服务器连接超时", cause);
        assertEquals("CalDAV 服务器连接超时", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void RemoteApiException_携带消息() {
        var ex = new SyncException.RemoteApiException("Todoist API 返回 500 内部错误");
        assertEquals("Todoist API 返回 500 内部错误", ex.getMessage());
        assertInstanceOf(SyncException.class, ex);
    }

    @Test
    void RemoteApiException_携带消息和原因() {
        var cause = new RuntimeException("HTTP 错误");
        var ex = new SyncException.RemoteApiException("Todoist API 返回 500 内部错误", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void MappingException_携带消息() {
        var ex = new SyncException.MappingException("iCalendar DTSTART 格式解析失败");
        assertEquals("iCalendar DTSTART 格式解析失败", ex.getMessage());
        assertInstanceOf(SyncException.class, ex);
    }

    @Test
    void MappingException_携带消息和原因() {
        var cause = new IllegalArgumentException("日期格式错误");
        var ex = new SyncException.MappingException("iCalendar DTSTART 格式解析失败", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void CredentialException_携带消息() {
        var ex = new SyncException.CredentialException("AES-GCM 解密失败：密钥不正确");
        assertEquals("AES-GCM 解密失败：密钥不正确", ex.getMessage());
        assertInstanceOf(SyncException.class, ex);
    }

    @Test
    void CredentialException_携带消息和原因() {
        var cause = new javax.crypto.AEADBadTagException("认证标签不匹配");
        var ex = new SyncException.CredentialException("AES-GCM 解密失败", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void SyncStateException_携带消息() {
        var ex = new SyncException.SyncStateException("同步状态与预期不一致");
        assertEquals("同步状态与预期不一致", ex.getMessage());
        assertInstanceOf(SyncException.class, ex);
    }

    @Test
    void SyncStateException_携带消息和原因() {
        var cause = new IllegalStateException("状态冲突");
        var ex = new SyncException.SyncStateException("同步状态与预期不一致", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void 所有子类均为RuntimeException() {
        assertInstanceOf(RuntimeException.class, new SyncException.ConnectionException("test"));
        assertInstanceOf(RuntimeException.class, new SyncException.RemoteApiException("test"));
        assertInstanceOf(RuntimeException.class, new SyncException.MappingException("test"));
        assertInstanceOf(RuntimeException.class, new SyncException.CredentialException("test"));
        assertInstanceOf(RuntimeException.class, new SyncException.SyncStateException("test"));
    }

    @Test
    void switch穷举匹配所有子类型() {
        SyncException[] exceptions = {
                new SyncException.ConnectionException("连接失败"),
                new SyncException.RemoteApiException("API 错误"),
                new SyncException.MappingException("映射错误"),
                new SyncException.CredentialException("凭证错误"),
                new SyncException.SyncStateException("状态错误")
        };

        for (SyncException ex : exceptions) {
            // sealed class 穷举匹配验证
            String type = switch (ex) {
                case SyncException.ConnectionException _ -> "connection";
                case SyncException.RemoteApiException _ -> "remote_api";
                case SyncException.MappingException _ -> "mapping";
                case SyncException.CredentialException _ -> "credential";
                case SyncException.SyncStateException _ -> "sync_state";
                default -> throw new AssertionError("未知子类型: " + ex.getClass());
            };
            assertNotNull(type);
        }
    }
}
