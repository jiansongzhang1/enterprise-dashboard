package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指标字典的页面专属属性（dashboard_metric_card.ext）：同一张指标字典，按所属范围（page_scope）多出一组属性，
 * 决定这个指标在风控排行榜、游戏分析、条件规则里怎么用。都在「指标管理」里编辑，保存时按范围校验。
 *
 * <h3>RISK_RANK · 风控排行榜的列与名单条件字段</h3>
 * <pre>
 * { "apiCode": "dep",            // 接口里用的编码（列、条件、排行字段都用它）
 *   "group": "FUND",             // 列设置的分组：FUND 资金 / GAME 游戏 / ACCOUNT 帐户与推广 / HISTORY 历史累计
 *   "source": "USER_DAY",        // 取数来源：USER_DAY 用户×日 / USER_GAME 用户×游戏×日 / SNAPSHOT 玩家快照 / DIM 维度字段 / CALC 由其他列算
 *   "kind": "NUMBER",            // NUMBER 数值 / TEXT 文本 / TIME 时间
 *   "agg": "SUM",                // 区间内怎么汇总：SUM / MAX / MIN / LAST / FOLLOW（取自 follow 那一列取最大值的那一行）/ ARGMAX（按 argOf 汇总后取最大的游戏）
 *   "follow": "maxPayout",       // agg = FOLLOW 时：跟随哪一列
 *   "argOf": "bet", "positive": true,  // agg = ARGMAX 时：按哪一列汇总、是否只取 &gt; 0 的
 *   "tieBreak": { "ratioOf": "maxPayoutBet", "timeOf": "winTime" },  // MAX 列并列时：先取 本列÷ratioOf 大的，再取 timeOf 早的
 *   "calc": { "op": "DIVIDE", "left": "bet", "right": "dep" },        // source = CALC：DIVIDE / SUBTRACT / ADD / DAYS_SINCE（left 为时间列）
 *   "rankable": true,            // 能否作排行字段（默认数值列可以）
 *   "riskDir": "desc",           // 排行字段的风险方向（换字段时方向回到它）：desc 从高到低 / asc 从低到高
 *   "column": true,              // 是否出现在列设置里（可选列）
 *   "condition": "RANGE",        // 名单条件：RANGE 区间 / SELECT 多选 / 不填 = 不能做条件
 *   "common": true }             // 名单条件是否放在「常用」
 * </pre>
 *
 * <h3>RISK_GAME · 游戏分析的列</h3>
 * <pre>{ "apiCode": "bet", "group": "BET", "standard": true, "calc": { "op": "DIVIDE", "left": "pay", "right": "bet" } }</pre>
 * group：BET 投注 / WINLOSS 输赢 / USER 用户 / SPLIT 本金与优惠。没有 calc 的列直接取 uds_day_field（游戏数据集字段）。
 *
 * <h3>MONITOR_COND · 条件规则的字段目录</h3>
 * <pre>
 * { "apiCode": "dep", "dims": ["ch","pay","term"], "snapshot": false,
 *   "defaultHow": "BASE", "defaultOp": "DOWN", "absUnit": null,
 *   "ratio": { "numerator": "dep_ok", "denominator": "dep_try" },     // 比率字段：多小时时段按分子分母合计重算
 *   "sample": { "key": "dTry", "name": "存款訂單數", "unit": "筆", "field": "dep_try" } }
 * </pre>
 * 取数字段为 uds_hour_field（条件规则小时数据集的字段）。
 *
 * @author fivetech
 */
public final class MetricExt
{
    public static final String SCOPE_RISK_RANK = "RISK_RANK";

    public static final String SCOPE_RISK_GAME = "RISK_GAME";

    public static final String SCOPE_MONITOR_COND = "MONITOR_COND";

    /** 这三个范围是「页面目录」：不进运营总览的指标注册表，由各自页面按范围读取 */
    public static final List<String> CATALOG_SCOPES = List.of(SCOPE_RISK_RANK, SCOPE_RISK_GAME, SCOPE_MONITOR_COND);

    static final Pattern API_CODE = Pattern.compile("^[a-z][A-Za-z0-9]{0,31}$");

    static final Set<String> RANK_GROUPS = Set.of("FUND", "GAME", "ACCOUNT", "HISTORY");

    static final Set<String> RANK_SOURCES = Set.of("USER_DAY", "USER_GAME", "SNAPSHOT", "DIM", "CALC");

    static final Set<String> KINDS = Set.of("NUMBER", "TEXT", "TIME");

    static final Set<String> AGGS = Set.of("SUM", "MAX", "MIN", "LAST", "FOLLOW", "ARGMAX");

    static final Set<String> CALC_OPS = Set.of("DIVIDE", "SUBTRACT", "ADD", "DAYS_SINCE");

    static final Set<String> GAME_GROUPS = Set.of("BET", "WINLOSS", "USER", "SPLIT");

    /** 条件规则可选的维度：渠道、支付通道、游戏平台、游戏类型、游戏、终端 */
    public static final List<String> COND_DIMS = List.of("ch", "pay", "pf", "gty", "game", "term");

    static final Set<String> HOWS = Set.of("CUR", "BASE", "YD");

    static final Set<String> OPS = Set.of("GT", "GE", "LT", "LE", "OUT", "UP", "DOWN", "CHG");

    private MetricExt()
    {
    }

    /**
     * 按范围校验 ext，错误写进 errors（键为 ext 或 ext.xxx）。范围不是目录范围时 ext 必须为空。
     *
     * @return 规范化后的 ext（去掉空值；编码统一大小写）；为空返回 null
     */
    public static Map<String, Object> validate(String scope, Map<String, Object> ext, Map<String, String> errors)
    {
        if (ext == null || ext.isEmpty())
        {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ext.entrySet())
        {
            if (e.getValue() != null && !"".equals(e.getValue()))
            {
                out.put(e.getKey(), e.getValue());
            }
        }
        if (out.isEmpty())
        {
            return null;
        }
        if (scope == null || !CATALOG_SCOPES.contains(scope))
        {
            errors.put("ext", "只有所屬範圍為 RISK_RANK、RISK_GAME、MONITOR_COND 的指標才有頁面屬性");
            return null;
        }
        String api = str(out.get("apiCode"));
        if (api == null || !API_CODE.matcher(api).matches())
        {
            errors.put("ext.apiCode", "請填寫接口編碼：小寫字母開頭，只能用英文字母與數字，最長 32 個字元");
        }
        switch (scope)
        {
            case SCOPE_RISK_RANK -> validateRank(out, errors);
            case SCOPE_RISK_GAME -> validateGame(out, errors);
            default -> validateCond(out, errors);
        }
        return out;
    }

    private static void validateRank(Map<String, Object> m, Map<String, String> errors)
    {
        upper(m, "group", RANK_GROUPS, true, "ext.group", "分組只能是 FUND、GAME、ACCOUNT、HISTORY", errors);
        upper(m, "source", RANK_SOURCES, true, "ext.source", "來源只能是 USER_DAY、USER_GAME、SNAPSHOT、DIM、CALC", errors);
        upper(m, "kind", KINDS, false, "ext.kind", "類型只能是 NUMBER、TEXT、TIME", errors);
        upper(m, "agg", AGGS, false, "ext.agg", "匯總方式只能是 SUM、MAX、MIN、LAST、FOLLOW、ARGMAX", errors);
        String agg = str(m.get("agg"));
        if ("FOLLOW".equals(agg) && str(m.get("follow")) == null)
        {
            errors.put("ext.follow", "匯總方式為 FOLLOW 時要填跟隨哪一列");
        }
        if ("ARGMAX".equals(agg) && str(m.get("argOf")) == null)
        {
            errors.put("ext.argOf", "匯總方式為 ARGMAX 時要填按哪一列匯總");
        }
        if ("CALC".equals(str(m.get("source"))))
        {
            calc(m, errors);
        }
        String dir = str(m.get("riskDir"));
        if (dir != null && !"asc".equalsIgnoreCase(dir) && !"desc".equalsIgnoreCase(dir))
        {
            errors.put("ext.riskDir", "風險方向只能是 asc 或 desc");
        }
        else if (dir != null)
        {
            m.put("riskDir", dir.toLowerCase(Locale.ROOT));
        }
        String cond = str(m.get("condition"));
        if (cond != null && !"RANGE".equalsIgnoreCase(cond) && !"SELECT".equalsIgnoreCase(cond))
        {
            errors.put("ext.condition", "名單條件只能是 RANGE 或 SELECT");
        }
        else if (cond != null)
        {
            m.put("condition", cond.toUpperCase(Locale.ROOT));
        }
        bool(m, "rankable", errors);
        bool(m, "column", errors);
        bool(m, "common", errors);
        bool(m, "positive", errors);
    }

    private static void validateGame(Map<String, Object> m, Map<String, String> errors)
    {
        upper(m, "group", GAME_GROUPS, true, "ext.group", "分組只能是 BET、WINLOSS、USER、SPLIT", errors);
        bool(m, "standard", errors);
        if (m.get("calc") != null)
        {
            calc(m, errors);
        }
    }

    private static void validateCond(Map<String, Object> m, Map<String, String> errors)
    {
        Object dims = m.get("dims");
        if (dims != null)
        {
            if (!(dims instanceof Collection<?> c))
            {
                errors.put("ext.dims", "支持的維度要是陣列，如 [\"ch\",\"term\"]");
            }
            else
            {
                List<String> norm = new ArrayList<>();
                for (Object o : c)
                {
                    String d = str(o);
                    if (d == null || !COND_DIMS.contains(d))
                    {
                        errors.put("ext.dims", "維度只能是 " + String.join("、", COND_DIMS));
                        break;
                    }
                    if (!norm.contains(d))
                    {
                        norm.add(d);
                    }
                }
                m.put("dims", norm);
            }
        }
        bool(m, "snapshot", errors);
        upper(m, "defaultHow", HOWS, false, "ext.defaultHow", "取值方式只能是 CUR、BASE、YD", errors);
        upper(m, "defaultOp", OPS, false, "ext.defaultOp", "比較只能是 GT、GE、LT、LE、OUT、UP、DOWN、CHG", errors);
        Object ratio = m.get("ratio");
        if (ratio != null && (!(ratio instanceof Map<?, ?> r) || str(r.get("numerator")) == null || str(r.get("denominator")) == null))
        {
            errors.put("ext.ratio", "比率字段要填 numerator（分子字段）與 denominator（分母字段）");
        }
        Object sample = m.get("sample");
        if (sample != null && (!(sample instanceof Map<?, ?> s) || str(s.get("field")) == null || str(s.get("name")) == null))
        {
            errors.put("ext.sample", "最小樣本量要填 name（名稱）與 field（取數字段）");
        }
    }

    private static void calc(Map<String, Object> m, Map<String, String> errors)
    {
        Object c = m.get("calc");
        if (!(c instanceof Map<?, ?> calc))
        {
            errors.put("ext.calc", "計算公式要是物件，如 {\"op\":\"DIVIDE\",\"left\":\"bet\",\"right\":\"dep\"}");
            return;
        }
        String op = str(calc.get("op"));
        if (op == null || !CALC_OPS.contains(op.toUpperCase(Locale.ROOT)))
        {
            errors.put("ext.calc", "計算方式只能是 DIVIDE、SUBTRACT、ADD、DAYS_SINCE");
            return;
        }
        if (str(calc.get("left")) == null || (!"DAYS_SINCE".equalsIgnoreCase(op) && str(calc.get("right")) == null))
        {
            errors.put("ext.calc", "計算公式要填 left 與 right（DAYS_SINCE 只填 left）");
        }
    }

    private static void upper(Map<String, Object> m, String key, Set<String> allowed, boolean required, String errKey,
        String msg, Map<String, String> errors)
    {
        String v = str(m.get(key));
        if (v == null)
        {
            if (required)
            {
                errors.put(errKey, msg);
            }
            return;
        }
        String u = v.toUpperCase(Locale.ROOT);
        if (!allowed.contains(u))
        {
            errors.put(errKey, msg);
            return;
        }
        m.put(key, u);
    }

    private static void bool(Map<String, Object> m, String key, Map<String, String> errors)
    {
        Object v = m.get(key);
        if (v != null && !(v instanceof Boolean))
        {
            errors.put("ext." + key, key + " 要是 true 或 false");
        }
    }

    public static String str(Object o)
    {
        if (o == null)
        {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
