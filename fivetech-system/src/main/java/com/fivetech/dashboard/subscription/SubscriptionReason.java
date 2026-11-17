package com.fivetech.dashboard.subscription;

import com.fivetech.common.lark.LarkErrorCategory;
import com.fivetech.common.lark.LarkResult;

/**
 * 发送历史的原因代码（V0.4 原型 push.js 的 PRSN，另加 rejected、data）：每个不成功的「一次发送 × 一个接收群」带一个代码，
 * 页面显示固定的原因短语与说明（影响与处理），Lark 原文只进应用日志和 log.error。
 *
 * <p>结果只有四种：{@link #OK} 成功、{@link #PART} 部分失敗、{@link #FAIL} 失敗、{@link #SKIP} 已跳過。
 * 成功没有原因代码。</p>
 *
 * @author fivetech
 */
public enum SubscriptionReason
{
    /** 机器人已不在群里、群已解散、无权向该群发消息 */
    GONE("gone", SubscriptionReason.FAIL, "機器人不在群", "機器人已不在這個群；重新拉進同一個群後自動恢復"),
    /** 本站改由其他机器人服务（多机器人 FY-43，本期不会出现） */
    MOVED("moved", SubscriptionReason.FAIL, "機器人不服務本站", "本站已改由其他機器人服務；改選群，或把新機器人拉進這個群"),
    TIMEOUT("timeout", SubscriptionReason.FAIL, "Lark 回應逾時", "未在時限內回應，已自動重試 2 次"),
    LIMIT("limit", SubscriptionReason.FAIL, "Lark 限流", "超過發送頻率限制，已自動重試 2 次"),
    SERVER("server", SubscriptionReason.FAIL, "Lark 服務錯誤", "Lark 返回 5xx，已自動重試 2 次"),
    /** 未归入以上的 Lark 拒绝（其他 4xx、未识别的错误码）：不重试 */
    REJECTED("rejected", SubscriptionReason.FAIL, "Lark 拒絕發送", "Lark 拒絕了這條訊息，重試不會成功；請聯繫管理員查看日誌"),
    /** 取数失败（定时发送补发期内重试都失败、或立即发送时失败）：本次所有群都未发送 */
    DATA("data", SubscriptionReason.FAIL, "資料取數失敗", "資料平台取數失敗，本次所有群都未發送"),
    /** 截图失败：本次所有群都未发送（2026-11-13 确认按原型） */
    SHOT("shot", SubscriptionReason.FAIL, "截圖生成失敗", "頁面截圖沒有生成，本次所有群都未發送"),
    /** 卡片、截图分两条发时才会出现（现在截图嵌在卡片里，预留） */
    IMG("img", SubscriptionReason.PART, "截圖未送達", "卡片已送達；截圖發送失敗，已自動重試 2 次"),
    CARD("card", SubscriptionReason.PART, "卡片未送達", "截圖已送達；卡片發送失敗，已自動重試 2 次"),
    /** 机器人凭证失效或没有配置：暂停发送，不算失败，恢复后不补发 */
    CRED("cred", SubscriptionReason.SKIP, "機器人憑證失效", "暫停發送；更新憑證後自動恢復，不補發"),
    /** 机器人已停用（多机器人 FY-43，本期不会出现） */
    OFF("off", SubscriptionReason.SKIP, "機器人已停用", "暫停發送；重新啟用後自動恢復，不補發");

    public static final String OK = "OK";

    public static final String PART = "PART";

    public static final String FAIL = "FAIL";

    public static final String SKIP = "SKIP";

    /** 发送中（批次未结束）；超过 10 分钟仍是 RUNNING 的由定时任务改为 FAIL */
    public static final String RUNNING = "RUNNING";

    private final String code;

    private final String result;

    private final String text;

    private final String note;

    SubscriptionReason(String code, String result, String text, String note)
    {
        this.code = code;
        this.result = result;
        this.text = text;
        this.note = note;
    }

    public String getCode() { return code; }

    public String getResult() { return result; }

    public String getText() { return text; }

    public String getNote() { return note; }

    /** 代码 → 原因；不认识返回 null */
    public static SubscriptionReason of(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (SubscriptionReason r : values())
        {
            if (r.code.equalsIgnoreCase(code.trim()))
            {
                return r;
            }
        }
        return null;
    }

    /** Lark 发送失败 → 原因（发群）。成功返回 null */
    public static SubscriptionReason of(LarkResult<?> r)
    {
        if (r == null || r.isSuccess())
        {
            return null;
        }
        if (r.getCategory() == LarkErrorCategory.CREDENTIAL_INVALID)
        {
            return CRED;
        }
        if (r.getCategory() == LarkErrorCategory.TARGET_INVALID)
        {
            return GONE;
        }
        if (r.isRateLimited())
        {
            return LIMIT;
        }
        if (r.getHttpStatus() >= 500)
        {
            return SERVER;
        }
        if (r.getHttpStatus() == 0)
        {
            return TIMEOUT;
        }
        return REJECTED;
    }

    /** 超时、限流、5xx 才自动重试 */
    public boolean retryable()
    {
        return this == TIMEOUT || this == LIMIT || this == SERVER;
    }

    /** 一次发送的汇总结果：各群结果相同就是该结果，否则为部分失敗；没有群为 FAIL */
    public static String aggregate(java.util.Collection<String> results)
    {
        java.util.Set<String> u = new java.util.LinkedHashSet<>(results);
        if (u.isEmpty())
        {
            return FAIL;
        }
        return u.size() == 1 ? u.iterator().next() : PART;
    }

    /** 结果 → 订阅表 last_status（SUCCESS / PARTIAL / FAILED / SKIPPED） */
    public static String lastStatus(String result)
    {
        switch (result == null ? FAIL : result)
        {
            case OK: return "SUCCESS";
            case PART: return "PARTIAL";
            case SKIP: return "SKIPPED";
            default: return "FAILED";
        }
    }

    /** 结果的中文名 */
    public static String resultText(String result)
    {
        switch (result == null ? "" : result)
        {
            case OK: return "成功";
            case PART: return "部分失敗";
            case FAIL: return "失敗";
            case SKIP: return "已跳過";
            case RUNNING: return "發送中";
            default: return result;
        }
    }
}
