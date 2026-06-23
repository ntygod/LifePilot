package com.lifepilot.memory.consolidation.association;

import com.lifepilot.agent.learning.consolidation.association.AssociationCandidate;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateStore;
import com.lifepilot.agent.learning.consolidation.association.AssociationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AssociationCandidateStore 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class AssociationCandidateStore_单元测试 {

    @Test
    void save后load能拿回样本(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var today = LocalDate.now();
        var list = List.of(
                new AssociationCandidate("a", "b", AssociationType.CAUSES, 0.9f, "e1", "seed", Instant.EPOCH),
                new AssociationCandidate("c", "d", AssociationType.SIMILAR_TO, 0.7f, "e2", "seed", Instant.EPOCH)
        );
        store.save(today, list);

        var loaded = store.load(today);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).sourceEntityId()).isEqualTo("a");
        assertThat(loaded.get(1).relationType()).isEqualTo(AssociationType.SIMILAR_TO);
    }

    @Test
    void 多次save合并而非覆盖(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var today = LocalDate.now();
        store.save(today, List.of(
                new AssociationCandidate("a", "b", AssociationType.CAUSES, 0.9f, null, "seed", null)));
        store.save(today, List.of(
                new AssociationCandidate("c", "d", AssociationType.RELATED_TO, 0.7f, null, "seed", null)));

        assertThat(store.load(today)).hasSize(2);
    }

    @Test
    void 空输入不创建文件(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        store.save(LocalDate.now(), List.of());
        assertThat(tempDir.toFile().listFiles()).isNullOrEmpty();
    }

    @Test
    void 不存在的日期返回空列表(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        assertThat(store.load(LocalDate.of(2020, 1, 1))).isEmpty();
    }

    @Test
    void 候选文件损坏时load应失败(@TempDir Path tempDir) throws Exception {
        var store = new AssociationCandidateStore(tempDir);
        var date = LocalDate.of(2026, 6, 23);
        Files.writeString(store.fileFor(date), "{不是合法JSON");

        assertThatThrownBy(() -> store.load(date))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 联想: 读取失败 date=2026-06-23");
    }

    @Test
    void 候选文件损坏时save不应覆盖旧文件(@TempDir Path tempDir) throws Exception {
        var store = new AssociationCandidateStore(tempDir);
        var date = LocalDate.of(2026, 6, 23);
        var file = store.fileFor(date);
        Files.writeString(file, "{不是合法JSON");

        assertThatThrownBy(() -> store.save(date, List.of(
                new AssociationCandidate("a", "b", AssociationType.CAUSES, 0.9f, null, "seed", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 联想: 读取失败 date=2026-06-23");
        assertThat(Files.readString(file)).isEqualTo("{不是合法JSON");
    }

    @Test
    void null参数返回空或静默(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        assertThat(store.load(null)).isEmpty();
        store.save(null, null);
        store.save(LocalDate.now(), null);
    }
}
