package com.fivetech.risk.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.configcenter.ConfigCenterService;
import com.fivetech.configcenter.domain.ConfigItem;
import com.fivetech.risk.RiskException;
import com.fivetech.risk.catalog.RiskCatalog;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 风控分析的配置：榜单条件（RISK_RANK_THRESHOLDS）、榜单设置（RISK_RANK_BOARDS）、游戏字典（RISK_GAME_DICT），
 * 都在配置中心按站点维护（站点自己的优先，没有用全站通用，再没有用这里的默认值）。
 *
 * @author fivetech
 */
@Service
public class RiskConfigService
{
    private static final Logger log = LoggerFactory.getLogger(RiskConfigService.class);

    public static final String THRESHOLDS = "RISK_RANK_THRESHOLDS";

    public static final String BOARDS = "RISK_RANK_BOARDS";

    public static final String GAME_DICT = "RISK_GAME_DICT";

    static final ObjectMapper JSON = JsonMapper.builder().build();

    /** 默认榜单条件（配置中心没配时） */
    public static final String DEFAULT_THRESHOLDS = "{\n"
        + "  \"values\": { \"bigOne\": 50000, \"newDays\": 7, \"winMin\": 20000, \"rtpBet\": 20000, \"lowTurn\": 1 },\n"
        + "  \"limits\": {\n"
        + "    \"bigOne\": { \"min\": 1000, \"max\": 100000000 }, \"newDays\": { \"min\": 1, \"max\": 90 },\n"
        + "    \"winMin\": { \"min\": 100, \"max\": 100000000 }, \"rtpBet\": { \"min\": 100, \"max\": 100000000 },\n"
        + "    \"lowTurn\": { \"min\": 0, \"max\": 100 } },\n"
        + "  \"names\": { \"bigOne\": \"大額提款榜 · 單筆提款 ≥\", \"newDays\": \"新號榜 · 註冊 ≤（天）\",\n"
        + "    \"winMin\": \"大額中獎榜 · 單筆派彩 ≥\", \"rtpBet\": \"高 RTP 榜 · 有效投注 ≥\", \"lowTurn\": \"低打碼提款榜 · 打碼倍數 ≤\" }\n"
        + "}";

    /** 默认榜单设置（与 release_next 的 RISK_RANK_BOARDS 一致；卡片 3 个数、默认列见 FY-66 表 3-1、FY-70、FY-71） */
    public static final String DEFAULT_BOARDS = "{\n"
        + "  \"myBoardLimit\": 10,\n"
        + "  \"bands\": [1, 5, 10],\n"
        + "  \"defaultCards\": [\"pnl\", \"wd\", \"new\", \"low\", \"win\", \"bigw\"],\n"
        + "  \"shareCols\": [\"dep\", \"wd\", \"bet\", \"pay\", \"bonus\", \"maxPayout\"],\n"
        + "  \"sharePositiveCols\": [\"winLoss\"],\n"
        + "  \"ratioCols\": [\"rtp\", \"turnX\", \"bonusR\"],\n"
        + "  \"riskTypes\": [\n"
        + "    { \"code\": \"newWin\", \"name\": \"新號盈利\", \"board\": \"new\" },\n"
        + "    { \"code\": \"lowTurn\", \"name\": \"低打碼提款\", \"board\": \"low\" },\n"
        + "    { \"code\": \"highRtp\", \"name\": \"高RTP\", \"board\": \"rtp\", \"and\": [{ \"field\": \"rtp\", \"min\": 1 }] },\n"
        + "    { \"code\": \"bigWin\", \"name\": \"大額中獎\", \"board\": \"win\" } ],\n"
        + "  \"channelRiskDefault\": [\"newWin\", \"lowTurn\", \"bigWin\"],\n"
        + "  \"boards\": [\n"
        + "    { \"code\": \"pnl\", \"name\": \"盈利榜\", \"entry\": [{ \"field\": \"winLoss\", \"gt\": 0 }], \"rankCol\": \"winLoss\",\n"
        + "      \"shareMode\": \"SHARE\", \"shareBase\": \"ENTRY\", \"cols\": [\"bet\", \"rtp\", \"net\", \"regTime\", \"channel\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"入榜\" }, { \"code\": \"sum\", \"label\": \"合計\" }, { \"code\": \"activeShare\", \"label\": \"佔活躍用戶\" }] },\n"
        + "    { \"code\": \"wd\", \"name\": \"提款榜\", \"entry\": [{ \"field\": \"wd\", \"gt\": 0 }], \"rankCol\": \"wd\",\n"
        + "      \"shareMode\": \"SHARE\", \"shareBase\": \"ALL\", \"cols\": [\"wdN\", \"dep\", \"net\", \"turnX\", \"regTime\", \"channel\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"入榜\" }, { \"code\": \"sum\", \"label\": \"合計\" }, { \"code\": \"ratio\", \"num\": \"wd\", \"den\": \"dep\", \"label\": \"提存比\" }] },\n"
        + "    { \"code\": \"new\", \"name\": \"新號榜\", \"entry\": [{ \"field\": \"regDays\", \"max\": \"$newDays\" }, { \"field\": \"winLoss\", \"gt\": 0 }],\n"
        + "      \"rankCol\": \"winLoss\", \"shareMode\": \"SHARE\", \"shareBase\": \"ENTRY\", \"cols\": [\"dep\", \"wd\", \"parent\", \"regTime\", \"channel\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"入榜\" }, { \"code\": \"sum\", \"label\": \"合計\" }, { \"code\": \"shareOfPositive\", \"col\": \"winLoss\", \"label\": \"佔全部盈利\" }] },\n"
        + "    { \"code\": \"win\", \"name\": \"大額中獎榜\", \"entry\": [{ \"field\": \"maxPayout\", \"min\": \"$winMin\" }], \"rankCol\": \"maxPayout\",\n"
        + "      \"shareMode\": \"GAME_DIST\", \"distCol\": \"winGame\", \"cols\": [\"winGame\", \"winTime\", \"maxPayoutBet\", \"payoutX\", \"regTime\", \"channel\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"入榜\" }, { \"code\": \"max\", \"label\": \"最高派彩\" }, { \"code\": \"maxOf\", \"col\": \"payoutX\", \"label\": \"最高倍數\" }] },\n"
        + "    { \"code\": \"rtp\", \"name\": \"高RTP榜\", \"entry\": [{ \"field\": \"bet\", \"min\": \"$rtpBet\" }], \"rankCol\": \"rtp\",\n"
        + "      \"shareMode\": \"GAME_DIST\", \"distCol\": \"profitGame\", \"cols\": [\"bet\", \"winLoss\", \"profitGame\", \"regTime\", \"channel\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"入榜\" }, { \"code\": \"countAtLeast\", \"col\": \"rtp\", \"value\": 1, \"label\": \"RTP ≥ 100% 的人數\" },\n"
        + "                 { \"code\": \"sumOf\", \"col\": \"winLoss\", \"label\": \"輸贏合計\" }] },\n"
        + "    { \"code\": \"low\", \"name\": \"低打碼提款榜\", \"entry\": [{ \"field\": \"wd\", \"gt\": 0 }, { \"field\": \"turnX\", \"max\": \"$lowTurn\" }],\n"
        + "      \"rankCol\": \"wd\", \"shareMode\": \"SHARE\", \"shareBase\": \"ALL\", \"cols\": [\"dep\", \"turnX\", \"wdN\", \"regTime\", \"channel\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"入榜\" }, { \"code\": \"sum\", \"label\": \"合計\" }, { \"code\": \"shareOfAll\", \"col\": \"wd\", \"label\": \"佔全部提款\" }] },\n"
        + "    { \"code\": \"bigw\", \"name\": \"大額提款榜\", \"kind\": \"ORDER\", \"threshold\": \"$bigOne\", \"shareMode\": \"SHARE\",\n"
        + "      \"filters\": [\"regDays\", \"turnX\", \"dep\", \"winLoss\"], \"cols\": [\"turnX\"], \"colOptions\": [\"turnX\", \"dep\", \"winLoss\"],\n"
        + "      \"stats\": [{ \"code\": \"entry\", \"label\": \"大額筆數\" }, { \"code\": \"sum\", \"label\": \"金額\" }, { \"code\": \"shareOfAll\", \"col\": \"wd\", \"label\": \"佔全部提款\" }] },\n"
        + "    { \"code\": \"chrisk\", \"name\": \"渠道風險榜\", \"kind\": \"CHANNEL\", \"rankCol\": \"riskWd\",\n"
        + "      \"cols\": [\"ftdUsers\", \"depAmt\", \"avgDep\", \"activeUsers\", \"riskUsers\", \"riskRate\", \"riskWd\", \"riskTypeCounts\", \"winLoss\"],\n"
        + "      \"drillCols\": [\"dep\", \"turnX\", \"winLoss\", \"regTime\"], \"drillRankCol\": \"wd\",\n"
        + "      \"stats\": [{ \"code\": \"riskChannels\", \"label\": \"風險渠道\" }, { \"code\": \"riskUsers\", \"label\": \"風險用戶\" }, { \"code\": \"riskWd\", \"label\": \"風險用戶提款\" }],\n"
        + "      \"channelCols\": [\n"
        + "        { \"code\": \"regUsers\", \"name\": \"註冊用戶\", \"group\": \"ACQ\", \"valueFormat\": \"INT\", \"rank\": true, \"condition\": true, \"fixed\": true,\n"
        + "          \"remark\": \"所選時間內註冊的用戶數（含沒有存款或投注的）\" },\n"
        + "        { \"code\": \"ftdUsers\", \"name\": \"首存人數\", \"group\": \"ACQ\", \"valueFormat\": \"INT\", \"rank\": true, \"condition\": true,\n"
        + "          \"remark\": \"所選時間內完成首存的用戶數（按首存時間，不看註冊時間）\" },\n"
        + "        { \"code\": \"ftdAmt\", \"name\": \"首存金額\", \"group\": \"ACQ\", \"valueFormat\": \"MONEY\", \"rank\": true,\n"
        + "          \"remark\": \"所選時間內完成首存的用戶，首存金額合計\" },\n"
        + "        { \"code\": \"activeUsers\", \"name\": \"活躍用戶\", \"group\": \"ACQ\", \"valueFormat\": \"INT\", \"rank\": true,\n"
        + "          \"remark\": \"所選時間內有存款或投注的用戶數\" },\n"
        + "        { \"code\": \"depUsers\", \"name\": \"存款人數\", \"group\": \"FUND\", \"valueFormat\": \"INT\", \"rank\": true, \"condition\": true,\n"
        + "          \"remark\": \"所選時間內有成功存款的用戶數\" },\n"
        + "        { \"code\": \"depAmt\", \"name\": \"存款金額\", \"group\": \"FUND\", \"valueFormat\": \"MONEY\", \"rank\": true, \"condition\": true,\n"
        + "          \"remark\": \"所選時間內的成功存款合計\" },\n"
        + "        { \"code\": \"avgDep\", \"name\": \"平均存款金額\", \"group\": \"FUND\", \"valueFormat\": \"MONEY\", \"rank\": true, \"condition\": true,\n"
        + "          \"remark\": \"存款金額 ÷ 存款人數\" },\n"
        + "        { \"code\": \"wd\", \"name\": \"提款\", \"group\": \"FUND\", \"valueFormat\": \"MONEY\", \"rank\": true, \"condition\": true },\n"
        + "        { \"code\": \"riskUsers\", \"name\": \"風險用戶\", \"group\": \"RISK\", \"valueFormat\": \"INT\", \"rank\": true, \"condition\": true },\n"
        + "        { \"code\": \"riskRate\", \"name\": \"風險用戶佔比\", \"group\": \"RISK\", \"valueFormat\": \"PCT\", \"rank\": true, \"condition\": true,\n"
        + "          \"remark\": \"風險用戶 ÷ 活躍用戶\" },\n"
        + "        { \"code\": \"riskWd\", \"name\": \"風險用戶提款\", \"group\": \"RISK\", \"valueFormat\": \"MONEY\", \"rank\": true, \"condition\": true },\n"
        + "        { \"code\": \"riskTypeCounts\", \"name\": \"各風險類型人數\", \"group\": \"RISK\", \"valueFormat\": \"INT\" },\n"
        + "        { \"code\": \"bet\", \"name\": \"有效投注\", \"group\": \"GAME\", \"valueFormat\": \"MONEY\", \"rank\": true, \"condition\": true },\n"
        + "        { \"code\": \"winLoss\", \"name\": \"玩家輸贏\", \"group\": \"GAME\", \"valueFormat\": \"MONEY\", \"rank\": true, \"condition\": true } ] },\n"
        + "    { \"code\": \"all\", \"name\": \"全部用戶\", \"scope\": [{ \"any\": [{ \"field\": \"dep\", \"gt\": 0 }, { \"field\": \"bet\", \"gt\": 0 }] }],\n"
        + "      \"rankCol\": \"winLoss\", \"shareMode\": \"NONE\", \"card\": false, \"cols\": [\"dep\", \"wd\", \"bet\", \"regTime\", \"channel\"] }\n"
        + "  ]\n"
        + "}";

    /** 默认游戏字典（UDS 编码定下来前为空） */
    public static final String DEFAULT_GAME_DICT = "{ \"platforms\": [], \"games\": [] }";

    private final ConfigCenterService configs;

    private final RiskCatalog catalog;

    public RiskConfigService(ConfigCenterService configs, RiskCatalog catalog)
    {
        this.configs = configs;
        this.catalog = catalog;
    }

    /** 当前站点生效的榜单条件与榜单设置；配置有错时退回默认并记日志 */
    public RiskBoards boards(String site)
    {
        RiskBoards b = new RiskBoards();
        List<String> errors = new ArrayList<>();
        b.readThresholds(read(THRESHOLDS, site, DEFAULT_THRESHOLDS), errors);
        if (!errors.isEmpty())
        {
            log.warn("[risk] {} 配置有误，用默认值：{}", THRESHOLDS, errors);
            b = new RiskBoards();
            b.readThresholds(parse(DEFAULT_THRESHOLDS), null);
        }
        errors.clear();
        b.readBoards(read(BOARDS, site, DEFAULT_BOARDS), null, errors);
        if (!errors.isEmpty())
        {
            log.warn("[risk] {} 配置有误（无效项已跳过）：{}", BOARDS, errors);
        }
        if (b.boards.isEmpty())
        {
            RiskBoards d = new RiskBoards();
            d.thresholds = b.thresholds;
            d.limits = b.limits;
            d.thresholdNames = b.thresholdNames;
            d.readBoards(parse(DEFAULT_BOARDS), null, null);
            b = d;
        }
        return b;
    }

    /** 游戏字典：{ platforms: [{code, name, brands:[{code,name}], gameTypes:[{code,name}]}], games: [{code, name, platform, brand, gameType}] } */
    @SuppressWarnings("unchecked")
    public Map<String, Object> gameDict(String site)
    {
        Object v = read(GAME_DICT, site, DEFAULT_GAME_DICT);
        Map<String, Object> m = v instanceof Map<?, ?> x ? new LinkedHashMap<>((Map<String, Object>) x) : new LinkedHashMap<>();
        m.putIfAbsent("platforms", List.of());
        m.putIfAbsent("games", List.of());
        return m;
    }

    /** 游戏编码 → 名称（找不到返回编码本身） */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> games(String site)
    {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Object list = gameDict(site).get("games");
        if (list instanceof List<?> l)
        {
            for (Object o : l)
            {
                if (o instanceof Map<?, ?> g && g.get("code") != null)
                {
                    out.put(String.valueOf(g.get("code")), (Map<String, Object>) g);
                }
            }
        }
        return out;
    }

    /** 生效的榜单条件那一条（修改人、时间）；没配为 null */
    public ConfigItem thresholdItem(String site)
    {
        return configs.effective(THRESHOLDS, site);
    }

    /** 渠道风险榜全局默认风险类型在 RISK_RANK_THRESHOLDS 里的键 */
    public static final String CHANNEL_RISK_TYPES = "channelRiskTypes";

    /**
     * 改榜单条件：只能改已有的键，值要在 limits 范围内；写到当前站点自己的配置（没有就新建），全站共用、对所有人生效。
     * {@code channelRiskTypes} 为渠道风险榜的全局默认风险类型（至少 1 类，只认配置里的风险类型）。
     */
    public void saveThresholds(String site, Map<String, Object> body, String operator)
    {
        RiskBoards current = boards(site);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, BigDecimal> next = new LinkedHashMap<>(current.thresholds);
        List<String> channelTypes = current.known(current.channelRiskGlobal);
        for (Map.Entry<String, Object> e : (body == null ? Map.<String, Object>of() : body).entrySet())
        {
            String key = e.getKey();
            if (CHANNEL_RISK_TYPES.equals(key))
            {
                List<String> raw = RiskBoards.strings(e.getValue());
                List<String> known = current.known(raw);
                if (!(e.getValue() instanceof List<?>) || known.isEmpty())
                {
                    errors.put(key, "至少勾選 1 類風險");
                }
                else if (known.size() != raw.size())
                {
                    errors.put(key, "風險類型不存在：" + raw.stream().filter(x -> !known.contains(x)).toList());
                }
                else
                {
                    channelTypes = known;
                }
                continue;
            }
            if (!current.thresholds.containsKey(key))
            {
                errors.put(key, "不認識的榜單條件：" + key);
                continue;
            }
            BigDecimal v = RiskBoards.num(e.getValue());
            BigDecimal[] lim = current.limits.getOrDefault(key, new BigDecimal[2]);
            if (v == null)
            {
                errors.put(key, "請填寫數字");
            }
            else if ((lim[0] != null && v.compareTo(lim[0]) < 0) || (lim[1] != null && v.compareTo(lim[1]) > 0))
            {
                errors.put(key, "請填寫 " + plain(lim[0]) + " – " + plain(lim[1]) + " 之間的數字");
            }
            else
            {
                next.put(key, v);
            }
        }
        RiskException.failIfAny(errors);
        Map<String, Object> value = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        next.forEach((k, v) -> values.put(k, RiskBoards.num(v)));
        value.put("values", values);
        Map<String, Object> limits = new LinkedHashMap<>();
        current.limits.forEach((k, r) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            if (r[0] != null)
            {
                x.put("min", r[0]);
            }
            if (r[1] != null)
            {
                x.put("max", r[1]);
            }
            limits.put(k, x);
        });
        value.put("limits", limits);
        value.put("names", current.thresholdNames);
        if (!channelTypes.isEmpty())
        {
            value.put(CHANNEL_RISK_TYPES, channelTypes);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("configCode", THRESHOLDS);
        item.put("siteCode", site == null ? "" : site);
        item.put("pageName", "風控分析");
        item.put("description", "排行榜的榜單條件（全站共用，只作用於榜單本身）");
        item.put("configValue", JSON.writeValueAsString(value));
        ConfigItem own = StringUtils.isEmpty(site) ? configs.effective(THRESHOLDS, "") : null;
        if (StringUtils.isNotEmpty(site))
        {
            ConfigItem e = configs.effective(THRESHOLDS, site);
            own = e != null && site.equalsIgnoreCase(StringUtils.trimToEmpty(e.getSiteCode())) ? e : null;
        }
        if (own == null)
        {
            configs.create(item, operator);
        }
        else
        {
            configs.update(own.getId(), item, operator);
        }
    }

    private static String plain(BigDecimal v)
    {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private Object read(String code, String site, String fallback)
    {
        String text;
        try
        {
            text = configs.value(code, site);
        }
        catch (RuntimeException e)
        {
            log.warn("[risk] 读取配置 {} 失败：{}", code, e.getMessage());
            text = null;
        }
        Object v = StringUtils.isEmpty(text) ? null : parse(text);
        return v == null ? parse(fallback) : v;
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

    /** 配置中心保存时的结构校验（ConfigCodes 调用）；返回第一个错误，没有返回 null */
    public static String validateThresholds(String json)
    {
        List<String> errors = new ArrayList<>();
        new RiskBoards().readThresholds(parse(json), errors);
        return errors.isEmpty() ? null : errors.get(0);
    }

    public static String validateBoards(String json)
    {
        List<String> errors = new ArrayList<>();
        RiskBoards b = new RiskBoards();
        b.readThresholds(parse(DEFAULT_THRESHOLDS), null);
        b.thresholds.clear();
        b.readBoards(parse(json), (Set<String>) null, errors);
        return errors.isEmpty() ? null : errors.get(0);
    }

    @SuppressWarnings("unchecked")
    public static String validateGameDict(String json)
    {
        Object v = parse(json);
        if (!(v instanceof Map<?, ?> m))
        {
            return "應為對象：{ \"platforms\": [...], \"games\": [...] }";
        }
        Object ps = m.get("platforms");
        if (!(ps instanceof List<?> pl))
        {
            return "platforms 應為數組：[{ \"code\", \"name\", \"brands\": [...], \"gameTypes\": [...] }]";
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Object o : pl)
        {
            if (!(o instanceof Map<?, ?> p) || p.get("code") == null || p.get("name") == null)
            {
                return "platforms 每項要有 code 與 name";
            }
            if (!codes.add(String.valueOf(p.get("code"))))
            {
                return "遊戲平台編碼重複：" + p.get("code");
            }
        }
        Object gs = m.get("games");
        if (gs != null && !(gs instanceof List<?>))
        {
            return "games 應為數組：[{ \"code\", \"name\", \"platform\", \"brand\", \"gameType\" }]";
        }
        java.util.Set<String> games = new java.util.HashSet<>();
        for (Object o : gs == null ? List.of() : (List<Object>) gs)
        {
            if (!(o instanceof Map<?, ?> g) || g.get("code") == null || g.get("name") == null)
            {
                return "games 每項要有 code 與 name";
            }
            if (!games.add(String.valueOf(g.get("code"))))
            {
                return "遊戲編碼重複：" + g.get("code");
            }
            if (g.get("platform") != null && !codes.contains(String.valueOf(g.get("platform"))))
            {
                return "遊戲 " + g.get("code") + " 的平台 " + g.get("platform") + " 不在 platforms 裡";
            }
        }
        return null;
    }

    /** 测试用：当前列目录的接口编码 */
    Set<String> columnCodes()
    {
        return catalog.rank().keySet();
    }
}
