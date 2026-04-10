package com.lifepilot.memory.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * SQLite BUSY 重试工具 — 指数退避，最多重试 3 次。
 *
 * <p>SQLite WAL 模式下并发写入可能触发 SQLITE_BUSY_SNAPSHOT，
 * 此工具在事务外层重试，确保每次重试使用新的事务和快照。</p>
 *
 * @author zsg
 * @since 2026-04-10
 */
public final class SqliteBusyRetry {

    private static final Logger log = LoggerFactory.getLogger(SqliteBusyRetry.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 200;

    private SqliteBusyRetry() {}

    /** 带返回值的重试。 */
    public static <T> T execute(Supplier<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (Exception e) {
                if (attempt >= MAX_RETRIES || !isSqliteBusy(e)) {
                    throw e;
                }
                long delay = BASE_DELAY_MS * (1L << attempt);
                log.debug("SQLite BUSY 重试: attempt={}, delayMs={}", attempt + 1, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 无返回值的重试。 */
    public static void run(Runnable operation) {
        execute(() -> { operation.run(); return null; });
    }

    /** 判断异常链中是否包含 SQLite BUSY 错误。 */
    static boolean isSqliteBusy(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.sqlite.SQLiteException sqliteEx
                    && sqliteEx.getResultCode() != null
                    && sqliteEx.getResultCode().name().startsWith("SQLITE_BUSY")) {
                return true;
            }
        }
        return false;
    }
}
