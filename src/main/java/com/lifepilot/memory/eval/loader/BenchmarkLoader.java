package com.lifepilot.memory.eval.loader;

import java.util.List;

/**
 * 评估数据集加载器。
 *
 * <p>不同 benchmark 的 JSON 格式差异较大，通过本 sealed interface 统一出口，
 * 把异构数据翻译为 {@link BenchmarkCase} 列表。</p>
 *
 * <p>当前 permits：</p>
 * <ul>
 *     <li>{@link LocomoLoader} — snap-research/locomo（Apache-2.0）</li>
 *     <li>{@link LongMemEvalLoader} — xiaowu0162/LongMemEval（MIT, ICLR 2025）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-09
 */
public sealed interface BenchmarkLoader permits LocomoLoader, LongMemEvalLoader {

    /**
     * Benchmark 标识名，如 {@code "locomo"} / {@code "longmemeval"}。
     *
     * <p>与 {@link BenchmarkCase#benchmark()} 保持一致。</p>
     */
    String name();

    /**
     * 加载数据集为 {@link BenchmarkCase} 列表。
     *
     * @param maxCases 最多加载的用例数，{@code &lt;= 0} 表示全部加载
     * @return 用例列表，按数据集内原顺序
     * @throws BenchmarkDatasetNotFoundException 数据集不存在或格式错误
     */
    List<BenchmarkCase> load(int maxCases);
}
