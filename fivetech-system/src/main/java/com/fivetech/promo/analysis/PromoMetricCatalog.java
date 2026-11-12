package com.fivetech.promo.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.mapper.DashboardConfigMapper;
import com.fivetech.dashboard.mapper.MetricDictMapper;

/**
 * 推广分析的指标目录。
 *
 * <ul>
 *   <li><b>概览指标卡</b>（16 个）：名称、分组、格式、方向、更新频率、顺序、默认显示、可歸因来自库
 *       （dashboard_metric_card 里 promo 前缀的行 + dashboard_page_metric 的 PROMO 页面，每次读库、不缓存，
 *       指标管理改了立即生效）；接口编码（spend、reg …）与库里编码（promoSpend、promoReg …）的对应、
 *       有没有小时数据、能否按终端拆这些取数口径写在代码里（{@link #OVERVIEW}）。</li>
 *   <li><b>渠道表现 / 投放日报的列</b>：只在代码里（{@link #COLUMNS}），没有页面配置。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class PromoMetricCatalog
{
    private static final Logger log = LoggerFactory.getLogger(PromoMetricCatalog.class);

    /** 概览一次最多显示的卡片数 */
    public static final int MAX_CARDS = 10;

    /** 概览指标的取数口径（代码里的部分） */
    public static final class Spec
    {
        final String code;
        final String dbCode;
        /** 有没有小时数据；false 的按小时看时 seriesUnavailable = DAILY_ONLY */
        final boolean hourly;
        /** 能否按终端拆；false 的在选了终端时 state = NA */
        final boolean byTerminal;
        /** 选了终端时不适用的说明（繁体） */
        final String terminalNote;
        /** 只有 Android 有值（下载点击、激活）：选了别的终端时 NA */
        final boolean androidOnly;
        /** 日粒度时趋势点能否下钻名单 */
        final boolean drillable;
        /** 依赖消耗（有效格子）：没有有效格子时 state = NO_SPEND */
        final boolean spendBased;
        /** 口径说明（繁体） */
        final String definition;

        Spec(String code, String dbCode, boolean hourly, boolean byTerminal, String terminalNote, boolean androidOnly,
            boolean drillable, boolean spendBased, String definition)
        {
            this.code = code;
            this.dbCode = dbCode;
            this.hourly = hourly;
            this.byTerminal = byTerminal;
            this.terminalNote = terminalNote;
            this.androidOnly = androidOnly;
            this.drillable = drillable;
            this.spendBased = spendBased;
            this.definition = definition;
        }

        public String getCode() { return code; }
        public String getDbCode() { return dbCode; }
        public boolean isHourly() { return hourly; }
        public boolean isByTerminal() { return byTerminal; }
        public String getTerminalNote() { return terminalNote; }
        public boolean isAndroidOnly() { return androidOnly; }
        public boolean isDrillable() { return drillable; }
        public boolean isSpendBased() { return spendBased; }
        public String getDefinition() { return definition; }
    }

    static final String NOTE_SPEND = "消耗不按終端拆分";

    static final String NOTE_TERMINAL = "不適用於所選終端";

    static final String NOTE_ANDROID = "只有 Android 有值";

    /** 概览 16 个指标，按 FY-46 概览页的顺序 */
    public static final List<Spec> OVERVIEW = List.of(
        new Spec("spend", "promoSpend", false, false, NOTE_SPEND, false, false, false, "所選範圍內運營匯入的消耗合計（全部數據，按日錄入）"),
        new Spec("budget", "promoBudget", false, false, NOTE_SPEND, false, false, false, "所選範圍內運營匯入的預算合計"),
        new Spec("cpa", "promoCpa", false, false, NOTE_SPEND, false, false, true, "有效格子的消耗 ÷ 有效格子的註冊人數（有效格子：已錄入消耗且 ＞ 0 的「日 × 推廣渠道」）"),
        new Spec("ftdCost", "promoFtdCost", false, false, NOTE_SPEND, false, false, true, "有效格子的消耗 ÷ 有效格子的首存人數"),
        new Spec("net", "promoNet", true, true, null, false, false, false, "存款總額 − 提款總額（同運營總覽「存提差」）"),
        new Spec("roi", "promoRoi", false, false, NOTE_SPEND, false, false, true, "有效格子的淨充值 ÷ 有效格子的消耗 − 1"),
        new Spec("lpUV", "promoLpUV", true, false, NOTE_TERMINAL, false, false, false, "落地頁去重訪問人數（玩家按用戶 ID、匿名按匿名標識）"),
        new Spec("hUV", "promoHUV", true, false, NOTE_TERMINAL, false, false, false, "主頁去重訪問人數（含 Web 與 APP 內打開）"),
        new Spec("dl", "promoDl", true, true, NOTE_ANDROID, true, false, false, "點擊下載 APK 的次數"),
        new Spec("act", "promoAct", true, true, NOTE_ANDROID, true, false, false, "Android 安裝後首次啟動的人數"),
        new Spec("reg", "promoReg", true, true, null, false, true, false, "註冊人數，按註冊時的推廣渠道與終端歸屬"),
        new Spec("regR", "promoRegR", true, false, NOTE_TERMINAL, false, false, false, "註冊人數 ÷ 入口 UV（按渠道入口類型取 UV-落地頁或 UV-主頁，分子分母分別相加）"),
        new Spec("ftd", "promoFtd", true, true, null, false, true, false, "首存人數，每人只算一次"),
        new Spec("ftdR", "promoFtdR", true, true, null, false, false, false, "首存人數 ÷ 註冊人數"),
        new Spec("ftdAmt", "promoFtdAmt", true, true, null, false, true, false, "首存金額"),
        new Spec("rd", "promoRd", true, true, null, false, true, false, "首存後再次存款的人數；多天按日累加"));

    private static final Map<String, Spec> BY_CODE = new LinkedHashMap<>();

    private static final Map<String, Spec> BY_DB_CODE = new LinkedHashMap<>();

    static
    {
        for (Spec s : OVERVIEW)
        {
            BY_CODE.put(s.code, s);
            BY_DB_CODE.put(s.dbCode, s);
        }
    }

    /** 渠道表现 / 投放日报的列 */
    public static final class Column
    {
        final String code;
        final String name;
        final String group;
        /** INT / MONEY / PCT */
        final String format;
        /** 依赖消耗或预算（按终端拆时 NA） */
        final boolean spendBased;
        /** 批次指标（ROAS、注册留存）：带 matured，全部未到期为 PENDING */
        final boolean cohort;

        Column(String code, String name, String group, String format, boolean spendBased, boolean cohort)
        {
            this.code = code;
            this.name = name;
            this.group = group;
            this.format = format;
            this.spendBased = spendBased;
            this.cohort = cohort;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getGroup() { return group; }
        public String getFormat() { return format; }
        public boolean isSpendBased() { return spendBased; }
        public boolean isCohort() { return cohort; }
        /** 渠道表现里这一列能否下钻到用户明细（见 {@link #CHANNEL_DRILLABLE}） */
        public boolean isDrillable() { return CHANNEL_DRILLABLE.contains(code); }
    }

    /** 渠道表现支持下钻的列：注册人数、首存人数 */
    public static final java.util.Set<String> CHANNEL_DRILLABLE = java.util.Set.of("reg", "ftd");

    /** 渠道表现可选列（5 组），编码与第 8 节指标清单一致 */
    public static final List<Column> COLUMNS = List.of(
        new Column("lpPV", "PV-落地頁", "traffic", "INT", false, false),
        new Column("lpUV", "UV-落地頁", "traffic", "INT", false, false),
        new Column("hPV", "PV-主頁", "traffic", "INT", false, false),
        new Column("hUV", "UV-主頁", "traffic", "INT", false, false),
        new Column("dl", "下載點擊", "traffic", "INT", false, false),
        new Column("act", "激活", "traffic", "INT", false, false),
        new Column("reg", "註冊人數", "conversion", "INT", false, false),
        new Column("regR", "註冊轉化率", "conversion", "PCT", false, false),
        new Column("ftd", "首存人數", "conversion", "INT", false, false),
        new Column("ftdR", "首存轉化率", "conversion", "PCT", false, false),
        new Column("depR", "存款率", "conversion", "PCT", false, false),
        new Column("ftdAmt", "首存金額", "conversion", "MONEY", false, false),
        new Column("rd", "復存人數", "conversion", "INT", false, false),
        new Column("rdAmt", "復存金額", "conversion", "MONEY", false, false),
        new Column("rdR", "復存率", "conversion", "PCT", false, false),
        new Column("spend", "消耗", "spend", "MONEY", true, false),
        new Column("budget", "預算", "spend", "MONEY", true, false),
        new Column("uvCost", "UV 成本", "spend", "MONEY", true, false),
        new Column("cpa", "CPA", "spend", "MONEY", true, false),
        new Column("ftdCost", "首存成本", "spend", "MONEY", true, false),
        new Column("roi", "D0 ROI", "spend", "PCT", true, false),
        // ROAS 由 UDS 计算好直接给值，后端不用「累计淨充值 ÷ 消耗」自己算；UDS 未提供前为 NO_SOURCE。
        // 仍标 spendBased：分母是按推广渠道导入的消耗，路径上有终端时 NA
        new Column("roas7", "ROAS D7", "retention", "PCT", true, true),
        new Column("roas30", "ROAS D30", "retention", "PCT", true, true),
        new Column("regRet1", "次日註冊留存", "retention", "PCT", false, true),
        new Column("regRet3", "3 日註冊留存", "retention", "PCT", false, true),
        new Column("regRet7", "7 日註冊留存", "retention", "PCT", false, true),
        new Column("regRet15", "15 日註冊留存", "retention", "PCT", false, true),
        new Column("regRet30", "30 日註冊留存", "retention", "PCT", false, true),
        new Column("depU", "存款人數", "value", "INT", false, false),
        new Column("dep", "存款總額", "value", "MONEY", false, false),
        new Column("wdU", "提款人數", "value", "INT", false, false),
        new Column("wd", "提款總額", "value", "MONEY", false, false),
        new Column("net", "淨充值", "value", "MONEY", false, false),
        new Column("arppu", "ARPPU", "value", "MONEY", false, false),
        new Column("betU", "投注人數", "value", "INT", false, false),
        new Column("bet", "投注總額", "value", "MONEY", false, false),
        new Column("vbet", "有效投注", "value", "MONEY", false, false),
        new Column("ggr", "GGR", "value", "MONEY", false, false));

    /** 列分组的显示名 */
    public static final Map<String, String> COLUMN_GROUPS = orderedGroups();

    private static Map<String, String> orderedGroups()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("traffic", "流量");
        m.put("conversion", "轉化");
        m.put("spend", "投放");
        m.put("retention", "留存與回收");
        m.put("value", "價值");
        return m;
    }

    private static final Map<String, Column> COLUMN_BY_CODE = new LinkedHashMap<>();

    static
    {
        COLUMNS.forEach(c -> COLUMN_BY_CODE.put(c.code, c));
    }

    /** 投放日报（FY-57）的 17 个指标列，同序同名 */
    public static final List<String> DAILY_REPORT_COLUMNS = List.of("spend", "uvCost", "cpa", "roi", "lpUV", "hUV", "dl", "act",
        "dep", "wd", "reg", "ftd", "depU", "wdU", "ftdR", "depR", "arppu");

    private final MetricDictMapper dictMapper;

    private final DashboardConfigMapper configMapper;

    public PromoMetricCatalog(MetricDictMapper dictMapper, DashboardConfigMapper configMapper)
    {
        this.dictMapper = dictMapper;
        this.configMapper = configMapper;
    }

    public static Spec spec(String code)
    {
        return code == null ? null : BY_CODE.get(code.trim());
    }

    public static Column column(String code)
    {
        return code == null ? null : COLUMN_BY_CODE.get(code.trim());
    }

    /** 概览指标（库里的配置 + 代码里的口径） */
    public static final class Metric
    {
        private final Spec spec;
        private final MetricDictItem card;
        private final PageMetricConfig page;

        public Metric(Spec spec, MetricDictItem card, PageMetricConfig page)
        {
            this.spec = spec;
            this.card = card;
            this.page = page;
        }

        public Spec getSpec() { return spec; }
        public String getCode() { return spec.code; }
        public String getName() { return card.getMetricName(); }
        public String getNameEn() { return card.getMetricNameEn(); }
        public String getGroup() { return card.getGroupCode(); }
        public String getFormat() { return card.getValueFormat(); }
        public Integer getDecimals() { return card.getValueDecimals(); }
        public String getDirection() { return card.getDirection(); }
        public String getUpdateFrequency() { return card.getUpdateFrequency(); }
        public Integer getSortNo() { return page.getSortNo(); }
        public boolean isDefault() { return page.getIsDefault(); }
        public String getEmphasis() { return page.isCore() ? "CORE" : "NORMAL"; }
        public boolean isAttributable() { return Boolean.TRUE.equals(card.getAttributable()); }
        /** 比率、成本类：各成员的值不能相加 */
        public boolean isRatio() { return "PCT".equalsIgnoreCase(getFormat()) || spec.spendBased; }
    }

    /**
     * 概览可选的指标，按页面顺序；读不到（没执行 release_next ⑤）时返回空列表并记警告。
     */
    public List<Metric> overviewMetrics()
    {
        try
        {
            Map<String, MetricDictItem> cards = new LinkedHashMap<>();
            for (MetricDictItem it : dictMapper.selectAll())
            {
                cards.put(it.getMetricCode(), it);
            }
            List<PageMetricConfig> rows = new ArrayList<>();
            for (PageMetricConfig r : configMapper.selectAllPageMetrics())
            {
                if (DashboardPage.PROMO.name().equals(r.getPageCode()) && r.isEnabled())
                {
                    rows.add(r);
                }
            }
            rows.sort(Comparator.comparing(r -> r.getSortNo() == null ? Integer.MAX_VALUE : r.getSortNo()));
            List<Metric> out = new ArrayList<>();
            for (PageMetricConfig r : rows)
            {
                Spec spec = BY_DB_CODE.get(r.getMetricCode());
                MetricDictItem card = cards.get(r.getMetricCode());
                if (spec == null || card == null)
                {
                    // 页面上挂了代码不认识的指标：没有取数口径，跳过
                    log.warn("[promo] 推广分析页面挂了未登记口径的指标 {}，已跳过", r.getMetricCode());
                    continue;
                }
                out.add(new Metric(spec, card, r));
            }
            return out;
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 读取推广分析指标配置失败（是否已执行 sql/release_next_postgresql.sql ⑤）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 启用的指标分组（编码 → 配置） */
    public Map<String, MetricGroupConfig> groups()
    {
        Map<String, MetricGroupConfig> out = new LinkedHashMap<>();
        try
        {
            configMapper.selectEnabledGroups().forEach(g -> out.put(g.getGroupCode(), g));
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 读取指标分组失败：{}", e.getMessage());
        }
        return out;
    }
}
