package com.lifepilot.memory.eval.loader;

/**
 * 当指定 benchmark 数据集未下载或格式错误时抛出。
 *
 * <p>异常信息会建议用户运行 {@code scripts/download-eval-datasets.sh} 下载数据集。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class BenchmarkDatasetNotFoundException extends RuntimeException {

    public BenchmarkDatasetNotFoundException(String benchmark, String details) {
        super(buildMessage(benchmark, details, null));
    }

    public BenchmarkDatasetNotFoundException(String benchmark, String details, Throwable cause) {
        super(buildMessage(benchmark, details, cause), cause);
    }

    private static String buildMessage(String benchmark, String details, Throwable cause) {
        StringBuilder sb = new StringBuilder("Benchmark ").append(benchmark)
                .append(" 数据集加载失败: ").append(details);
        sb.append("。请先运行 scripts/download-eval-datasets.sh 下载数据集到 ")
                .append("~/.zhiwei/eval-cache/ 或通过 lifepilot.memory.eval.dataset-cache-dir 覆盖路径。");
        if (cause != null) {
            sb.append(" 原因: ").append(cause.getClass().getSimpleName())
                    .append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }
}
