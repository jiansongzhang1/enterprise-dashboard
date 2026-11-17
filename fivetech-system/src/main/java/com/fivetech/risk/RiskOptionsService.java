package com.fivetech.risk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fivetech.configcenter.domain.ConfigItem;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.promo.budget.PromoChannelDictionary;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.catalog.RiskColumn;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.data.RiskDataSource;

/**
 * 风控分析的筛选项与字典（GET /dashboard/risk/options）、榜单条件（GET / POST /dashboard/risk/rank/thresholds）。
 * 全部按当前站点合并配置中心与指标管理，不写在代码里。
 *
 * @author fivetech
 */
@Service
public class RiskOptionsService
{
    private final RiskCatalog catalog;

    private final RiskConfigService config;

    private final RiskDataSource source;

    private final PromoChannelDictionary channels;

    private final DashboardProperties dashboard;

    public RiskOptionsService(RiskCatalog catalog, RiskConfigService config, RiskDataSource source,
        PromoChannelDictionary channels, DashboardProperties dashboard)
    {
        this.catalog = catalog;
        this.config = config;
        this.source = source;
        this.channels = channels;
        this.dashboard = dashboard;
    }

    public Map<String, Object> options()
    {
        String site = dashboard.currentSite();
        RiskBoards cfg = config.boards(site);
        Map<String, Object> dict = config.gameDict(site);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platforms", dict.get("platforms"));
        out.put("games", dict.get("games"));
        List<Map<String, Object>> promoters = new ArrayList<>();
        for (PromoChannelDictionary.Promoter p : channels.promotersOf(site))
        {
            List<Map<String, Object>> chs = new ArrayList<>();
            for (PromoChannelDictionary.Channel c : p.getChannels())
            {
                chs.add(Map.of("id", c.getId(), "name", c.getName()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("channels", chs);
            promoters.add(m);
        }
        out.put("promoters", promoters);
        List<Map<String, Object>> boards = new ArrayList<>();
        for (RiskBoards.Board b : cfg.boards)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", b.code);
            m.put("name", b.name);
            m.put("kind", b.kind);
            // 下拉与编辑卡片的分组：USER 按用戶 / ORDER 按訂單 / CHANNEL 按渠道（我的榜单为 MY，见 /my-boards）
            m.put("group", b.group());
            m.put("rankCol", b.rankCol);
            RiskColumn rc = b.rankCol == null ? null : catalog.rank(b.rankCol);
            m.put("rankDir", b.rankDir != null ? b.rankDir : b.isChannel() ? "desc" : rc == null ? null : rc.getRiskDir());
            m.put("rankFixed", b.isOrder());
            m.put("shareMode", b.shareMode);
            m.put("distCol", b.distCol);
            m.put("defaultCard", cfg.defaultCards.contains(b.code));
            m.put("card", b.card);
            m.put("cols", b.cols);
            m.put("entry", entryText(b.entry, cfg));
            // 名单筛选面板按这张榜预填的条件（FY-67 R1、R3：「> 0」预填最小值 0.01）
            m.put("defaultConditions", defaultConditions(b, cfg));
            List<Map<String, Object>> stats = new ArrayList<>();
            b.stats.forEach(x -> stats.add(Map.of("code", x.code, "label", x.label)));
            m.put("stats", stats);
            if (b.isOrder())
            {
                m.put("filters", b.filters);
                m.put("colOptions", b.colOptions);
            }
            if (b.isChannel())
            {
                List<Map<String, Object>> ccols = new ArrayList<>();
                for (RiskBoards.ChannelCol c : b.channelCols)
                {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("code", c.code);
                    x.put("name", c.name);
                    x.put("group", c.group);
                    x.put("valueFormat", c.valueFormat);
                    x.put("rankable", c.rank);
                    x.put("condition", c.condition);
                    x.put("fixed", c.fixed);
                    x.put("remark", c.remark);
                    ccols.add(x);
                }
                m.put("channelCols", ccols);
                m.put("drillCols", b.drillCols);
                m.put("drillRankCol", b.drillRankCol);
                m.put("acquisitionPending", !source.available(RiskDataSource.Kind.CHANNEL_ACQ));
            }
            boards.add(m);
        }
        out.put("boards", boards);
        List<Map<String, Object>> types = new ArrayList<>();
        for (RiskBoards.RiskType t : cfg.riskTypes)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.code);
            m.put("name", t.name);
            m.put("board", t.board);
            RiskBoards.Board b = cfg.board(t.board);
            List<String> text = b == null ? new ArrayList<>() : new ArrayList<>(entryText(b.entry, cfg));
            text.addAll(entryText(t.and, cfg));
            // 判定条件（风险类型弹窗每类写的条件）
            m.put("condition", String.join(" 且 ", text));
            types.add(m);
        }
        out.put("riskTypes", types);
        List<Map<String, Object>> conds = new ArrayList<>();
        List<Map<String, Object>> cols = new ArrayList<>();
        for (RiskColumn c : catalog.rank().values())
        {
            if (c.getCondition() != null)
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", c.getCode());
                m.put("name", c.getName());
                m.put("kind", c.getCondition());
                m.put("valueFormat", c.getValueFormat());
                m.put("common", c.isCommon());
                conds.add(m);
            }
            if (c.isColumn())
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", c.getCode());
                m.put("name", c.getName());
                m.put("group", c.getGroup());
                m.put("valueFormat", c.getValueFormat());
                m.put("kind", c.getKind());
                m.put("rankable", c.isRankable());
                m.put("riskDir", c.getRiskDir());
                List<String> defaultFor = new ArrayList<>();
                cfg.boards.forEach(b -> {
                    if (b.cols.contains(c.getCode()))
                    {
                        defaultFor.add(b.code);
                    }
                });
                m.put("defaultFor", defaultFor);
                m.put("remark", c.getRemark());
                cols.add(m);
            }
        }
        if (!cfg.riskTypes.isEmpty())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", com.fivetech.risk.rank.RiskRankService.RISK_TYPE);
            m.put("name", "風險類型");
            m.put("kind", "SELECT");
            m.put("common", true);
            m.put("options", types);
            conds.add(m);
        }
        out.put("conditionFields", conds);
        out.put("columns", cols);
        List<Map<String, Object>> gcols = new ArrayList<>();
        for (RiskColumn c : catalog.game().values())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.getCode());
            m.put("name", c.getName());
            m.put("group", c.getGroup());
            m.put("valueFormat", c.getValueFormat());
            m.put("standard", c.isStandard());
            gcols.add(m);
        }
        out.put("gameColumns", gcols);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("bands", cfg.bands);
        defaults.put("myBoardLimit", cfg.myBoardLimit);
        defaults.put("defaultCards", cfg.defaultCards);
        defaults.put("channelRiskTypes", cfg.channelGlobalOrDefault());
        defaults.put("channelRiskSystemDefault", cfg.known(cfg.channelRiskDefault));
        RiskBoards.Board big = null;
        for (RiskBoards.Board b : cfg.boards)
        {
            if (b.isOrder())
            {
                big = b;
            }
        }
        if (big != null && big.threshold != null && big.threshold.ref != null)
        {
            BigDecimal[] lim = cfg.limits.getOrDefault(big.threshold.ref, new BigDecimal[2]);
            defaults.put("bigThresholdRange", new BigDecimal[] { lim[0], lim[1] });
            defaults.put("bigThreshold", cfg.thresholds.get(big.threshold.ref));
        }
        out.put("defaults", defaults);
        out.put("currency", dashboard.currentCurrency());
        out.put("currencySymbol", dashboard.siteCurrencySymbol(site));
        out.put("timezone", dashboard.currentTimezone());
        Map<String, Object> status = new LinkedHashMap<>();
        for (RiskDataSource.Kind k : RiskDataSource.Kind.values())
        {
            status.put(k.name(), source.available(k));
        }
        out.put("dataStatus", status);
        return out;
    }

    /** 入榜条件换成名单条件（筛选面板预填）：> x → 最小 x + 0.01，≥ x → 最小 x，≤ x → 最大 x，< x → 最大 x - 0.01 */
    List<Map<String, Object>> defaultConditions(RiskBoards.Board b, RiskBoards cfg)
    {
        Map<String, Map<String, Object>> byField = new LinkedHashMap<>();
        if (b.isOrder() && b.threshold != null)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", "amount");
            m.put("min", b.threshold.resolve(cfg.thresholds, null));
            m.put("max", null);
            byField.put("amount", m);
        }
        for (RiskBoards.Cond c : b.entry)
        {
            if (c.any != null || c.field == null)
            {
                continue;
            }
            BigDecimal v = c.resolve(cfg.thresholds, null);
            Map<String, Object> m = byField.computeIfAbsent(c.field, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("field", k);
                x.put("min", null);
                x.put("max", null);
                return x;
            });
            if (v == null)
            {
                continue;
            }
            switch (c.op)
            {
                case "GT" -> m.put("min", v.add(new BigDecimal("0.01")));
                case "GE" -> m.put("min", v);
                case "LE" -> m.put("max", v);
                default -> m.put("max", v.subtract(new BigDecimal("0.01")));
            }
        }
        return new ArrayList<>(byField.values());
    }

    /** 入榜条件的文字（如「玩家輸贏 > 0」「註冊天數 ≤ 7」），条件行默认显示用 */
    List<String> entryText(List<RiskBoards.Cond> conds, RiskBoards cfg)
    {
        List<String> out = new ArrayList<>();
        for (RiskBoards.Cond c : conds)
        {
            if (c.any != null)
            {
                List<String> parts = entryText(c.any, cfg);
                out.add(String.join(" 或 ", parts));
                continue;
            }
            RiskColumn col = catalog.rank(c.field);
            BigDecimal v = c.resolve(cfg.thresholds, null);
            String op = switch (c.op)
            {
                case "GT" -> " > ";
                case "GE" -> " ≥ ";
                case "LE" -> " ≤ ";
                default -> " < ";
            };
            out.add((col == null ? c.field : col.getName()) + op + (v == null ? "—" : v.stripTrailingZeros().toPlainString()));
        }
        return out;
    }

    /** 榜单条件：每项的值、上下限、名称，最后修改人与时间，当前用户能不能改 */
    public Map<String, Object> thresholds(boolean editable)
    {
        String site = dashboard.currentSite();
        RiskBoards cfg = config.boards(site);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        cfg.thresholds.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("name", cfg.thresholdNames.getOrDefault(k, k));
            m.put("value", v);
            BigDecimal[] lim = cfg.limits.getOrDefault(k, new BigDecimal[2]);
            m.put("min", lim[0]);
            m.put("max", lim[1]);
            items.add(m);
            out.put(k, v);
        });
        out.put("items", items);
        // 渠道风险榜的全局默认风险类型（「榜單條件」弹窗的「渠道風險榜」分组）
        out.put("channelRiskTypes", cfg.channelGlobalOrDefault());
        List<Map<String, Object>> rts = new ArrayList<>();
        cfg.riskTypes.forEach(t -> rts.add(Map.of("code", t.code, "name", t.name)));
        out.put("riskTypeOptions", rts);
        out.put("editable", editable);
        ConfigItem it = config.thresholdItem(site);
        out.put("updateBy", it == null ? null : it.getUpdateBy() != null ? it.getUpdateBy() : it.getCreateBy());
        out.put("updateTime", it == null ? null : it.getUpdateTime() != null ? it.getUpdateTime() : it.getCreateTime());
        return out;
    }

    public void saveThresholds(Map<String, Object> body, String operator)
    {
        config.saveThresholds(dashboard.currentSite(), body, operator);
    }

    // ------------------------------------------------------------------ 渠道风险榜 · 个人风险类型（FY-71 R2）

    private com.fivetech.dashboard.view.UserViewService views;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViews(com.fivetech.dashboard.view.UserViewService views)
    {
        this.views = views;
    }

    /** 生效的风险类型与来源（PERSONAL 个人 / GLOBAL 全局默认 / SYSTEM 系统默认），以及全局、系统默认和可选项 */
    public Map<String, Object> riskTypes()
    {
        RiskBoards cfg = config.boards(dashboard.currentSite());
        List<String> personal = views == null ? null : views.itemsOf(RiskViewItemSource.RANK_RISK_TYPES);
        Object[] eff = cfg.channelRiskTypes(personal);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selected", eff[0]);
        out.put("source", eff[1]);
        out.put("globalDefault", cfg.channelGlobalOrDefault());
        out.put("systemDefault", cfg.known(cfg.channelRiskDefault));
        List<Map<String, Object>> opts = new ArrayList<>();
        cfg.riskTypes.forEach(t -> opts.add(Map.of("code", t.code, "name", t.name)));
        out.put("options", opts);
        return out;
    }

    /**
     * 保存个人风险类型：至少 1 类、只认配置里的风险类型；和全局默认一样时视为跟随全局（清掉个人设置，之后全局改了跟着变）。
     * types 为空数组 = 「恢復默認」。
     */
    public Map<String, Object> saveRiskTypes(Map<String, Object> body)
    {
        if (views == null)
        {
            throw RiskException.field("types", "個人設置暫不可用");
        }
        RiskBoards cfg = config.boards(dashboard.currentSite());
        Object raw = body == null ? null : body.get("types");
        List<String> types = new ArrayList<>();
        if (raw instanceof List<?> l)
        {
            l.forEach(x -> {
                if (x != null && !types.contains(String.valueOf(x)))
                {
                    types.add(String.valueOf(x));
                }
            });
        }
        else
        {
            throw RiskException.field("types", "至少勾選 1 類風險");
        }
        List<String> known = cfg.known(types);
        if (known.size() != types.size())
        {
            throw RiskException.field("types", "風險類型不存在：" + types.stream().filter(x -> !known.contains(x)).toList());
        }
        if (!types.isEmpty() && known.isEmpty())
        {
            throw RiskException.field("types", "至少勾選 1 類風險");
        }
        List<String> global = cfg.channelGlobalOrDefault();
        if (known.isEmpty() || (known.size() == global.size() && known.containsAll(global)))
        {
            views.reset(RiskViewItemSource.RANK_RISK_TYPES);
        }
        else
        {
            com.fivetech.dashboard.view.domain.SaveViewRequest req = new com.fivetech.dashboard.view.domain.SaveViewRequest();
            req.setMode(com.fivetech.dashboard.view.domain.UserView.CUSTOM);
            req.setItems(known);
            views.save(RiskViewItemSource.RANK_RISK_TYPES, req);
        }
        return riskTypes();
    }
}
