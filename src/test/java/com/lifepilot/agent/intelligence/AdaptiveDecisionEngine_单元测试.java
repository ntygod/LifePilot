package com.lifepilot.agent.intelligence;

import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdaptiveDecisionEngine 单元测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class AdaptiveDecisionEngine_单元测试 {

    @Test
    void 经验匹配分数低于配置阈值时不注入经验提示() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.75f)));
        var engine = newEngine(intentMatcher, 0.8f);

        var signal = engine.buildDecisionSignal("整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
    }

    @Test
    void 经验匹配分数达到配置阈值时注入经验提示() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.75f)));
        var engine = newEngine(intentMatcher, 0.7f);

        var signal = engine.buildDecisionSignal("整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        assertThat(signal.experienceHint().confidence()).isEqualTo(0.75f);
    }

    @Test
    void 构造器应拒绝非法经验阈值() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(assessor, perceptor, null, 1.1f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("经验匹配最低置信度");
    }

    @Test
    void 构造器应拒绝缺少意图匹配器() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(assessor, perceptor, null, 0.8f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("意图匹配器不能为空");
    }

    private AdaptiveDecisionEngine newEngine(IntentMatcher intentMatcher, float minExperienceConfidence) {
        var assessor = new CapabilityAssessor(10);
        return new AdaptiveDecisionEngine(
                assessor,
                new EnvironmentPerceptor(assessor, 60),
                intentMatcher,
                minExperienceConfidence);
    }

    private ProcedureTemplate template() {
        return new ProcedureTemplate(
                "tpl-project-plan",
                "项目计划模板",
                "按阶段整理项目计划",
                "整理项目计划",
                List.of(),
                Map.of(),
                0.85f,
                3,
                null,
                List.of("trace-1"),
                Instant.now(),
                Instant.now(),
                null,
                null
        );
    }
}
