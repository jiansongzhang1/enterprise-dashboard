package com.fivetech.dashboard.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import com.fivetech.dashboard.enums.Granularity;
import com.fivetech.dashboard.format.ValueFormat;

/**
 * 订阅简报：把运营总览的查询结果（{@link OverviewVO}）整理成一张 Lark 卡片。纯函数，不查库、不调接口，便于单测。
 *
 * <p>卡片：表头「站点 · 订阅名称」（测试发送加【測試】）→ 说明（选填）→ 统计周期 / 对比周期 → 指标两列排开
 * （名称、数值、环比；环比按指标方向着色：越高越好的涨为绿、跌为红，越低越好的相反，区间型和无方向为灰）
 * → 底注（资料截至、发送频率、时区）→「在 Yata 中查看」（配置了地址才有）。</p>
 *
 * <p>开了 AI 摘要的订阅：{@link #applyAi} 在统计周期下方加一段「AI 摘要」（模型只根据 {@link #getFacts()} 里后端算好的数字写文字），
 * 生成失败时卡片不带摘要、照常发送。</p>
 *
 * <p>数据异动分析（{@link #applyAnomaly}）：统计周期内有告警事件或指标明显波动时，在 AI 摘要下方加一段不超过 100 字的分析
 * （变化原因），由 {@link SubscriptionAnomalyAnalyst} 结合告警事件生成；没有异动或生成失败时不加。</p>
 *
 * <p>数值与页面同一量纲（比率已 ×100、时长为分钟）；金额用站点币种符号、不缩写，方便核对。
 * 百分比类指标的变化写百分点（pt），其余写相对变化率（%），与运营总览一致。</p>
 *
 * @author fivetech
 */
public final class SubscriptionBrief
{
    public static final String DASH = "—";

    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    /** 一个 div 里最多放几个指标（Lark 卡片两列排开） */
    private static final int FIELDS_PER_DIV = 10;

    private String title;

    private String description;

    private String period;

    private String compare;

    private String asOf;

    private String footer;

    private final List<Map<String, Object>> rows = new ArrayList<>();

    /** 给 AI 的数据：每个指标一行「名稱｜本期｜上期｜變化｜方向｜監控」，数字与卡片完全一致 */
    private final List<String> facts = new ArrayList<>();

    private String siteName;

    private String name;

    private String overviewUrl;

    /** AI 摘要（已通过后端校验）；null = 没有摘要 */
    private String aiSummary;

    private String aiModel;

    private String aiModelLabel;

    /** 开了 AI 但没有摘要的原因（只在预览里给，卡片不显示） */
    private String aiNote;

    private boolean aiRequested;

    private Map<String, Object> card;

    /** 统计周期 [from, to)（站点时区）；查告警事件用 */
    private java.time.LocalDateTime windowFrom;

    /** 订阅要求带截图 */
    private boolean snapshotRequested;

    /** 已上传到 Lark 的截图 */
    private String snapshotKey;

    /** 截图本身（预览时以 data URL 返回，不上传） */
    private byte[] snapshotPng;

    /** 截图失败的原因（卡片里只写「截圖生成失敗」，原因在预览和日志里） */
    private String snapshotError;

    /** 截的页面地址（不含令牌） */
    private String snapshotUrl;

    private java.time.LocalDateTime windowTo;

    /** 每个指标的变化（判断明显波动用），顺序同 rows */
    private final List<Change> changes = new ArrayList<>();

    /** 数据异动分析（已通过校验）；null = 没有 */
    private String anomaly;

    /** 开了 AI、但没有异动分析的原因（预览里给） */
    private String anomalyNote;

    private boolean anomalyRequested;

    /** 一个指标的变化 */
    public static final class Change
    {
        public final String code;
        public final String label;
        /** 卡片上的变化文字，如「+12.3%」「-2.51pt」 */
        public final String text;
        public final java.math.BigDecimal deltaPct;
        public final java.math.BigDecimal deltaPt;
        public final String direction;

        Change(String code, String label, String text, java.math.BigDecimal deltaPct, java.math.BigDecimal deltaPt, String direction)
        {
            this.code = code;
            this.label = label;
            this.text = text;
            this.deltaPct = deltaPct;
            this.deltaPt = deltaPt;
            this.direction = direction;
        }
    }

    private SubscriptionBrief()
    {
    }

    /** 卡片要用到、但 OverviewVO 里没有的东西 */
    public static final class Context
    {
        public String siteName;

        public String name;

        public String description;

        public String currencySymbol;

        public String timezone;

        public Integer sendHour;

        public String overviewUrl;

        public boolean test;
    }

    /**
     * @param vo    运营总览查询结果（只用 METRICS 块、slot、compare、asOf）
     * @param codes 要输出的指标，已按站点顺序排好；vo 里没有的（本站不提供）跳过
     */
    public static SubscriptionBrief build(OverviewVO vo, List<String> codes, Context ctx)
    {
        SubscriptionBrief b = new SubscriptionBrief();
        b.title = (ctx.test ? "【測試】" : "") + nvl(ctx.siteName) + " · " + nvl(ctx.name);
        b.siteName = ctx.siteName;
        b.name = ctx.name;
        b.overviewUrl = ctx.overviewUrl;
        b.description = ctx.description == null || ctx.description.trim().isEmpty() ? null : ctx.description.trim();
        Granularity g = vo.getSlot() == null ? Granularity.HOUR : vo.getSlot().getGranularity();
        b.period = vo.getSlot() == null ? DASH : span(vo.getSlot().getFrom(), vo.getSlot().getTo(), g);
        b.compare = vo.getCompare() == null ? DASH : span(vo.getCompare().getFrom(), vo.getCompare().getTo(), g);
        b.asOf = vo.getAsOf() == null ? DASH : shortTime(vo.getAsOf());
        b.footer = "資料截至 " + b.asOf
            + (ctx.sendHour == null ? "" : " · 每日 " + String.format("%02d:00", ctx.sendHour) + " 發送")
            + (ctx.timezone == null ? "" : " · " + ctx.timezone);

        if (vo.getSlot() != null)
        {
            b.windowFrom = parseSlot(vo.getSlot().getFrom());
            b.windowTo = parseSlot(vo.getSlot().getTo());
        }

        Map<String, MetricCardVO> byCode = new LinkedHashMap<>();
        if (vo.getBlocks().get("METRICS") instanceof MetricsBlockVO)
        {
            for (MetricCardVO c : ((MetricsBlockVO) vo.getBlocks().get("METRICS")).getItems())
            {
                byCode.put(c.getCode(), c);
            }
        }
        for (String code : codes)
        {
            MetricCardVO c = byCode.get(code);
            if (c == null)
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("label", c.getLabel() == null ? code : c.getLabel());
            ValueFormat f = ValueFormat.of(c.getValueFormat());
            boolean noValue = c.getValue() == null && c.getNoValueTitle() != null;
            row.put("value", formatValue(c.getValue(), f, c.getDecimals(), ctx.currencySymbol));
            String change = noValue ? c.getNoValueTitle() : formatChange(c, f);
            row.put("change", change);
            row.put("trend", noValue ? "flat" : trend(c, f));
            b.rows.add(row);
            String prev = noValue ? DASH : formatValue(c.getPrevValue(), f, c.getDecimals(), ctx.currencySymbol);
            b.facts.add(row.get("label") + "｜" + row.get("value") + "｜" + prev + "｜" + change + "｜" + directionText(c.getDirection())
                + "｜" + alertText(c.getAlert()));
            if (!noValue)
            {
                b.changes.add(new Change(code, String.valueOf(row.get("label")), change, c.getDeltaPct(), c.getDeltaPt(), c.getDirection()));
            }
        }
        b.card = card(b, ctx.overviewUrl);
        return b;
    }

    /**
     * 加上 AI 摘要后重新生成卡片。
     *
     * @param summary 通过校验的摘要；null 表示没有（note 说明原因，只在预览里显示）
     */
    public void applyAi(String summary, String model, String modelLabel, String note)
    {
        this.aiRequested = true;
        this.aiSummary = summary == null || summary.trim().isEmpty() ? null : summary.trim();
        this.aiModel = model;
        this.aiModelLabel = modelLabel;
        this.aiNote = this.aiSummary == null ? note : null;
        this.card = card(this, overviewUrl);
    }

    /**
     * 加上数据异动分析后重新生成卡片。
     *
     * @param analysis 通过校验的分析；null 表示没有（note 说明原因，只在预览里显示）
     */
    public void applyAnomaly(String analysis, String modelLabel, String note)
    {
        this.anomalyRequested = true;
        this.anomaly = analysis == null || analysis.trim().isEmpty() ? null : analysis.trim();
        this.aiModelLabel = this.aiModelLabel == null ? modelLabel : this.aiModelLabel;
        this.anomalyNote = this.anomaly == null ? note : null;
        this.card = card(this, overviewUrl);
    }

    /**
     * 加上运营总览截图后重新生成卡片。
     *
     * @param imageKey Lark 的 image_key；null 表示没有（预览不上传、或失败）
     * @param png      截图（预览用），可为 null
     * @param error    失败原因；null 表示成功
     */
    public void applySnapshot(String imageKey, byte[] png, String url, String error)
    {
        this.snapshotRequested = true;
        this.snapshotKey = imageKey;
        this.snapshotPng = png;
        this.snapshotUrl = url;
        this.snapshotError = error;
        this.card = card(this, overviewUrl);
    }

    public String getSnapshotKey() { return snapshotKey; }

    public String getSnapshotError() { return snapshotError; }

    private static java.time.LocalDateTime parseSlot(String text)
    {
        try
        {
            return text == null ? null : java.time.LocalDateTime.parse(text.trim(), SLOT);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    static String directionText(String direction)
    {
        String d = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        switch (d)
        {
            case "up":
                return "越高越好";
            case "down":
                return "越低越好";
            case "range":
                return "區間";
            default:
                return "無方向";
        }
    }

    /** 监控状态：只有注意 / 严重时写「嚴重：低於嚴重線 90.00%（近 2 小時 83.60–84.20%）」，其余为破折号 */
    static String alertText(com.fivetech.dashboard.domain.vo.MetricAlertVO a)
    {
        if (a == null || a.getLevel() == null)
        {
            return DASH;
        }
        String lv = a.getLevel().trim().toUpperCase(Locale.ROOT);
        String name = "CRIT".equals(lv) ? "嚴重" : "WARN".equals(lv) ? "注意" : null;
        if (name == null)
        {
            return DASH;
        }
        StringBuilder sb = new StringBuilder(name);
        if (a.getReasonLine() != null && !a.getReasonLine().isEmpty())
        {
            sb.append("：").append(a.getReasonLine());
        }
        if (a.getReasonValue() != null && !a.getReasonValue().isEmpty())
        {
            sb.append("（").append(a.getReasonValue()).append("）");
        }
        return sb.toString();
    }

    // ===================== 卡片 =====================

    private static Map<String, Object> card(SubscriptionBrief b, String url)
    {
        List<Map<String, Object>> elements = new ArrayList<>();
        if (b.description != null)
        {
            elements.add(div(md(escape(b.description))));
        }
        elements.add(fields(List.of(
            "**統計週期**\n" + escape(b.period),
            "**對比週期**\n" + escape(b.compare))));
        elements.add(Map.of("tag", "hr"));
        if (b.aiSummary != null)
        {
            elements.add(div(md("**AI 摘要**\n" + escape(b.aiSummary))));
            Map<String, Object> aiText = new LinkedHashMap<>();
            aiText.put("tag", "plain_text");
            aiText.put("content", "由 " + nvl(b.aiModelLabel) + " 根據上方數據生成，僅供參考");
            Map<String, Object> aiNoteEl = new LinkedHashMap<>();
            aiNoteEl.put("tag", "note");
            aiNoteEl.put("elements", List.of(aiText));
            elements.add(aiNoteEl);
            elements.add(Map.of("tag", "hr"));
        }
        if (b.anomaly != null)
        {
            elements.add(div(md("**數據異動分析**\n" + escape(b.anomaly))));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tag", "plain_text");
            t.put("content", "由 " + nvl(b.aiModelLabel) + " 結合指標變化與告警事件生成，僅供參考");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("tag", "note");
            n.put("elements", List.of(t));
            elements.add(n);
            elements.add(Map.of("tag", "hr"));
        }
        if (b.rows.isEmpty())
        {
            elements.add(div(md("沒有可顯示的指標（指標已在本站隱藏或下線）")));
        }
        List<String> buf = new ArrayList<>();
        for (Map<String, Object> r : b.rows)
        {
            buf.add("**" + escape(String.valueOf(r.get("label"))) + "**\n" + escape(String.valueOf(r.get("value")))
                + "　" + colored(String.valueOf(r.get("change")), String.valueOf(r.get("trend"))));
            if (buf.size() == FIELDS_PER_DIV)
            {
                elements.add(fields(buf));
                buf = new ArrayList<>();
            }
        }
        if (!buf.isEmpty())
        {
            elements.add(fields(buf));
        }
        if (b.snapshotKey != null)
        {
            Map<String, Object> alt = new LinkedHashMap<>();
            alt.put("tag", "plain_text");
            alt.put("content", "運營總覽截圖");
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("tag", "img");
            img.put("img_key", b.snapshotKey);
            img.put("alt", alt);
            img.put("mode", "fit_horizontal");
            img.put("preview", true);
            elements.add(Map.of("tag", "hr"));
            elements.add(img);
        }
        else if (b.snapshotRequested && b.snapshotError != null)
        {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tag", "plain_text");
            t.put("content", "運營總覽截圖生成失敗，請在 Yata 中查看");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("tag", "note");
            n.put("elements", List.of(t));
            elements.add(n);
        }
        Map<String, Object> noteText = new LinkedHashMap<>();
        noteText.put("tag", "plain_text");
        noteText.put("content", b.footer);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("tag", "note");
        note.put("elements", List.of(noteText));
        elements.add(note);
        if (url != null && !url.trim().isEmpty())
        {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("tag", "plain_text");
            text.put("content", "在 Yata 中查看");
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("tag", "button");
            button.put("text", text);
            button.put("type", "primary");
            button.put("url", url.trim());
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("tag", "action");
            action.put("actions", List.of(button));
            elements.add(action);
        }

        Map<String, Object> title = new LinkedHashMap<>();
        title.put("tag", "plain_text");
        title.put("content", b.title);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", b.title.startsWith("【測試】") ? "grey" : "blue");
        header.put("title", title);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("wide_screen_mode", true);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", config);
        card.put("header", header);
        card.put("elements", elements);
        return card;
    }

    private static Map<String, Object> md(String content)
    {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("tag", "lark_md");
        text.put("content", content);
        return text;
    }

    private static Map<String, Object> div(Map<String, Object> text)
    {
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");
        div.put("text", text);
        return div;
    }

    private static Map<String, Object> fields(List<String> contents)
    {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String c : contents)
        {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("is_short", true);
            f.put("text", md(c));
            fields.add(f);
        }
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");
        div.put("fields", fields);
        return div;
    }

    private static String colored(String text, String trend)
    {
        String color = "good".equals(trend) ? "green" : "bad".equals(trend) ? "red" : "grey";
        return "<font color='" + color + "'>" + escape(text) + "</font>";
    }

    /** lark_md 的特殊字符转义，避免名称、说明里的 * _ ~ ` [ ] < > 被当成格式 */
    static String escape(String s)
    {
        if (s == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray())
        {
            switch (c)
            {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '*':
                case '_':
                case '~':
                case '`':
                case '[':
                case ']':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // ===================== 数值 =====================

    /** 数值：金额用站点币种符号，其他沿用 ValueFormat 的后缀（% / min / ×）；不缩写；空值为破折号 */
    static String formatValue(BigDecimal value, ValueFormat f, Integer decimals, String currencySymbol)
    {
        if (value == null)
        {
            return DASH;
        }
        int dp = decimals != null ? decimals : f.defaultDecimals();
        boolean negative = value.signum() < 0;
        String body = group(value.abs().setScale(dp, RoundingMode.HALF_UP), dp);
        if (f == ValueFormat.MONEY)
        {
            return (negative ? "−" : "") + nvl(currencySymbol) + body;
        }
        return (negative ? "−" : "") + f.prefix() + body + f.suffix();
    }

    /** 变化：百分比类用百分点（deltaPt），其余用相对变化率（deltaPct）；没有对比值为破折号 */
    static String formatChange(MetricCardVO c, ValueFormat f)
    {
        BigDecimal d = f.deltaInPoints() ? c.getDeltaPt() : c.getDeltaPct();
        if (d == null)
        {
            return DASH;
        }
        String sign = d.signum() < 0 ? "−" : d.signum() > 0 ? "+" : "±";
        return sign + d.abs().stripTrailingZeros().toPlainString() + (f.deltaInPoints() ? "pt" : "%");
    }

    /** good / bad / flat：按指标方向判断变化是好是坏 */
    static String trend(MetricCardVO c, ValueFormat f)
    {
        BigDecimal d = f.deltaInPoints() ? c.getDeltaPt() : c.getDeltaPct();
        if (d == null || d.signum() == 0)
        {
            return "flat";
        }
        String dir = c.getDirection() == null ? "" : c.getDirection().trim().toLowerCase(Locale.ROOT);
        if ("up".equals(dir))
        {
            return d.signum() > 0 ? "good" : "bad";
        }
        if ("down".equals(dir))
        {
            return d.signum() < 0 ? "good" : "bad";
        }
        return "flat";
    }

    private static String group(BigDecimal v, int decimals)
    {
        StringBuilder p = new StringBuilder("#,##0");
        if (decimals > 0)
        {
            p.append('.');
            for (int i = 0; i < decimals; i++)
            {
                p.append('0');
            }
        }
        return new DecimalFormat(p.toString(), DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    // ===================== 时间 =====================

    /**
     * 区间文字（左闭右开）。小时粒度：{@code 10-31 00:00 – 11-01 00:00}；日粒度写到最后一天：{@code 10-25 – 10-31}，
     * 只有一天时写 {@code 10-31}。
     */
    static String span(String from, String to, Granularity g)
    {
        LocalDateTime f = parse(from);
        LocalDateTime t = parse(to);
        if (f == null || t == null)
        {
            return nvl(from) + " – " + nvl(to);
        }
        if (g != Granularity.HOUR && f.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
            && t.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
        {
            LocalDateTime last = t.minusDays(1);
            return last.toLocalDate().equals(f.toLocalDate()) ? f.format(MD) : f.format(MD) + " – " + last.format(MD);
        }
        return f.format(MD_HM) + " – " + t.format(MD_HM);
    }

    private static String shortTime(String s)
    {
        LocalDateTime t = parse(s);
        return t == null ? s : t.format(MD_HM);
    }

    private static LocalDateTime parse(String s)
    {
        if (s == null)
        {
            return null;
        }
        try
        {
            return LocalDateTime.parse(s.trim(), SLOT);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String nvl(String s)
    {
        return s == null ? "" : s;
    }

    // ===================== 输出 =====================

    public String getTitle() { return title; }

    public String getPeriod() { return period; }

    public List<Map<String, Object>> getRows() { return rows; }

    public Map<String, Object> getCard() { return card; }

    public List<String> getFacts() { return facts; }

    public String getCompare() { return compare; }

    public String getSiteName() { return siteName; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String getAiSummary() { return aiSummary; }

    public String getAnomaly() { return anomaly; }

    public List<Change> getChanges() { return changes; }

    public java.time.LocalDateTime getWindowFrom() { return windowFrom; }

    public java.time.LocalDateTime getWindowTo() { return windowTo; }

    /** 给管理后台预览用：标题、说明、周期、对比、资料截至、指标行、底注 */
    public Map<String, Object> toPreview()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("description", description);
        m.put("period", period);
        m.put("compare", compare);
        m.put("asOf", asOf);
        m.put("rows", rows);
        m.put("footer", footer);
        if (aiRequested)
        {
            Map<String, Object> ai = new LinkedHashMap<>();
            ai.put("model", aiModel);
            ai.put("modelLabel", aiModelLabel);
            ai.put("summary", aiSummary);
            ai.put("note", aiNote);
            m.put("ai", ai);
        }
        if (anomalyRequested)
        {
            Map<String, Object> an = new LinkedHashMap<>();
            an.put("analysis", anomaly);
            an.put("note", anomalyNote);
            m.put("anomaly", an);
        }
        if (snapshotRequested)
        {
            Map<String, Object> sn = new LinkedHashMap<>();
            sn.put("image", snapshotPng == null ? null : "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(snapshotPng));
            sn.put("url", snapshotUrl);
            sn.put("error", snapshotError);
            m.put("snapshot", sn);
        }
        return m;
    }
}
