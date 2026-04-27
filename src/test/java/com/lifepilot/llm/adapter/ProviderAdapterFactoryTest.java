package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import com.lifepilot.llm.thinking.AnthropicThinkingProtocol;
import com.lifepilot.llm.thinking.DeepSeekThinkingProtocol;
import com.lifepilot.llm.thinking.NoopThinkingProtocol;
import com.lifepilot.llm.thinking.OpenAiReasoningEffortProtocol;
import com.lifepilot.llm.thinking.QwenThinkingProtocol;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProviderAdapterFactory 路由分支契约测试。
 *
 * <p>覆盖每个 baseAdapter 路由出正确的 Adapter 子类 + 注入正确的 ThinkingProtocol。
 * Fix-1 的 OPENAI_BASE 分支按 thinkingProtocol enum 路由，本测试也覆盖该路由的穷尽性。
 *
 * @author zsg
 * @since 2026-04-27
 */
class ProviderAdapterFactoryTest {

    private ProviderAdapterFactory factory;

    @BeforeEach
    void setUp() {
        ProviderProfileRegistry registry = new ProviderProfileRegistry();
        registry.init();
        List<ThinkingProtocol> protocols = List.of(
                new NoopThinkingProtocol(),
                new DeepSeekThinkingProtocol(),
                new QwenThinkingProtocol(),
                new OpenAiReasoningEffortProtocol(),
                new AnthropicThinkingProtocol()
        );
        factory = new ProviderAdapterFactory(List.of(), null, registry, protocols);
    }

    @Test
    void DeepSeek_profile_路由到_DeepSeekProviderAdapter() {
        var config = providerConfig("deepseek-official", ProviderType.OPENAI_COMPATIBLE,
                "https://api.deepseek.com");
        var adapter = factory.create(config);
        assertThat(adapter).isInstanceOf(DeepSeekProviderAdapter.class);
        assertThat(((OpenAiBaseProviderAdapter) adapter).thinkingProtocol().id())
                .isEqualTo(ThinkingProtocolId.DEEPSEEK);
    }

    @Test
    void Qwen_profile_路由到_QwenProviderAdapter() {
        var config = providerConfig("qwen-dashscope", ProviderType.OPENAI_COMPATIBLE,
                "https://dashscope.aliyuncs.com/compatible-mode");
        var adapter = factory.create(config);
        assertThat(adapter).isInstanceOf(QwenProviderAdapter.class);
        assertThat(((OpenAiBaseProviderAdapter) adapter).thinkingProtocol().id())
                .isEqualTo(ThinkingProtocolId.QWEN);
    }

    @Test
    void OpenAI_official_profile_路由到_OpenAiOfficialProviderAdapter() {
        var config = providerConfig("openai-official", ProviderType.OPENAI_COMPATIBLE,
                "https://api.openai.com");
        var adapter = factory.create(config);
        assertThat(adapter).isInstanceOf(OpenAiOfficialProviderAdapter.class);
        assertThat(((OpenAiBaseProviderAdapter) adapter).thinkingProtocol().id())
                .isEqualTo(ThinkingProtocolId.OPENAI_REASONING_EFFORT);
    }

    @Test
    void Anthropic_profile_路由到_AnthropicProviderAdapter() {
        var config = providerConfig("anthropic-official", ProviderType.ANTHROPIC,
                "https://api.anthropic.com");
        var adapter = factory.create(config);
        assertThat(adapter).isInstanceOf(AnthropicProviderAdapter.class);
    }

    @Test
    void Ollama_profile_路由到_OllamaProviderAdapter() {
        var config = providerConfig("ollama-local", ProviderType.OLLAMA,
                "http://localhost:11434");
        var adapter = factory.create(config);
        assertThat(adapter).isInstanceOf(OllamaProviderAdapter.class);
    }

    @Test
    void 火山方舟_DEEPSEEK_协议复用_DeepSeekProviderAdapter() {
        var config = providerConfig("volcengine-ark", ProviderType.OPENAI_COMPATIBLE,
                "https://ark.cn-beijing.volces.com/api");
        var adapter = factory.create(config);
        assertThat(adapter).isInstanceOf(DeepSeekProviderAdapter.class);
    }

    @Test
    void NONE_协议_provider_默认走_OpenAiBaseProviderAdapter() {
        var config = providerConfig("zhipu-bigmodel", ProviderType.OPENAI_COMPATIBLE,
                "https://open.bigmodel.cn/api/paas");
        var adapter = factory.create(config);
        assertThat(adapter).isExactlyInstanceOf(OpenAiBaseProviderAdapter.class);
        assertThat(((OpenAiBaseProviderAdapter) adapter).thinkingProtocol().id())
                .isEqualTo(ThinkingProtocolId.NONE);
    }

    private ProviderConfig providerConfig(String profileId, ProviderType type, String apiUrl) {
        return new ProviderConfig(
                "test-id",
                type,
                profileId,
                apiUrl,
                "sk-test",
                "test-model",
                30,
                0,
                List.of("default"),
                Set.of(ProviderCapability.CHAT),
                true,
                0,
                0,
                0,
                null,
                true
        );
    }
}
