package com.lifepilot.interaction.middleware.audit;

import java.util.regex.Pattern;

/**
 * 轻量级数据脱敏器（临时实现），用于审计日志脱敏。
 *
 * <p>脱敏规则：手机号 138****1234、身份证 110***********1234、
 * 银行卡 6222****1234、邮箱 u***@example.com。
 * 待 observability 模块完成后替换为完整实现。</p>
 *
 * @author zsg
 * @since 2026-02-25
 * @deprecated 请使用 {@link com.lifepilot.observability.redactor.DataRedactor}
 */
@Deprecated(forRemoval = true)
public class DataRedactor {

    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("\\d{16,19}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    /**
     * 对内容中的敏感数据进行脱敏处理。
     *
     * @param content 原始内容，null 返回空字符串
     * @return 脱敏后的内容
     */
    public String redact(String content) {
        if (content == null) {
            return "";
        }
        String result = content;
        result = PHONE_PATTERN.matcher(result).replaceAll(m -> maskPhone(m.group()));
        result = ID_CARD_PATTERN.matcher(result).replaceAll(m -> maskIdCard(m.group()));
        result = BANK_CARD_PATTERN.matcher(result).replaceAll(m -> maskBankCard(m.group()));
        result = EMAIL_PATTERN.matcher(result).replaceAll(m -> maskEmail(m.group()));
        return result;
    }

    /** 手机号脱敏：138****1234 */
    private static String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 身份证脱敏：110***********1234 */
    private static String maskIdCard(String idCard) {
        return idCard.substring(0, 3) + "***********" + idCard.substring(14);
    }

    /** 银行卡脱敏：6222****1234 */
    private static String maskBankCard(String bankCard) {
        return bankCard.substring(0, 4) + "****" + bankCard.substring(bankCard.length() - 4);
    }

    /** 邮箱脱敏：u***@example.com */
    private static String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
