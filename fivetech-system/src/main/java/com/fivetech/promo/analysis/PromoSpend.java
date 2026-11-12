package com.fivetech.promo.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.promo.budget.BudgetDataReader;
import com.fivetech.promo.budget.BudgetImportProperties;
import com.fivetech.promo.budget.BudgetKind;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 推广分析读消耗 / 预算：UDS {@code import_yata_promo_cost / budget}（运营按「日 × 推广渠道」导入，站点默认币种）。
 * 概览卡、渠道表现、投放日报共用；读取开关与「预算与消耗」页面相同（promo.budget-import.read-enabled）。
 *
 * <p>消耗、预算带推广商 / 推广渠道 ID，所以按推广商、推广渠道筛选与拆分都能直接算；不按终端拆（那些格子 NA）。
 * 没有导入记录返回 null（没录入），不当成 0。</p>
 *
 * @author fivetech
 */
@Component
public class PromoSpend
{
    private static final Logger log = LoggerFactory.getLogger(PromoSpend.class);

    /** 直接取导入数据的指标：消耗、预算 */
    public static final Set<String> CODES = Set.of("spend", "budget");

    /** 读取失败时卡片 / 单元格的说明 */
    public static final String FAILED_NOTE = "消耗 / 預算暫時無法取得（數據平台讀取失敗）";

    private final BudgetDataReader reader;

    private final BudgetImportProperties props;

    public PromoSpend(BudgetDataReader reader, BudgetImportProperties props)
    {
        this.reader = reader;
        this.props = props;
    }

    public boolean isEnabled()
    {
        return props == null || props.isReadEnabled();
    }

    /**
     * 读 [from, to]（两端都含）的消耗与预算。关闭读取返回 null；数据平台出错不抛异常，返回 {@link Cube#failed()}，
     * 由调用方把相关指标标成「暫時無法取得」，其他指标照常。
     */
    public Cube load(LocalDate from, LocalDate to)
    {
        if (!isEnabled() || reader == null || from == null || to == null || to.isBefore(from))
        {
            return null;
        }
        try
        {
            return new Cube(reader.rows(BudgetKind.SPEND, from, to), reader.rows(BudgetKind.BUDGET, from, to));
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 消耗 / 预算读取失败 {}~{}：{}", from, to, e.getMessage());
            return Cube.failed();
        }
    }

    static BudgetKind kind(String code)
    {
        return "budget".equals(code) ? BudgetKind.BUDGET : BudgetKind.SPEND;
    }

    /** 所选推广商 / 推广渠道范围；全部（含自然量）时不过滤 */
    static Predicate<BudgetDataReader.Row> inScope(PromoScope scope)
    {
        if (scope == null || scope.isAll())
        {
            return r -> true;
        }
        Set<String> ids = new LinkedHashSet<>();
        scope.getChannels().forEach(c -> ids.add(ConfigCodes.idKey(c.getId())));
        return r -> ids.contains(ConfigCodes.idKey(r.getChannelId()));
    }

    /** 渠道表现某一行：路径上的推广商 / 推广渠道（为 null 不限） */
    static Predicate<BudgetDataReader.Row> ofPath(String agentKey, String channelKey)
    {
        String a = agentKey == null ? null : ConfigCodes.idKey(agentKey);
        String c = channelKey == null ? null : ConfigCodes.idKey(channelKey);
        return r -> (a == null || a.equals(ConfigCodes.idKey(r.getPromoterId())))
            && (c == null || c.equals(ConfigCodes.idKey(r.getChannelId())));
    }

    /** 一次读到的消耗与预算 */
    public static final class Cube
    {
        private final List<BudgetDataReader.Row> spend;

        private final List<BudgetDataReader.Row> budget;

        private final boolean failed;

        public Cube(List<BudgetDataReader.Row> spend, List<BudgetDataReader.Row> budget)
        {
            this(spend, budget, false);
        }

        private Cube(List<BudgetDataReader.Row> spend, List<BudgetDataReader.Row> budget, boolean failed)
        {
            this.spend = spend == null ? List.of() : spend;
            this.budget = budget == null ? List.of() : budget;
            this.failed = failed;
        }

        public static Cube failed()
        {
            return new Cube(List.of(), List.of(), true);
        }

        public boolean isFailed()
        {
            return failed;
        }

        List<BudgetDataReader.Row> rows(BudgetKind kind)
        {
            return kind == BudgetKind.BUDGET ? budget : spend;
        }

        private static boolean in(BudgetDataReader.Row r, LocalDate from, LocalDate to)
        {
            return r.getDate() != null && !r.getDate().isBefore(from) && !r.getDate().isAfter(to);
        }

        /** [from, to] 内满足条件的金额合计；一行都没有返回 null（没录入，不是 0） */
        public BigDecimal sum(BudgetKind kind, LocalDate from, LocalDate to, Predicate<BudgetDataReader.Row> filter)
        {
            BigDecimal total = null;
            for (BudgetDataReader.Row r : rows(kind))
            {
                if (in(r, from, to) && filter.test(r) && r.getAmount() != null)
                {
                    total = total == null ? r.getAmount() : total.add(r.getAmount());
                }
            }
            return total;
        }

        /** 按「日 × 推广渠道」的金额（同一格多行相加），算有效格子用 */
        public Map<PromoCost.Cell, BigDecimal> cells(BudgetKind kind, LocalDate from, LocalDate to,
            Predicate<BudgetDataReader.Row> filter)
        {
            Map<PromoCost.Cell, BigDecimal> out = new LinkedHashMap<>();
            for (BudgetDataReader.Row r : rows(kind))
            {
                if (in(r, from, to) && filter.test(r) && r.getAmount() != null)
                {
                    out.merge(new PromoCost.Cell(r.getDate(), ConfigCodes.idKey(r.getChannelId())), r.getAmount(), BigDecimal::add);
                }
            }
            return out;
        }

        /** [from, to] 内有消耗或预算记录的推广渠道（idKey） */
        public Set<String> channelKeys(LocalDate from, LocalDate to, Predicate<BudgetDataReader.Row> filter)
        {
            Set<String> out = new LinkedHashSet<>();
            for (List<BudgetDataReader.Row> list : List.of(spend, budget))
            {
                for (BudgetDataReader.Row r : list)
                {
                    if (in(r, from, to) && filter.test(r))
                    {
                        out.add(ConfigCodes.idKey(r.getChannelId()));
                    }
                }
            }
            return out;
        }

        /** 投放日报：按字典顺序列出有记录的推广渠道 */
        List<PromoChannelDictionary.Channel> listed(PromoScope scope, LocalDate from, LocalDate to)
        {
            Set<String> keys = channelKeys(from, to, inScope(scope));
            List<PromoChannelDictionary.Channel> out = new ArrayList<>();
            for (PromoChannelDictionary.Promoter p : scope.promotersInScope())
            {
                for (PromoChannelDictionary.Channel c : scope.channelsOf(p))
                {
                    if (keys.contains(ConfigCodes.idKey(c.getId())))
                    {
                        out.add(c);
                    }
                }
            }
            return out;
        }
    }
}
