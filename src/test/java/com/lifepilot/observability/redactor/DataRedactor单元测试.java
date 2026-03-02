package com.lifepilot.observability.redactor;

import com.lifepilot.observability.config.ObservabilityAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataRedactor 单元测试。
 *
 * <p>验证内置手机号、身份证号、银行卡号、邮箱、IP 地址和 API 密钥脱敏规则，
 * 以及对不含敏感数据文本的幂等性。</p>
 */
@SpringBootTest(
        classes = {ObservabilityAutoConfiguration.class},
        properties = {
                "lifepilot.observability.trace.enabled=false",
                "lifepilot.observability.guardrail.enabled=false",
                "lifepilot.observability.evaluation.enabled=false"
        }
)
class DataRedactor单元测试 {

    @Autowired
    DataRedactor dataRedactor;

    @Test
    @DisplayName("手机号_正确脱敏为中间四位星号()")
    void 手机号_正确脱敏为中间四位星号() {
        String input = "联系方式：13812345678";

        String redacted = dataRedactor.redact(input);

        assertThat(redacted).isEqualTo("联系方式：138****5678");
    }

    @Test
    @DisplayName("身份证号_正确脱敏为中间星号()")
    void 身份证号_正确脱敏为中间星号() {
        String input = "身份证：110101199001011234";

        String redacted = dataRedactor.redact(input);

        assertThat(redacted).isEqualTo("身份证：110***********1234");
    }

    @Test
    @DisplayName("银行卡号_正确脱敏为中间星号()")
    void 银行卡号_正确脱敏为中间星号() {
        String input = "卡号：6222021234560123";

        String redacted = dataRedactor.redact(input);

        assertThat(redacted).isEqualTo("卡号：6222****0123");
    }

    @Test
    @DisplayName("邮箱和IP地址_正确脱敏()")
    void 邮箱和IP地址_正确脱敏() {
        String input = "邮箱：user@example.com，IP：192.168.1.100";

        String redacted = dataRedactor.redact(input);

        assertThat(redacted).isEqualTo("邮箱：u***@example.com，IP：***.***.***.***");
    }

    @Test
    @DisplayName("API密钥_正确脱敏为前缀加星号()")
    void API密钥_正确脱敏为前缀加星号() {
        String input = "密钥：sk-1234567890abcdef";

        String redacted = dataRedactor.redact(input);

        assertThat(redacted).isEqualTo("密钥：sk-****");
    }

    @Test
    @DisplayName("不含敏感数据_redact后保持不变()")
    void 不含敏感数据_redact后保持不变() {
        String input = "今天天气不错";

        String redacted = dataRedactor.redact(input);

        assertThat(redacted).isEqualTo(input);
    }
}

