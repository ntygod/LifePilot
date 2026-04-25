package com.lifepilot.agent.context;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.spec.SkillPriority;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 选择召回率回归测试。
 */
class SkillCatalogQuality_召回率回归测试 {

    @SuppressWarnings("unchecked")
    @Test
    void 固定fixture集_top3召回率不低于80percent() throws Exception {
        List<ContextAssembler.SkillCatalogEntry> entries = loadAllProductionSkills();
        assertThat(entries).as("应能从 src/main/resources/skills 加载到 SKILL.md").isNotEmpty();

        Map<String, Object> root;
        try (InputStream is = getClass().getResourceAsStream("/skill-selection-fixtures.yaml")) {
            assertThat(is).as("fixture yaml 必须存在").isNotNull();
            root = new Yaml().load(is);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) root.get("fixtures");
        assertThat(fixtures).isNotEmpty();

        int hits = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> fx : fixtures) {
            String query = (String) fx.get("query");
            @SuppressWarnings("unchecked")
            List<String> expected = (List<String>) fx.get("expected_top_3");

            List<String> keywords = ContextAssembler.extractKeywords(query);
            List<String> actualTop3 = entries.stream()
                    .sorted(Comparator
                            .comparingInt((ContextAssembler.SkillCatalogEntry e) ->
                                    -ContextAssembler.scoreEntry(e, keywords))
                            .thenComparing(ContextAssembler.SkillCatalogEntry::name))
                    .limit(3)
                    .map(ContextAssembler.SkillCatalogEntry::name)
                    .toList();

            if (!Collections.disjoint(actualTop3, expected)) {
                hits++;
            } else {
                failures.add("query=\"" + query + "\", expected=" + expected
                        + ", actual=" + actualTop3);
            }
        }

        double recall = (double) hits / fixtures.size();
        System.out.printf("Skill recall: %d / %d = %.2f%%%n",
                hits, fixtures.size(), recall * 100);
        if (!failures.isEmpty()) {
            failures.forEach(System.out::println);
        }

        assertThat(recall).isGreaterThanOrEqualTo(0.80);
    }

    private static List<ContextAssembler.SkillCatalogEntry> loadAllProductionSkills() throws IOException {
        Path skillsRoot = Path.of("src", "main", "resources", "skills");
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        var parser = new MarkdownSkillParser();
        var entries = new ArrayList<ContextAssembler.SkillCatalogEntry>();
        try (Stream<Path> dirs = Files.list(skillsRoot)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) continue;
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.exists(skillMd)) continue;
                try {
                    String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                    ParsedSkill parsed = parser.parse(content);
                    SkillPriority priority = parsed.frontmatter().zhiweiMeta().priority();
                    entries.add(new ContextAssembler.SkillCatalogEntry(
                            parsed.frontmatter().name(),
                            parsed.frontmatter().description(),
                            parsed.frontmatter().zhiweiMeta().tags(),
                            priority == null ? SkillPriority.NORMAL : priority));
                } catch (Exception e) {
                    System.err.println("skip: " + dir + ", error=" + e.getMessage());
                }
            }
        }
        return entries;
    }
}