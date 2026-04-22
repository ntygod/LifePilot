package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * patch 执行结果 —— 成功时附新版本号 + diffJson；失败时附 failedOps。
 *
 * @author zsg
 * @since 2026-04-21
 */
public record DocumentPatchResult(
        boolean success,
        int newVersion,
        @Nullable String diffJson,
        @Nullable String patchSummary,
        List<FailedOp> failedOps
) {

    public static DocumentPatchResult success(int newVersion, String diffJson, String summary) {
        return new DocumentPatchResult(true, newVersion, diffJson, summary, List.of());
    }

    public static DocumentPatchResult failure(List<FailedOp> failedOps) {
        return new DocumentPatchResult(false, -1, null, null, List.copyOf(failedOps));
    }
}
