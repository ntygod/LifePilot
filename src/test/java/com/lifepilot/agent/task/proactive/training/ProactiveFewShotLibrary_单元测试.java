package com.lifepilot.agent.task.proactive.training;

import com.lifepilot.agent.task.reminder.ReminderAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProactiveFewShotLibrary 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class ProactiveFewShotLibrary_单元测试 {

    @Test
    void save后load能拿回所有样本(@TempDir Path tempDir) {
        var library = new ProactiveFewShotLibrary(tempDir);
        var samples = List.of(
                new ProactiveFewShotSample("DUE_SOON", "吃药", ReminderAction.NORMAL_PUSH,
                        0.9f, "score=0.8,evi=0.7", Instant.parse("2026-05-09T08:00:00Z"), true),
                new ProactiveFewShotSample("COMMITMENT_GAP", "运动", ReminderAction.SOFT_PUSH,
                        0.15f, "score=0.4", Instant.parse("2026-05-08T20:00:00Z"), false)
        );
        library.save("u1", samples);

        var loaded = library.getSamples("u1", null, 10);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).candidateType()).isEqualTo("DUE_SOON");
        assertThat(loaded.get(0).topicSummary()).isEqualTo("吃药");
        assertThat(loaded.get(0).reward()).isEqualTo(0.9f);
        assertThat(loaded.get(0).positive()).isTrue();
        assertThat(loaded.get(1).positive()).isFalse();
    }

    @Test
    void candidateType过滤生效(@TempDir Path tempDir) {
        var library = new ProactiveFewShotLibrary(tempDir);
        var samples = List.of(
                new ProactiveFewShotSample("DUE_SOON", "A", ReminderAction.NORMAL_PUSH,
                        0.9f, "", Instant.now(), true),
                new ProactiveFewShotSample("COMMITMENT_GAP", "B", ReminderAction.SOFT_PUSH,
                        0.15f, "", Instant.now(), false),
                new ProactiveFewShotSample("DUE_SOON", "C", ReminderAction.NORMAL_PUSH,
                        0.85f, "", Instant.now(), true)
        );
        library.save("u1", samples);

        var result = library.getSamples("u1", "DUE_SOON", 10);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> "DUE_SOON".equals(s.candidateType()));
    }

    @Test
    void limit控制返回条数(@TempDir Path tempDir) {
        var library = new ProactiveFewShotLibrary(tempDir);
        var samples = List.of(
                new ProactiveFewShotSample("DUE_SOON", "A", ReminderAction.NORMAL_PUSH, 0.9f, "", Instant.now(), true),
                new ProactiveFewShotSample("DUE_SOON", "B", ReminderAction.NORMAL_PUSH, 0.85f, "", Instant.now(), true),
                new ProactiveFewShotSample("DUE_SOON", "C", ReminderAction.NORMAL_PUSH, 0.80f, "", Instant.now(), true)
        );
        library.save("u1", samples);

        var result = library.getSamples("u1", null, 2);
        assertThat(result).hasSize(2);
    }

    @Test
    void 空文件返回空列表(@TempDir Path tempDir) {
        var library = new ProactiveFewShotLibrary(tempDir);
        assertThat(library.getSamples("nonexistent", null, 10)).isEmpty();
    }

    @Test
    void 无效参数返回空(@TempDir Path tempDir) {
        var library = new ProactiveFewShotLibrary(tempDir);
        assertThat(library.getSamples(null, null, 10)).isEmpty();
        assertThat(library.getSamples("", null, 10)).isEmpty();
        assertThat(library.getSamples("u1", null, 0)).isEmpty();
    }

    @Test
    void userId含非法字符会转义(@TempDir Path tempDir) {
        var library = new ProactiveFewShotLibrary(tempDir);
        var file = library.userFile("user@domain.com/weird");
        assertThat(file.getFileName().toString()).doesNotContain("/");
        assertThat(file.getFileName().toString()).doesNotContain("@");
    }
}
