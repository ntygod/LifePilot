package com.lifepilot.sync.model;

/**
 * 同步模块异常层次根类。
 *
 * <p>使用 sealed class 限定子类范围，便于 switch 穷举匹配。
 * 所有同步相关的运行时异常均继承此类。
 *
 * @author zsg
 * @since 2026-02-26
 */
public sealed class SyncException extends RuntimeException
        permits SyncException.ConnectionException,
                SyncException.RemoteApiException,
                SyncException.MappingException,
                SyncException.CredentialException,
                SyncException.SyncStateException {

    protected SyncException(String message) {
        super(message);
    }

    protected SyncException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 连接失败异常（网络不可达、认证失败等）。
     *
     * <p>示例：{@code throw new ConnectionException("CalDAV 服务器连接超时")}
     */
    public static final class ConnectionException extends SyncException {

        public ConnectionException(String message) {
            super(message);
        }

        public ConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 远程 API 错误异常（非 2xx HTTP 响应）。
     *
     * <p>示例：{@code throw new RemoteApiException("Todoist API 返回 500 内部错误")}
     */
    public static final class RemoteApiException extends SyncException {

        public RemoteApiException(String message) {
            super(message);
        }

        public RemoteApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 数据映射错误异常（字段转换失败）。
     *
     * <p>示例：{@code throw new MappingException("iCalendar DTSTART 格式解析失败")}
     */
    public static final class MappingException extends SyncException {

        public MappingException(String message) {
            super(message);
        }

        public MappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 凭证错误异常（加密/解密失败）。
     *
     * <p>示例：{@code throw new CredentialException("AES-GCM 解密失败：密钥不正确")}
     */
    public static final class CredentialException extends SyncException {

        public CredentialException(String message) {
            super(message);
        }

        public CredentialException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 同步状态错误异常（状态不一致）。
     *
     * <p>示例：{@code throw new SyncStateException("同步状态与预期不一致：期望 SUCCESS 但为 FAILED")}
     */
    public static final class SyncStateException extends SyncException {

        public SyncStateException(String message) {
            super(message);
        }

        public SyncStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
