package com.fivetech.monitor.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.config.MonitorProperties;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.judge.JudgeRule;
import com.fivetech.monitor.engine.judge.Levels;
import com.fivetech.monitor.engine.judge.MonitorJudge;

/**
 * 告警消息内容（繁体）。写待发送记录时生成并存入，重试时不变。
 * <p>2026-10-22 起正文取数据库模板 monitor_notify_template（{@link #alertValues} 提供占位符的值）；
 * 没有可用模板或渲染出错时用 {@code MonitorNotifyTemplate.DEFAULT_CONTENT}（FY-30 格式，与启动时写入的全站默认模板一致）。</p>
 * <pre>
 * 【uWin】Yata 告警 · 嚴重
 * 指標：存款成功率 81.33%
 * 判定：低於嚴重閾值 90.00% · R-001
 * 時間：2026-10-22 14:00（IST）
 * 負責人：zhang@yata.demo
 * </pre>
 * 格式见 FY-30「四、页面与交互」（「查看監控詳情」深链接一期不做）；同一事件只在新触发严重、注意升级为严重时各发一次，不重复提醒（R2）。
 * 原因句（第二行冒号后）和运营总览卡片的「原因」用同一个方法 {@link #reason}。
 *
 * @author fivetech
 */
@Component
public class MonitorMessageBuilder
{
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DashboardProperties dashboard;

    private final MonitorProperties properties;

    public MonitorMessageBuilder(DashboardProperties dashboard, MonitorProperties properties)
    {
        this.dashboard = dashboard;
        this.properties = properties;
    }

    /**
     * 告警模板的占位符值（名字见 {@link MonitorTemplateRenderer#PLACEHOLDERS}，格式按 FY-30「四、页面与交互」）。
     * 没有的值放空串：模板里 {{名字}} 显示「—」，可选段落 {{#名字}}…{{/名字}} 整段不输出。
     *
     * @param trigger 事件的触发周期起点（FY-30「時間」行；升级而来的严重也写原始触发周期）
     * @param recent  判定窗口内各周期的 [值, 基准]（时间正序，含本周期）
     * @param now     生成消息的时间（IST）
     */
    public java.util.Map<String, String> alertValues(MonitorEngineRule rule, JudgeRule judge, boolean escalated,
            LocalDateTime trigger, List<BigDecimal[]> recent, LocalDateTime now)
    {
        java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
        String fmt = rule.getValueFormat();
        boolean drop = JudgeRule.DROP.equals(judge.getJudgeType());
        v.put("site", dashboard.siteName(rule.getSiteCode()));
        v.put("metric", com.fivetech.monitor.service.MonitorText.metricName(rule.getMetricCode(), rule.getMetricName()));
        v.put("level", "嚴重");
        BigDecimal[] last = recent == null || recent.isEmpty() ? null : recent.get(recent.size() - 1);
        v.put("currentValue", last == null || last[0] == null ? "" : display(fmt, last[0]));
        v.put("judgeText", judgeText(fmt, judge, escalated));
        v.put("triggerTime", triggerTime(trigger, judge.getPeriodHours()));
        v.put("ruleCode", String.format(Locale.ROOT, "R-%03d", rule.getRuleNo() == null ? 0 : rule.getRuleNo()));
        v.put("owner", StringUtils.isEmpty(rule.getOwnerEmail()) ? "" : rule.getOwnerEmail());
        v.put("reason", reason(rule, judge, Levels.CRIT, escalated, recent));
        v.put("reasonValue", reasonValue(rule, judge, recent));
        v.put("reasonLine", reasonLine(rule, judge, Levels.CRIT, escalated));
        v.put("baseline", !drop || last == null || last[1] == null ? "" : display(fmt, last[1]));
        v.put("warnLine", judge.hasWarn() ? lineSpec(fmt, judge, judge.getWarnLow(), judge.getWarnHigh()) : "");
        v.put("critLine", lineSpec(fmt, judge, judge.getCritLow(), judge.getCritHigh()));
        int p = judge.getPeriodHours();
        v.put("periodText", (p <= 1 ? "每小時" : "每 " + p + " 小時") + " · 連續 " + judge.getDurationCount() + " 次");
        Integer esc = judge.getEscalateHours();
        v.put("escalateHours", esc == null || esc <= 0 ? "" : String.valueOf(esc));
        String url = properties.getEngine().getEventPageUrl();
        v.put("eventUrl", StringUtils.isEmpty(url) ? "" : url.trim());
        v.put("sentAt", now == null ? "" : now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return v;
    }

    /**
     * FY-30「判定」行（不含规则编号）：下限 / 上限「低於嚴重閾值 90.00%」「高於注意閾值 5.0 min」；
     * 跌幅「跌幅超過嚴重閾值 20%」；区间「超出嚴重區間 2.00% – 8.00%」。
     * 升级而来的严重写注意阈值（或注意区间）并追加「，已升為嚴重」。
     */
    public static String judgeText(String fmt, JudgeRule judge, boolean escalated)
    {
        boolean crit = Levels.CRIT.equals(against(judge, Levels.CRIT, escalated));
        String lv = crit ? "嚴重" : "注意";
        BigDecimal low = crit ? judge.getCritLow() : judge.getWarnLow();
        BigDecimal high = crit ? judge.getCritHigh() : judge.getWarnHigh();
        String s;
        switch (judge.getJudgeType())
        {
            case JudgeRule.LO:
                s = "低於" + lv + "閾值 " + display(fmt, low);
                break;
            case JudgeRule.HI:
                s = "高於" + lv + "閾值 " + display(fmt, high);
                break;
            case JudgeRule.BAND:
                s = "超出" + lv + "區間 " + display(fmt, low) + " – " + display(fmt, high);
                break;
            case JudgeRule.DROP:
                s = "跌幅超過" + lv + "閾值 " + plain(low) + "%";
                break;
            default:
                s = "";
                break;
        }
        return escalated ? s + "，已升為嚴重" : s;
    }

    /**
     * FY-30「時間」行：触发周期的开始时刻。每小时「2026-10-22 14:00（IST）」；多小时周期写时段
     * 「2026-10-22 00:00 – 12:00（IST）」；24 小时周期「2026-10-22 全天」。
     */
    public static String triggerTime(LocalDateTime trigger, int periodHours)
    {
        if (trigger == null)
        {
            return "";
        }
        String day = trigger.toLocalDate().toString();
        if (periodHours >= 24)
        {
            return day + " 全天";
        }
        if (periodHours <= 1)
        {
            return day + " " + trigger.format(HM) + "（IST）";
        }
        LocalDateTime end = trigger.plusHours(periodHours);
        String e = end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? "24:00" : end.format(HM);
        return day + " " + trigger.format(HM) + " – " + e + "（IST）";
    }

    /** 判定线的写法：下限「< 95.00%」、上限「> 5.0 分鐘」、区间「2.00% – 8.00%」、跌幅「跌幅 ≥ 20%」；线没设为空串 */
    static String lineSpec(String fmt, JudgeRule judge, BigDecimal low, BigDecimal high)
    {
        switch (judge.getJudgeType())
        {
            case JudgeRule.LO:
                return low == null ? "" : "< " + format(fmt, low);
            case JudgeRule.HI:
                return high == null ? "" : "> " + format(fmt, high);
            case JudgeRule.BAND:
                return low == null || high == null ? "" : format(fmt, low) + " – " + format(fmt, high);
            case JudgeRule.DROP:
                return low == null ? "" : "跌幅 ≥ " + plain(low) + "%";
            default:
                return "";
        }
    }

    /**
     * 原因句（告警消息第二行）：
     * 「近 2 小時 83.60% – 84.20%，低於嚴重線 90.00%」；升级来的严重在句尾加「，已升為嚴重」。
     * 由 {@link #reasonValue} 和 {@link #reasonLine} 拼成，前端需要单独排版时用那两个。
     */
    public String reason(MonitorEngineRule rule, JudgeRule judge, String level, boolean escalated, List<BigDecimal[]> recent)
    {
        return reasonValue(rule, judge, recent) + "，" + reasonLine(rule, judge, level, escalated)
                + (escalated ? "，已升為嚴重" : "");
    }

    /**
     * 原因的「值」部分（alert.reasonValue）：「近 2 小時 83.60% – 84.20%」；跌幅类为「近 2 小時 較基準下跌 12.4% – 13.1%」。
     *
     * @param recent 判定窗口内各周期的 [值, 基准]（时间正序）
     */
    public String reasonValue(MonitorEngineRule rule, JudgeRule judge, List<BigDecimal[]> recent)
    {
        boolean drop = JudgeRule.DROP.equals(judge.getJudgeType());
        BigDecimal min = null;
        BigDecimal max = null;
        for (BigDecimal[] p : recent)
        {
            BigDecimal v = drop ? MonitorJudge.dropPct(p[0], p[1]) : p[0];
            if (v == null)
            {
                continue;
            }
            min = min == null || v.compareTo(min) < 0 ? v : min;
            max = max == null || v.compareTo(max) > 0 ? v : max;
        }
        String fmt = rule.getValueFormat();
        StringBuilder sb = new StringBuilder();
        sb.append("近 ").append(Math.max(1, recent.size()) * judge.getPeriodHours()).append(" 小時 ");
        if (drop)
        {
            sb.append("較基準下跌 ");
        }
        if (min != null)
        {
            String a = drop ? pct1(min) : format(fmt, min);
            String b = drop ? pct1(max) : format(fmt, max);
            sb.append(min.compareTo(max) == 0 ? a : a + " – " + b);
        }
        else
        {
            sb.append('—');
        }
        return sb.toString();
    }

    /**
     * 原因的「对照线」部分（alert.reasonLine）：「低於嚴重線 90.00%」。不含升级说明，升级由 escalated 字段单独表示。
     * 对照的线：严重且不是升级来的 → 严重线；其余（注意、注意升级）→ 注意线，没有注意线时用严重线。
     */
    public String reasonLine(MonitorEngineRule rule, JudgeRule judge, String level, boolean escalated)
    {
        return lineText(rule.getValueFormat(), judge, level, escalated);
    }

    /** 对照的线：严重且不是升级来的 → CRIT；其余 → WARN（没有注意线时 CRIT） */
    public static String against(JudgeRule judge, String level, boolean escalated)
    {
        return (Levels.CRIT.equals(level) && !escalated) || !judge.hasWarn() ? Levels.CRIT : Levels.WARN;
    }

    /**
     * 运营总览提醒条 judgeValue.window：每小时检测为「近 N 小時」；周期 &gt; 1 小时且只有 1 个周期为时段「00:00 – 12:00」；
     * 多个周期为「近 N 個週期」。
     */
    public static String windowText(int periods, int periodHours, LocalDateTime from, LocalDateTime to)
    {
        int n = Math.max(1, periods);
        if (periodHours <= 1)
        {
            return "近 " + n + " 小時";
        }
        if (n == 1 && from != null && to != null)
        {
            String end = to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? "24:00" : to.format(HM);
            return from.format(HM) + " – " + end;
        }
        return "近 " + n + " 個週期";
    }

    /**
     * 运营总览提醒条 judgeValue.text：窗口内的值「最小 – 最大」；跌幅类「較基準下跌 a% – b%」；
     * 区间类两侧都越线时逐个列出「1.80%、8.60%」。
     *
     * @param recent 判定窗口内各周期的 [值, 基准]（时间正序）
     */
    public String valueText(String valueFormat, JudgeRule judge, String level, boolean escalated, List<BigDecimal[]> recent)
    {
        boolean drop = JudgeRule.DROP.equals(judge.getJudgeType());
        List<BigDecimal> values = new java.util.ArrayList<>();
        for (BigDecimal[] p : recent)
        {
            BigDecimal v = drop ? MonitorJudge.dropPct(p[0], p[1]) : p[0];
            if (v != null)
            {
                values.add(v);
            }
        }
        if (values.isEmpty())
        {
            return drop ? "較基準下跌 —" : "—";
        }
        if (JudgeRule.BAND.equals(judge.getJudgeType()))
        {
            boolean crit = Levels.CRIT.equals(against(judge, level, escalated));
            BigDecimal low = crit ? judge.getCritLow() : judge.getWarnLow();
            BigDecimal high = crit ? judge.getCritHigh() : judge.getWarnHigh();
            boolean below = low != null && values.stream().anyMatch(v -> v.compareTo(low) < 0);
            boolean above = high != null && values.stream().anyMatch(v -> v.compareTo(high) > 0);
            if (below && above)
            {
                StringBuilder sb = new StringBuilder();
                for (BigDecimal v : values)
                {
                    sb.append(sb.length() == 0 ? "" : "、").append(format(valueFormat, v));
                }
                return sb.toString();
            }
        }
        BigDecimal min = values.stream().min(BigDecimal::compareTo).get();
        BigDecimal max = values.stream().max(BigDecimal::compareTo).get();
        String a = drop ? pct1(min) : format(valueFormat, min);
        String b = drop ? pct1(max) : format(valueFormat, max);
        return (drop ? "較基準下跌 " : "") + (min.compareTo(max) == 0 ? a : a + " – " + b);
    }

    /** 卡片 alert.deviation 的计算结果 */
    public static final class Deviation
    {
        private final BigDecimal value;
        private final String against;
        private final String text;

        Deviation(BigDecimal value, String against, String text)
        {
            this.value = value;
            this.against = against;
            this.text = text;
        }

        public BigDecimal getValue() { return value; }
        public String getAgainst() { return against; }
        public String getText() { return text; }
    }

    /**
     * 某个周期离线多远（卡片 alert.deviation / recent[].deviation）：
     * <ul>
     *   <li>下限：值 − 线（负数 = 低于线）「低於嚴重線 6.40%」</li>
     *   <li>上限：值 − 线（正数 = 高于线）「高於嚴重線 3.0 分鐘」</li>
     *   <li>区间：超出一侧到边界的距离，低于下限为负、高于上限为正，区间内为 0「高於嚴重區間上限 1.10%」</li>
     *   <li>跌幅：实际跌幅 − 阈值「跌幅超過嚴重線 5.0%」</li>
     *   <li>未越线：距对照线还有多远「距注意線還有 2.10%」</li>
     * </ul>
     * 对照线：本周期触发了（WARN / CRIT）按 {@link #against}；未触发对注意线（没有注意线对严重线）。
     *
     * @param level 本周期的判定等级（OK / WARN / CRIT / SKIP）
     * @return 值或基准缺失、线没设时返回 null
     */
    public Deviation deviation(String valueFormat, JudgeRule judge, String level, boolean escalated,
            BigDecimal value, BigDecimal baseline)
    {
        boolean triggered = Levels.WARN.equals(level) || Levels.CRIT.equals(level);
        String against = triggered ? against(judge, level, escalated) : (judge.hasWarn() ? Levels.WARN : Levels.CRIT);
        boolean crit = Levels.CRIT.equals(against);
        String name = crit ? "嚴重" : "注意";
        BigDecimal low = crit ? judge.getCritLow() : judge.getWarnLow();
        BigDecimal high = crit ? judge.getCritHigh() : judge.getWarnHigh();
        if (value == null)
        {
            return null;
        }
        switch (judge.getJudgeType())
        {
            case JudgeRule.LO:
            {
                if (low == null)
                {
                    return null;
                }
                BigDecimal d = scaled(valueFormat, value.subtract(low));
                return new Deviation(d, against, d.signum() < 0
                    ? "低於" + name + "線 " + format(valueFormat, d.abs())
                    : "距" + name + "線還有 " + format(valueFormat, d.abs()));
            }
            case JudgeRule.HI:
            {
                if (high == null)
                {
                    return null;
                }
                BigDecimal d = scaled(valueFormat, value.subtract(high));
                return new Deviation(d, against, d.signum() > 0
                    ? "高於" + name + "線 " + format(valueFormat, d)
                    : "距" + name + "線還有 " + format(valueFormat, d.abs()));
            }
            case JudgeRule.BAND:
            {
                if (low == null || high == null)
                {
                    return null;
                }
                if (value.compareTo(low) < 0)
                {
                    BigDecimal d = scaled(valueFormat, value.subtract(low));
                    return new Deviation(d, against, "低於" + name + "區間下限 " + format(valueFormat, d.abs()));
                }
                if (value.compareTo(high) > 0)
                {
                    BigDecimal d = scaled(valueFormat, value.subtract(high));
                    return new Deviation(d, against, "高於" + name + "區間上限 " + format(valueFormat, d));
                }
                BigDecimal room = value.subtract(low).min(high.subtract(value));
                return new Deviation(BigDecimal.ZERO, against, "距" + name + "區間邊界還有 " + format(valueFormat, room));
            }
            case JudgeRule.DROP:
            {
                BigDecimal drop = MonitorJudge.dropPct(value, baseline);
                if (drop == null || low == null)
                {
                    return null;
                }
                BigDecimal d = drop.subtract(low).setScale(1, RoundingMode.HALF_UP);
                return new Deviation(d, against, d.signum() > 0
                    ? "跌幅超過" + name + "線 " + pct1(d)
                    : "距" + name + "線還有 " + pct1(d.abs()));
            }
            default:
                return null;
        }
    }

    /** 偏离值按指标展示精度取整：百分比 2 位、分钟 1 位、倍数 2 位、金额 2 位、人数 0 位 */
    private static BigDecimal scaled(String valueFormat, BigDecimal v)
    {
        String f = valueFormat == null ? "" : valueFormat.toUpperCase(Locale.ROOT);
        int scale = "MIN".equals(f) ? 1 : "INT".equals(f) ? 0 : 2;
        return v.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 阈值、刻度的字符串（不带单位）：百分比 2 位、分钟 1 位、倍数 2 位、金额和人数 0 位；跌幅阈值为整数百分比。
     */
    public static String thresholdText(String valueFormat, String judgeType, BigDecimal v)
    {
        if (v == null)
        {
            return null;
        }
        if (JudgeRule.DROP.equals(judgeType))
        {
            return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        String f = valueFormat == null ? "" : valueFormat.toUpperCase(Locale.ROOT);
        int scale = "PCT".equals(f) || "MULTIPLE".equals(f) ? 2 : "MIN".equals(f) ? 1 : 0;
        return v.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    /** 对照线文案（judgeLine.text / alert.reasonLine）：「低於嚴重線 90.00%」 */
    public String lineText(String valueFormat, JudgeRule judge, String level, boolean escalated)
    {
        String fmt = valueFormat;
        boolean againstCrit = Levels.CRIT.equals(against(judge, level, escalated));
        String line = againstCrit ? "嚴重" : "注意";
        BigDecimal low = againstCrit ? judge.getCritLow() : judge.getWarnLow();
        BigDecimal high = againstCrit ? judge.getCritHigh() : judge.getWarnHigh();
        switch (judge.getJudgeType())
        {
            case JudgeRule.LO:
                return "低於" + line + "線 " + format(fmt, low);
            case JudgeRule.HI:
                return "高於" + line + "線 " + format(fmt, high);
            case JudgeRule.BAND:
                return "超出" + line + "區間 " + format(fmt, low) + " – " + format(fmt, high);
            case JudgeRule.DROP:
                return "跌幅超過" + line + "線 " + plain(low) + "%";
            default:
                return "";
        }
    }

    /**
     * 异常事件列表「判定基準」列（原型 evBaseTxt），每段一个字符串，前端用「 · 」连接、每段不折行：
     * <ul>
     *   <li>下限 / 上限 / 区间：一段「嚴重線 &lt; 90.00%」「注意線 &gt; 5.0 min」「嚴重線 2.00% – 8.00%」。
     *       严重且不是升级来的对照严重线，其余对照注意线（没有注意线时用严重线）。</li>
     *   <li>跌幅：两段「基準 ₹40,000」「較基準下跌 12.4%」（基准和实际跌幅，不是跌幅阈值）；没有基准时为空列表。</li>
     * </ul>
     * 数值格式与原型表格一致：百分比 2 位、分钟 1 位 + min、倍数 2 位 + x、金额和人数 ≥ 100 万用 M。
     */
    public List<String> judgeBasisParts(String valueFormat, JudgeRule judge, String level, boolean escalated,
            BigDecimal currentValue, BigDecimal baseline)
    {
        if (JudgeRule.DROP.equals(judge.getJudgeType()))
        {
            BigDecimal pct = MonitorJudge.dropPct(currentValue, baseline);
            if (baseline == null || pct == null)
            {
                return java.util.Collections.emptyList();
            }
            return java.util.Arrays.asList("基準 " + display(valueFormat, baseline), "較基準下跌 " + pct1(pct));
        }
        boolean againstCrit = (Levels.CRIT.equals(level) && !escalated) || !judge.hasWarn();
        BigDecimal low = againstCrit ? judge.getCritLow() : judge.getWarnLow();
        BigDecimal high = againstCrit ? judge.getCritHigh() : judge.getWarnHigh();
        String line = againstCrit ? "嚴重線 " : "注意線 ";
        switch (judge.getJudgeType())
        {
            case JudgeRule.LO:
                return java.util.Collections.singletonList(line + "< " + display(valueFormat, low));
            case JudgeRule.HI:
                return java.util.Collections.singletonList(line + "> " + display(valueFormat, high));
            case JudgeRule.BAND:
                return java.util.Collections.singletonList(line + display(valueFormat, low) + " – " + display(valueFormat, high));
            default:
                return java.util.Collections.emptyList();
        }
    }

    /** 原型表格的数值格式（util.js fmt）：金额、人数 6 位以内直接显示，7–8 位两位小数加 M，9 位以上取整加 M */
    public static String display(String valueFormat, BigDecimal v)
    {
        if (v == null)
        {
            return "—";
        }
        String f = valueFormat == null ? "" : valueFormat.toUpperCase(Locale.ROOT);
        switch (f)
        {
            case "PCT":
                return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
            case "MIN":
                return v.setScale(1, RoundingMode.HALF_UP).toPlainString() + " min";
            case "MULTIPLE":
                return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "x";
            default:
                String sign = v.signum() < 0 ? "-" : "";
                String money = "MONEY".equals(f) ? "₹" : "";
                BigDecimal a = v.abs();
                if (a.compareTo(BigDecimal.valueOf(99_950_000L)) >= 0)
                {
                    return sign + money + new DecimalFormat("#,##0").format(a.divide(BigDecimal.valueOf(1_000_000L), 0, RoundingMode.HALF_UP)) + "M";
                }
                if (a.compareTo(BigDecimal.valueOf(1_000_000L)) >= 0)
                {
                    return sign + money + a.divide(BigDecimal.valueOf(1_000_000L), 2, RoundingMode.HALF_UP).toPlainString() + "M";
                }
                return sign + money + new DecimalFormat("#,##0").format(a.setScale(0, RoundingMode.HALF_UP));
        }
    }

    /**
     * 异常事件列表「持續」列的悬停文案（原型 y13durTip）：
     * <ul>
     *   <li>异常中：「自 09-13 13:00 起，仍在異常中」</li>
     *   <li>已结束：「09-12 16:00 起，09-12 18:00 回到正常範圍」</li>
     *   <li>已停止判定：「09-13 10:00 起；10:40 規則暫停，之後不再判定」（规则已删除时为「規則刪除」；
     *       停止时间与起点同一天只写时分）</li>
     * </ul>
     *
     * @param status open / resolved / stopped
     */
    public static String durationTip(String status, boolean ruleDeleted, LocalDateTime start, LocalDateTime end,
            LocalDateTime stoppedAt)
    {
        if (start == null)
        {
            return "";
        }
        String a = start.format(MDHM);
        if ("stopped".equals(status))
        {
            if (stoppedAt == null)
            {
                return a + " 起；規則" + (ruleDeleted ? "刪除" : "暫停") + "，之後不再判定";
            }
            String at = stoppedAt.toLocalDate().equals(start.toLocalDate()) ? stoppedAt.format(HM) : stoppedAt.format(MDHM);
            return a + " 起；" + at + " 規則" + (ruleDeleted ? "刪除" : "暫停") + "，之後不再判定";
        }
        if ("resolved".equals(status) && end != null)
        {
            return a + " 起，" + end.format(MDHM) + " 回到正常範圍";
        }
        return "自 " + a + " 起，仍在異常中";
    }

    private static String startText(LocalDateTime start, LocalDateTime lastEnd)
    {
        boolean sameDay = lastEnd != null && start.toLocalDate().equals(lastEnd.minusMinutes(1).toLocalDate());
        return sameDay ? start.format(HM) : start.format(MDHM);
    }

    /** 按指标格式显示：百分比 2 位 + %，分钟 1 位，倍数 2 位 + x，金额 ₹ + 千分位 2 位，人数 / 笔数取整 */
    static String format(String valueFormat, BigDecimal v)
    {
        if (v == null)
        {
            return "—";
        }
        String f = valueFormat == null ? "" : valueFormat.toUpperCase(Locale.ROOT);
        switch (f)
        {
            case "PCT":
                return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
            case "MIN":
                return v.setScale(1, RoundingMode.HALF_UP).toPlainString() + " 分鐘";
            case "MULTIPLE":
                return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "x";
            case "MONEY":
                return (v.signum() < 0 ? "-₹" : "₹") + new DecimalFormat("#,##0.00").format(v.abs());
            case "INT":
                return new DecimalFormat("#,##0").format(v.setScale(0, RoundingMode.HALF_UP));
            default:
                return v.stripTrailingZeros().toPlainString();
        }
    }

    private static String pct1(BigDecimal v)
    {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String plain(BigDecimal v)
    {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
