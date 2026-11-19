package com.fivetech.risk.overview;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.configcenter.ConfigCenterService;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 风控概览（scene = RISK）的变化归因：维度字典与每个指标可用的维度都来自配置中心 {@code ATTRIBUTION_DIMENSIONS}，
 * 维度的中文名随概览接口下发（顶层 {@code attributionDimensions}、每张卡 {@code attributionDims}），前端不写死。
 *
 * <p>配置结构（按场景，目前只有 RISK；先配「全部站点」一份，站点可单独覆盖）：</p>
 * <pre>
 * { "RISK": {
 *     "dimensions": [ {code, name, bands?, uds?} ],  // 维度字典；depBand 的 bands 为存款档位边界（升序）；uds = {dataset, field} 按哪个 UDS 字段拆
 *     "metrics":    { 指标编码: [维度编码] },          // 每个指标可按哪些维度拆，按此顺序展示
 *     "ratios":     { 指标编码: {goTo} },             // 比率类：不拆贡献，只写说明并给去处
 *     "snapshots":  [指标编码] } }                     // 快照类：不做归因
 * </pre>
 *
 * <p>请求 {@code attribution.dimension} 时每张卡都返回 {@code attribution}：
 * 卡片没配这个维度 → NOT_CONFIGURED；比率 → RATIO + goTo；快照 → SNAPSHOT；
 * 其余 → PENDING_SOURCE（TODO：UDS 按推广商 / 渠道 / 新老用户 / 存款档位 / 游戏拆分的数据接入后填 total、rows）。</p>
 *
 * @author fivetech
 */
@Component
public class RiskAttribution
{
    private static final Logger log = LoggerFactory.getLogger(RiskAttribution.class);

    public static final String CODE = "ATTRIBUTION_DIMENSIONS";

    public static final String SCENE = "RISK";

    static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 代码默认（配置中心没配时用）；资金类：推广商、推广渠道、新老用户、存款档位；游戏类另加游戏平台、游戏类型、游戏 */
    public static final String DEFAULT = "{\n"
        + "  \"RISK\": {\n"
        + "    \"dimensions\": [\n"
        + "      { \"code\": \"agent\", \"name\": \"推廣商\" },\n"
        + "      { \"code\": \"channel\", \"name\": \"推廣渠道\", \"uds\": { \"dataset\": \"risk_user_window_v1\", \"field\": \"channel\" } },\n"
        + "      { \"code\": \"age\", \"name\": \"新老用戶\", \"uds\": { \"dataset\": \"risk_user_window_v1\", \"field\": \"reg_bucket\" } },\n"
        + "      { \"code\": \"depBand\", \"name\": \"存款檔位\", \"bands\": [500, 1000, 5000],\n"
        + "        \"uds\": { \"dataset\": \"risk_user_window_v1\", \"field\": \"dep_bucket\" } },\n"
        + "      { \"code\": \"platform\", \"name\": \"遊戲平台\", \"uds\": { \"dataset\": \"risk_game_player_cube_window_v1\", \"field\": \"provider_code\" } },\n"
        + "      { \"code\": \"gameType\", \"name\": \"遊戲類型\", \"uds\": { \"dataset\": \"risk_game_player_cube_window_v1\", \"field\": \"game_type\" } },\n"
        + "      { \"code\": \"game\", \"name\": \"遊戲\", \"uds\": { \"dataset\": \"risk_game_player_cube_window_v1\", \"field\": \"game_id\" } }\n"
        + "    ],\n"
        + "    \"metrics\": {\n"
        + "      \"reg\": [\"agent\", \"channel\"],\n"
        + "      \"ftd\": [\"agent\", \"channel\"],\n"
        + "      \"dep\": [\"agent\", \"channel\", \"age\", \"depBand\"],\n"
        + "      \"depU\": [\"agent\", \"channel\", \"age\", \"depBand\"],\n"
        + "      \"wd\": [\"agent\", \"channel\", \"age\", \"depBand\"],\n"
        + "      \"wOk\": [\"agent\", \"channel\", \"age\", \"depBand\"],\n"
        + "      \"net\": [\"agent\", \"channel\", \"age\", \"depBand\"],\n"
        + "      \"ngr\": [\"agent\", \"channel\", \"age\", \"depBand\"],\n"
        + "      \"bet\": [\"agent\", \"channel\", \"age\", \"depBand\", \"platform\", \"gameType\", \"game\"],\n"
        + "      \"pay\": [\"agent\", \"channel\", \"age\", \"depBand\", \"platform\", \"gameType\", \"game\"],\n"
        + "      \"ggr\": [\"agent\", \"channel\", \"age\", \"depBand\", \"platform\", \"gameType\", \"game\"]\n"
        + "    },\n"
        + "    \"ratios\": {\n"
        + "      \"wdr\": { \"goTo\": { \"tab\": \"rank\", \"board\": \"wd\" } },\n"
        + "      \"rtp\": { \"goTo\": { \"tab\": \"games\" } },\n"
        + "      \"killR\": { \"goTo\": { \"tab\": \"games\" } },\n"
        + "      \"dOkR\": {}\n"
        + "    },\n"
        + "    \"snapshots\": [\"wPendN\", \"wPendT\"]\n"
        + "  }\n"
        + "}";

    private final ConfigCenterService configs;

    public RiskAttribution(ConfigCenterService configs)
    {
        this.configs = configs;
    }

    /** 一个站点生效的风控归因配置 */
    public static final class Config
    {
        /** 维度字典（按配置顺序）：code → name */
        final Map<String, String> names = new LinkedHashMap<>();

        /** 维度 → UDS {dataset, field}（拆分取数用；推广商等 UDS 没有的不配） */
        final Map<String, Map<String, String>> uds = new LinkedHashMap<>();

        public Map<String, String> uds(String dimension) { return uds.getOrDefault(dimension, Map.of()); }

        /** depBand 等的档位边界 */
        final Map<String, List<BigDecimal>> bands = new LinkedHashMap<>();

        final Map<String, List<String>> metrics = new LinkedHashMap<>();

        final Map<String, Map<String, Object>> ratios = new LinkedHashMap<>();

        final Set<String> snapshots = new HashSet<>();

        public Map<String, String> getNames() { return names; }

        public List<String> dims(String metric) { return metrics.getOrDefault(metric, List.of()); }

        public boolean isRatio(String metric) { return ratios.containsKey(metric); }

        public boolean isSnapshot(String metric) { return snapshots.contains(metric); }

        public List<BigDecimal> bands(String dimension) { return bands.getOrDefault(dimension, List.of()); }
    }

    /** 当前站点的配置：站点自己的 → 全部站点的 → 代码默认；配置有误时用代码默认并记日志 */
    public Config config(String site)
    {
        String text = null;
        try
        {
            text = configs == null ? null : configs.value(CODE, site);
        }
        catch (RuntimeException e)
        {
            log.warn("[risk] 读取配置 {} 失败：{}", CODE, e.getMessage());
        }
        if (StringUtils.isNotEmpty(text))
        {
            List<String> errors = new ArrayList<>();
            Config c = read(parse(text), errors);
            if (errors.isEmpty())
            {
                return c;
            }
            log.warn("[risk] {} 配置有误，用默认值：{}", CODE, errors);
        }
        return read(parse(DEFAULT), null);
    }

    /**
     * 校验请求里的归因维度（取数前调用）：不传返回 null；不在维度字典里 → 4003。
     */
    public String dimension(OverviewQuery query, Config c)
    {
        if (query.getAttribution() == null || StringUtils.isEmpty(query.getAttribution().getDimension())
            || query.getAttribution().getDimension().trim().isEmpty())
        {
            return null;
        }
        String want = query.getAttribution().getDimension().trim();
        for (String code : c.names.keySet())
        {
            if (code.equalsIgnoreCase(want))
            {
                return code;
            }
        }
        throw new ServiceException("不認識的歸因維度：" + want + "（" + String.join(" / ", c.names.keySet()) + "）", 4003);
    }

    /**
     * 装配：顶层 attributionDimensions = 维度字典；每张卡 attributionDims = 该指标可用的维度（带名称）；
     * 请求了维度时每张卡都有 attribution。
     */
    public void apply(OverviewVO vo, MetricsBlockVO block, String dimension, Config c)
    {
        List<Map<String, Object>> dict = new ArrayList<>();
        c.names.forEach((code, name) -> dict.add(dim(code, name)));
        vo.setAttributionDimensions(dict);
        if (block == null)
        {
            return;
        }
        for (MetricCardVO card : block.getItems())
        {
            List<Map<String, Object>> dims = new ArrayList<>();
            if (!c.isRatio(card.getCode()) && !c.isSnapshot(card.getCode()))
            {
                for (String d : c.dims(card.getCode()))
                {
                    if (c.names.containsKey(d))
                    {
                        dims.add(dim(d, c.names.get(d)));
                    }
                }
            }
            card.setAttributionDims(dims);
            if (dimension != null)
            {
                card.setAttribution(attribution(card, dimension, c));
            }
        }
    }

    /** 一张卡的归因结果（supported = false 时没有 total / rows） */
    static Map<String, Object> attribution(MetricCardVO card, String dimension, Config c)
    {
        String name = c.names.get(dimension);
        String label = card.getLabel() == null ? card.getCode() : card.getLabel();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("dimension", dimension);
        a.put("dimensionName", name);
        a.put("supported", false);
        if (c.isRatio(card.getCode()))
        {
            Object goTo = c.ratios.get(card.getCode()).get("goTo");
            a.put("reasonCode", "RATIO");
            a.put("reason", label + "是比率類指標，變化同時來自各" + name + "自身的變化和結構的變化，不能拆成各"
                + name + "的貢獻。" + (goTo == null ? "" : goToText(goTo)));
            a.put("goTo", goTo);
            return a;
        }
        if (c.isSnapshot(card.getCode()))
        {
            a.put("reasonCode", "SNAPSHOT");
            a.put("reason", label + "是時間點上的快照，不做變化歸因。");
            return a;
        }
        if (!c.dims(card.getCode()).contains(dimension))
        {
            a.put("reasonCode", "NOT_CONFIGURED");
            a.put("reason", label + "未配置按" + name + "歸因（配置中心 › " + CODE + "）。");
            return a;
        }
        // TODO UDS：按推广商 / 推广渠道 / 新老用户 / 存款档位 / 游戏平台 / 类型 / 游戏拆分的数据接入后，
        //  supported = true，total = {value, prevValue}（= 卡片值），rows = [{key, name, value, prevValue}]
        a.put("reasonCode", "PENDING_SOURCE");
        a.put("reason", "數據平台還沒有按" + name + "拆分的數據，暫不能歸因。");
        return a;
    }

    private static String goToText(Object goTo)
    {
        if (goTo instanceof Map<?, ?> g)
        {
            if ("games".equals(g.get("tab")))
            {
                return "各遊戲的值可在遊戲分析查看。";
            }
            if ("rank".equals(g.get("tab")))
            {
                return "相關用戶可在排行榜查看。";
            }
        }
        return "";
    }

    private static Map<String, Object> dim(String code, String name)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        return m;
    }

    /** 配置中心保存时的结构校验；返回第一个错误，没有返回 null */
    public static String validate(String json)
    {
        List<String> errors = new ArrayList<>();
        read(parse(json), errors);
        return errors.isEmpty() ? null : errors.get(0);
    }

    static Object parse(String text)
    {
        try
        {
            return JSON.readValue(text, Object.class);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Config read(Object root, List<String> errors)
    {
        Config c = new Config();
        if (!(root instanceof Map<?, ?> r))
        {
            err(errors, "應為 JSON 對象，鍵為場景（RISK）");
            return c;
        }
        for (Object k : r.keySet())
        {
            if (!SCENE.equals(k))
            {
                err(errors, "不認識的場景：" + k + "（目前只有 RISK）");
            }
        }
        if (!(r.get(SCENE) instanceof Map<?, ?> s))
        {
            err(errors, "缺少 RISK");
            return c;
        }
        if (!(s.get("dimensions") instanceof List<?> dims) || dims.isEmpty())
        {
            err(errors, "RISK.dimensions 應為非空數組 [{code, name}]");
            return c;
        }
        for (Object o : dims)
        {
            Map<String, Object> d = o instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
            String code = str(d.get("code"));
            String name = str(d.get("name"));
            if (code == null || name == null)
            {
                err(errors, "RISK.dimensions 每項都要有 code、name");
                continue;
            }
            if (c.names.containsKey(code))
            {
                err(errors, "RISK.dimensions 編碼重複：" + code);
                continue;
            }
            c.names.put(code, name);
            if (d.get("uds") != null)
            {
                Map<String, Object> u = d.get("uds") instanceof Map<?, ?> x ? (Map<String, Object>) x : null;
                String ds = u == null ? null : str(u.get("dataset"));
                String f = u == null ? null : str(u.get("field"));
                if (ds == null || f == null)
                {
                    err(errors, "維度 " + code + " 的 uds 應為 {dataset, field}");
                }
                else
                {
                    c.uds.put(code, Map.of("dataset", ds, "field", f));
                }
            }
            if (d.get("bands") != null)
            {
                List<BigDecimal> b = new ArrayList<>();
                if (d.get("bands") instanceof List<?> l)
                {
                    for (Object v : l)
                    {
                        BigDecimal n = num(v);
                        if (n == null || (!b.isEmpty() && n.compareTo(b.get(b.size() - 1)) <= 0))
                        {
                            err(errors, "維度 " + code + " 的 bands 應為升序數字");
                            b = null;
                            break;
                        }
                        b.add(n);
                    }
                }
                else
                {
                    err(errors, "維度 " + code + " 的 bands 應為數組");
                    b = null;
                }
                if (b != null)
                {
                    c.bands.put(code, b);
                }
            }
        }
        Object metrics = s.get("metrics");
        if (metrics != null && !(metrics instanceof Map<?, ?>))
        {
            err(errors, "RISK.metrics 應為 {指標編碼: [維度編碼]}");
        }
        else if (metrics != null)
        {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) metrics).entrySet())
            {
                if (!(e.getValue() instanceof List<?> l))
                {
                    err(errors, "RISK.metrics." + e.getKey() + " 應為維度編碼數組");
                    continue;
                }
                List<String> codes = new ArrayList<>();
                for (Object v : l)
                {
                    String d = str(v);
                    if (d == null || !c.names.containsKey(d))
                    {
                        err(errors, "RISK.metrics." + e.getKey() + " 的維度不在 dimensions 裡：" + v);
                    }
                    else if (!codes.contains(d))
                    {
                        codes.add(d);
                    }
                }
                c.metrics.put(e.getKey(), codes);
            }
        }
        Object ratios = s.get("ratios");
        if (ratios != null && !(ratios instanceof Map<?, ?>))
        {
            err(errors, "RISK.ratios 應為 {指標編碼: {goTo}}");
        }
        else if (ratios != null)
        {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) ratios).entrySet())
            {
                Map<String, Object> v = e.getValue() instanceof Map<?, ?> x ? new LinkedHashMap<>((Map<String, Object>) x) : null;
                if (v == null)
                {
                    err(errors, "RISK.ratios." + e.getKey() + " 應為對象（可帶 goTo）");
                    continue;
                }
                if (v.get("goTo") != null && !(v.get("goTo") instanceof Map<?, ?>))
                {
                    err(errors, "RISK.ratios." + e.getKey() + ".goTo 應為對象，如 {\"tab\":\"games\"}");
                }
                c.ratios.put(e.getKey(), v);
            }
        }
        Object snaps = s.get("snapshots");
        if (snaps != null && !(snaps instanceof List<?>))
        {
            err(errors, "RISK.snapshots 應為指標編碼數組");
        }
        else if (snaps != null)
        {
            ((List<Object>) snaps).forEach(v -> {
                if (str(v) != null)
                {
                    c.snapshots.add(str(v));
                }
            });
        }
        return c;
    }

    private static void err(List<String> errors, String text)
    {
        if (errors != null)
        {
            errors.add(text);
        }
    }

    private static String str(Object v)
    {
        return v instanceof String s && !s.trim().isEmpty() ? s.trim() : null;
    }

    private static BigDecimal num(Object v)
    {
        if (v instanceof Number n)
        {
            return new BigDecimal(n.toString());
        }
        return null;
    }
}
