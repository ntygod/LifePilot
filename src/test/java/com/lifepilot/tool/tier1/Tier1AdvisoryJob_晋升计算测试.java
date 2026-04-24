package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tier1AdvisoryJob 单元测试 — 覆盖覆盖率过滤、pinned 排除、maxPromoted 限额、禁用短路。
 *
 * @author zsg
 * @since 2026-04-23
 */
class Tier1AdvisoryJob_晋升计算测试 {

    @Test
    void 覆盖率达标_生成PENDING建议() {
        var statsRepo = mock(ToolUsageStatsRepository.class);
        var advisoryRepo = mock(Tier1AdvisoryRepository.class);
        var config = buildConfig(0.3, 3, List.of("file.read"));

        when(statsRepo.computeSessionCoverage(30)).thenReturn(Map.of(
                "file.read", 0.8,                   // 已 pinned 跳过
                "datastore.query_documents", 0.5,   // 达标，候选
                "shell.exec", 0.1                    // 未达标
        ));

        new Tier1AdvisoryJob(config, statsRepo, advisoryRepo).reviewTier1();

        ArgumentCaptor<Tier1Advisory> captor = ArgumentCaptor.forClass(Tier1Advisory.class);
        verify(advisoryRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().toolId()).isEqualTo("datastore.query_documents");
        assertThat(captor.getValue().status()).isEqualTo(Tier1AdvisoryStatus.PENDING);
    }

    @Test
    void maxPromoted限制生成数量_按覆盖率降序选() {
        var statsRepo = mock(ToolUsageStatsRepository.class);
        var advisoryRepo = mock(Tier1AdvisoryRepository.class);
        var config = buildConfig(0.3, 2, List.of());    // maxPromoted=2

        when(statsRepo.computeSessionCoverage(30)).thenReturn(Map.of(
                "a", 0.9, "b", 0.8, "c", 0.7, "d", 0.6));    // 4 个达标

        new Tier1AdvisoryJob(config, statsRepo, advisoryRepo).reviewTier1();

        // 只选 top 2（a, b）
        verify(advisoryRepo, times(2)).save(any());
    }

    @Test
    void promotion禁用_不执行() {
        var statsRepo = mock(ToolUsageStatsRepository.class);
        var advisoryRepo = mock(Tier1AdvisoryRepository.class);
        var config = new ToolConfigProperties();
        config.getTier1().getPromotion().setEnabled(false);

        new Tier1AdvisoryJob(config, statsRepo, advisoryRepo).reviewTier1();
        verifyNoInteractions(statsRepo);
        verifyNoInteractions(advisoryRepo);
    }

    private ToolConfigProperties buildConfig(double threshold, int maxPromoted, List<String> pinned) {
        var config = new ToolConfigProperties();
        config.getTier1().setPinned(pinned);
        config.getTier1().getPromotion().setEnabled(true);
        config.getTier1().getPromotion().setWindowDays(30);
        config.getTier1().getPromotion().setSessionThreshold(threshold);
        config.getTier1().getPromotion().setMaxPromoted(maxPromoted);
        return config;
    }
}
