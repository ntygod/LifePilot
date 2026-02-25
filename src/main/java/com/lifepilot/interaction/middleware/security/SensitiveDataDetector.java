package com.lifepilot.interaction.middleware.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据检测器。
 * <p>
 * 检测文本中的手机号、身份证号、银行卡号和邮箱地址，
 * 并生成脱敏后的内容。身份证号使用加权求和校验算法验证，
 * 银行卡号使用 Luhn 算法验证，仅校验通过的才报告为违规。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SensitiveDataDetector {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataDetector.class);

    /** 中国大陆手机号：1[3-9] 开头的 11 位数字 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 18 位身份证号（最后一位可为 X/x） */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 银行卡号：16-19 位纯数字 */
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    /** 邮箱地址 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    /** 身份证校验位权重因子 */
    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 身份证校验码映射表 */
    private static final char[] ID_CARD_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /**
     * 检测文本中的敏感数据并生成脱敏版本。
     *
     * @param content 待检测的文本内容
     * @return 检测结果，包含违规列表和脱敏后的内容
     */
    public SensitiveDataResult detect(String content) {
        if (content == null || content.isBlank()) {
            return new SensitiveDataResult(List.of(), "");
        }

        var violations = new ArrayList<SecurityViolation>();
        String redacted = content;

        // 已匹配区间集合，用于避免重复匹配（身份证 18 位 vs 银行卡 16-19 位 vs 手机号 11 位）
        var matchedRanges = new HashSet<String>();

        // 1. 先检测身份证号（18 位 + 校验位验证）
        redacted = detectAndRedact(redacted, ID_CARD_PATTERN, "身份证号", violations, matchedRanges,
                this::validateIdCard, this::maskIdCard);

        // 2. 再检测银行卡号（16-19 位 + Luhn 校验，排除已匹配的身份证）
        redacted = detectAndRedact(redacted, BANK_CARD_PATTERN, "银行卡号", violations, matchedRanges,
                this::validateLuhn, this::maskBankCard);

        // 3. 检测手机号（11 位，排除已匹配的区间）
        redacted = detectAndRedact(redacted, PHONE_PATTERN, "手机号", violations, matchedRanges,
                match -> true, this::maskPhone);

        // 4. 检测邮箱
        redacted = detectAndRedact(redacted, EMAIL_PATTERN, "邮箱地址", violations, matchedRanges,
                match -> true, this::maskEmail);

        return new SensitiveDataResult(List.copyOf(violations), redacted);
    }

    /**
     * 通用检测与脱敏方法。
     * <p>
     * 对匹配到的内容先进行校验（validator），校验通过则记录违规并脱敏（masker）。
     * 使用 matchedRanges 避免同一位置被多种模式重复匹配。
     */
    private String detectAndRedact(String content, Pattern pattern, String dataType,
                                   List<SecurityViolation> violations, HashSet<String> matchedRanges,
                                   java.util.function.Predicate<String> validator,
                                   java.util.function.UnaryOperator<String> masker) {
        Matcher matcher = pattern.matcher(content);
        var sb = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            String rangeKey = matcher.start() + ":" + matcher.end();

            // 检查是否与已匹配区间重叠
            if (isOverlapping(matcher.start(), matcher.end(), matchedRanges)) {
                continue;
            }

            if (validator.test(matched)) {
                matchedRanges.add(rangeKey);
                log.warn("检测到敏感数据: type={}, masked={}", dataType, masker.apply(matched));
                violations.add(new SecurityViolation(
                        "sensitive_data",
                        "MEDIUM",
                        "检测到" + dataType,
                        null
                ));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(matched)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 检查指定区间是否与已匹配的区间重叠。
     */
    private boolean isOverlapping(int start, int end, HashSet<String> matchedRanges) {
        for (String range : matchedRanges) {
            String[] parts = range.split(":");
            int existStart = Integer.parseInt(parts[0]);
            int existEnd = Integer.parseInt(parts[1]);
            // 区间有交集
            if (start < existEnd && end > existStart) {
                return true;
            }
        }
        return false;
    }

    /**
     * 验证 18 位身份证号校验位。
     * <p>
     * 使用加权求和算法：sum = Σ(digit[i] * weight[i])，checkCode = CHECK_CODES[sum % 11]。
     *
     * @param idCard 18 位身份证号字符串
     * @return 校验位是否正确
     */
    boolean validateIdCard(String idCard) {
        if (idCard.length() != 18) {
            return false;
        }
        // 前 17 位必须是数字
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(idCard.charAt(i))) {
                return false;
            }
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idCard.charAt(i) - '0') * ID_CARD_WEIGHTS[i];
        }
        char expectedCheck = ID_CARD_CHECK_CODES[sum % 11];
        char actualCheck = Character.toUpperCase(idCard.charAt(17));
        return expectedCheck == actualCheck;
    }

    /**
     * 使用 Luhn 算法验证银行卡号。
     * <p>
     * 从右向左，偶数位（从 1 开始计数）乘以 2，大于 9 则减 9，
     * 所有位求和，能被 10 整除则有效。
     *
     * @param cardNumber 银行卡号字符串
     * @return Luhn 校验是否通过
     */
    boolean validateLuhn(String cardNumber) {
        if (cardNumber.length() < 16 || cardNumber.length() > 19) {
            return false;
        }
        for (int i = 0; i < cardNumber.length(); i++) {
            if (!Character.isDigit(cardNumber.charAt(i))) {
                return false;
            }
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int digit = cardNumber.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    /**
     * 手机号脱敏：保留前 3 位和后 4 位。
     * <p>
     * 示例：138****1234
     */
    String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 身份证号脱敏：保留前 3 位和后 4 位。
     * <p>
     * 示例：110***********1234
     */
    String maskIdCard(String idCard) {
        return idCard.substring(0, 3) + "*".repeat(idCard.length() - 7) + idCard.substring(idCard.length() - 4);
    }

    /**
     * 银行卡号脱敏：保留前 4 位和后 4 位。
     * <p>
     * 示例：6222****1234
     */
    String maskBankCard(String card) {
        return card.substring(0, 4) + "*".repeat(card.length() - 8) + card.substring(card.length() - 4);
    }

    /**
     * 邮箱脱敏：保留 local part 首字符。
     * <p>
     * 示例：u***@example.com
     */
    String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    /**
     * 敏感数据检测结果。
     *
     * @param violations      检测到的安全违规列表
     * @param redactedContent 脱敏后的内容
     */
    public record SensitiveDataResult(List<SecurityViolation> violations, String redactedContent) {
        /** 防御性拷贝 */
        public SensitiveDataResult {
            violations = List.copyOf(violations);
        }
    }
}
