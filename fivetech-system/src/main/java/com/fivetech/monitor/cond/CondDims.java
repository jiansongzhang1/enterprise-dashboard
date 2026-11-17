package com.fivetech.monitor.cond;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.configcenter.ConfigCenterService;
import com.fivetech.promo.analysis.PromoTerminalDictionary;
import com.fivetech.promo.budget.PromoChannelDictionary;
import com.fivetech.risk.config.RiskConfigService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 条件范围各维度的可选值与显示名，都来自配置中心，不写死：
 * <ul>
 *   <li>渠道 ch：推广商 › 推广渠道（PROMO_CHANNELS），值为 g:推广商ID / c:推广商ID|渠道ID；</li>
 *   <li>游戏平台 pf、类型 gty、游戏 game：游戏字典（RISK_GAME_DICT，与风控分析共用）；</li>
 *   <li>终端 term：推广分析的终端字典；</li>
 *   <li>支付通道 pay：MONITOR_COND_PAY_CHANNELS。</li>
 * </ul>
 * 某个维度的字典为空时不限制取值（显示名即值）。
 *
 * @author fivetech
 */
@Component
public class CondDims
{
    private static final Logger log = LoggerFactory.getLogger(CondDims.class);

    /** 配置中心：条件规则「支付通道」的选项 [{code, name}] */
    public static final String PAY_CHANNELS = "MONITOR_COND_PAY_CHANNELS";

    public static final String DEFAULT_PAY_CHANNELS = "[ { \"code\": \"upi\", \"name\": \"UPI\" }, { \"code\": \"imps\", \"name\": \"IMPS\" } ]";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final PromoChannelDictionary channels;

    private final RiskConfigService risk;

    private final PromoTerminalDictionary terminals;

    private final ConfigCenterService configs;

    public CondDims(PromoChannelDictionary channels, RiskConfigService risk, PromoTerminalDictionary terminals,
            ConfigCenterService configs)
    {
        this.channels = channels;
        this.risk = risk;
        this.terminals = terminals;
        this.configs = configs;
    }

    /** 一个站点的维度选项：dim → { name, values } ；ch 为树 */
    public Snapshot of(String site)
    {
        Snapshot s = new Snapshot();
        // 渠道
        List<Map<String, Object>> ch = new ArrayList<>();
        for (PromoChannelDictionary.Promoter p : or(safe(() -> channels.promotersOf(site)), List.<PromoChannelDictionary.Promoter>of()))
        {
            String g = "g:" + p.getId();
            s.put("ch", g, p.getName());
            List<Map<String, Object>> children = new ArrayList<>();
            for (PromoChannelDictionary.Channel c : p.getChannels())
            {
                String code = "c:" + p.getId() + "|" + c.getId();
                String name = p.getName() + " › " + c.getName();
                s.put("ch", code, name);
                children.add(option(code, c.getName()));
            }
            Map<String, Object> node = option(g, p.getName());
            node.put("children", children);
            ch.add(node);
        }
        s.values.put("ch", ch);
        // 游戏
        Map<String, Object> dict = or(safe(() -> risk.gameDict(site)), Map.of());
        List<Map<String, Object>> pf = new ArrayList<>();
        List<Map<String, Object>> gty = new ArrayList<>();
        if (dict.get("platforms") instanceof List<?> pl)
        {
            for (Object o : pl)
            {
                if (o instanceof Map<?, ?> m && m.get("code") != null)
                {
                    String code = String.valueOf(m.get("code"));
                    s.put("pf", code, str(m.get("name"), code));
                    pf.add(option(code, str(m.get("name"), code)));
                    if (m.get("gameTypes") instanceof List<?> tl)
                    {
                        for (Object t : tl)
                        {
                            if (t instanceof Map<?, ?> tm && tm.get("code") != null)
                            {
                                String tc = String.valueOf(tm.get("code"));
                                Map<String, Object> opt = gty.stream().filter(x -> tc.equals(x.get("code"))).findFirst().orElse(null);
                                if (opt == null)
                                {
                                    opt = option(tc, str(tm.get("name"), tc));
                                    opt.put("platforms", new ArrayList<String>());
                                    gty.add(opt);
                                }
                                // 同一类型可能挂在多个平台下：platforms 为全部所属平台，前端按所选平台过滤
                                @SuppressWarnings("unchecked")
                                List<String> pfs = (List<String>) opt.get("platforms");
                                if (!pfs.contains(code))
                                {
                                    pfs.add(code);
                                }
                                s.put("gty", tc, str(tm.get("name"), tc));
                            }
                        }
                    }
                }
            }
        }
        List<Map<String, Object>> game = new ArrayList<>();
        for (Map<String, Object> g : or(safe(() -> risk.games(site)), Map.<String, Map<String, Object>>of()).values())
        {
            String code = String.valueOf(g.get("code"));
            s.put("game", code, str(g.get("name"), code));
            Map<String, Object> opt = option(code, str(g.get("name"), code));
            opt.put("platform", g.get("platform"));
            opt.put("gameType", g.get("gameType"));
            game.add(opt);
        }
        s.values.put("pf", pf);
        s.values.put("gty", gty);
        s.values.put("game", game);
        // 终端
        List<Map<String, Object>> term = new ArrayList<>();
        PromoTerminalDictionary.Config tc = safe(() -> terminals.of(site));
        if (tc != null)
        {
            for (PromoTerminalDictionary.Terminal t : tc.getTerminals())
            {
                if (PromoTerminalDictionary.UNIDENTIFIED.equals(t.getCode()))
                {
                    continue;
                }
                s.put("term", t.getCode(), t.getName());
                term.add(option(t.getCode(), t.getName()));
            }
        }
        s.values.put("term", term);
        // 支付通道
        List<Map<String, Object>> pay = new ArrayList<>();
        String text = safe(() -> configs.value(PAY_CHANNELS, site));
        try
        {
            Object v = JSON.readValue(StringUtils.isEmpty(text) ? DEFAULT_PAY_CHANNELS : text, Object.class);
            if (v instanceof List<?> l)
            {
                for (Object o : l)
                {
                    if (o instanceof Map<?, ?> m && m.get("code") != null)
                    {
                        String code = String.valueOf(m.get("code"));
                        s.put("pay", code, str(m.get("name"), code));
                        pay.add(option(code, str(m.get("name"), code)));
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] {} 解析失败：{}", PAY_CHANNELS, e.getMessage());
        }
        s.values.put("pay", pay);
        return s;
    }

    /** 校验配置中心 MONITOR_COND_PAY_CHANNELS：[{code, name}] */
    public static String validatePayChannels(String json)
    {
        try
        {
            Object v = JSON.readValue(json, Object.class);
            if (!(v instanceof List<?> l))
            {
                return "要是陣列，如 [{\"code\":\"upi\",\"name\":\"UPI\"}]";
            }
            for (Object o : l)
            {
                if (!(o instanceof Map<?, ?> m) || StringUtils.isEmpty(str(m.get("code"), null)) || StringUtils.isEmpty(str(m.get("name"), null)))
                {
                    return "每項要有 code 與 name";
                }
            }
            return null;
        }
        catch (RuntimeException e)
        {
            return "JSON 格式不正確";
        }
    }

    /** 一个站点的维度字典 */
    public static class Snapshot implements CondRules.DimLabels
    {
        final Map<String, Map<String, String>> labels = new LinkedHashMap<>();

        final Map<String, List<Map<String, Object>>> values = new LinkedHashMap<>();

        Snapshot()
        {
            for (String d : List.of("ch", "pay", "pf", "gty", "game", "term"))
            {
                labels.put(d, new LinkedHashMap<>());
            }
        }

        void put(String dim, String code, String name)
        {
            labels.get(dim).put(code, name);
        }

        @Override
        public String label(String dim, String value)
        {
            Map<String, String> m = labels.get(dim);
            if (m == null)
            {
                return null;
            }
            if (m.isEmpty())
            {
                return value;
            }
            return m.get(value);
        }

        /** 字段目录的 dims：{ch: {name, values}, …} */
        public Map<String, Object> options()
        {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : values.entrySet())
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", CondRules.DIM_TEXT.get(e.getKey()));
                m.put("values", e.getValue());
                out.put(e.getKey(), m);
            }
            return out;
        }
    }

    private static Map<String, Object> option(String code, String name)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        return m;
    }

    private static String str(Object o, String fallback)
    {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    private static <T> T or(T v, T fallback)
    {
        return v == null ? fallback : v;
    }

    private static <T> T safe(java.util.function.Supplier<T> s)
    {
        try
        {
            return s.get();
        }
        catch (RuntimeException e)
        {
            log.warn("[monitor] 读取条件维度字典失败：{}", e.getMessage());
            return null;
        }
    }
}
