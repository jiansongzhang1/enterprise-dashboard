package com.fivetech.promo.analysis.market;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.promo.analysis.PromoTime;
import tools.jackson.databind.ObjectMapper;

/**
 * 裂变层级的 UDS 查询（{@code dashboard_market_fission_v1}，对接手册 2026-11-17 第二节）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>节点主键是复合键 <b>(merchant_code, agent_id)</b>。页面上没有商户这一项，下钻、定位只按
 *       agent_id；站点下有多个商户时必须配 {@code promo.market.merchant-code} 固定住；</li>
 *   <li>时间维度固定 {@code uds_hh}、{@code granularity=DAY}，起止传站点业务时区的
 *       {@code 00:00:00} / {@code 23:59:59}（末日含在内）；{@code uds_hh} 只能放在 {@code time.dimension},
 *       不能进 dimensions / filters / orderBy；</li>
 *   <li>{@code node_*} 不能与旧族指标（{@code reg}、{@code ftd_players} 等）在同一个请求里混查；</li>
 *   <li>总数要单独聚合查 {@code node_count}，不能用当前页行数；</li>
 *   <li>数据集或指标被 omit（九站目前是 {@code UNSUPPORTED/PENDING_SOURCE}）时必须当成「未接通」，
 *       不能把空结果显示成 0。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class FissionGateway
{
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 列表行的维度（节点本身的属性） */
    public static final List<String> DIMENSIONS = List.of("merchant_code", "agent_id", "agent_login", "parent_agent_id",
        "agent_parent", "root_agent_id", "agent_level", "children_count", "ancestor_ids", "ancestor_logins", "depth_overflow");

    /** 列表行的指标 */
    public static final List<String> METRICS = List.of("node_new_sub", "node_first_deposit_users", "node_deposit_users",
        "node_withdraw_users", "node_deposit_amount", "node_withdraw_amount", "node_bet_amount", "node_member_win_loss",
        "node_count");

    /** 顶卡指标（不带一级列表过滤） */
    public static final List<String> SUMMARY_METRICS = List.of("node_fission_registered_users",
        "node_fission_registered_first_users", "node_registered_first_rate", "node_direct_registered_users",
        "node_direct_registered_rate");

    /** 页面排序列 → UDS 字段 */
    private static final Map<String, String> SORT_FIELDS = Map.of(
        "directCount", "children_count",
        "newSub", "node_new_sub",
        "ftd", "node_first_deposit_users",
        "depU", "node_deposit_users",
        "wdU", "node_withdraw_users",
        "depAmt", "node_deposit_amount",
        "betAmt", "node_bet_amount",
        "winLoss", "node_member_win_loss");

    private final UdsClient client;

    private final MarketProperties props;

    private final ObjectMapper objectMapper;

    public FissionGateway(UdsClient client, MarketProperties props, ObjectMapper objectMapper)
    {
        this.client = client;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled()
    {
        return props.isEnabled() && StringUtils.isNotEmpty(props.getFissionDataset());
    }

    /** 排序列对应的 UDS 字段；不认识的列返回 null（调用方已经先校验过列名） */
    public static String sortField(String column)
    {
        return SORT_FIELDS.get(column);
    }

    /** 一级列表过滤：层级 = 1 且有直属下级 */
    public static List<Map<String, Object>> topFilters()
    {
        List<Map<String, Object>> f = new ArrayList<>();
        f.add(MarketGateway.cond("agent_level", "EQ", 1));
        f.add(MarketGateway.cond("children_count", "GT", 0));
        return f;
    }

    /**
     * 下钻：某个上级的直属下级。
     * <p>手册要求同时带 merchant_code（节点主键是 merchant_code + agent_id），但页面上没有商户这一项，
     * 所以这里只按 parent_agent_id 过滤。站点下有多个商户时要配 {@code promo.market.merchant-code}
     * 固定住，由 {@link #body} 统一加上，否则同名玩家号可能串到别的商户。</p>
     */
    public static List<Map<String, Object>> childFilters(String parentAgentId)
    {
        List<Map<String, Object>> f = new ArrayList<>();
        f.add(MarketGateway.cond("parent_agent_id", "EQ", parentAgentId));
        return f;
    }

    /** 精确定位：按玩家号定位节点（商户同上） */
    public static List<Map<String, Object>> nodeFilters(String agentId)
    {
        List<Map<String, Object>> f = new ArrayList<>();
        f.add(MarketGateway.cond("agent_id", "EQ", agentId));
        return f;
    }

    /** 节点列表（带维度于全部指标） */
    public UdsQueryResult nodes(PromoTime.Range range, List<Map<String, Object>> filters, String sortColumn, boolean asc,
        int limit, int offset)
    {
        List<Map<String, Object>> order = new ArrayList<>();
        String field = sortField(sortColumn);
        if (field != null)
        {
            order.add(MarketGateway.order(field, asc ? "ASC" : "DESC"));
        }
        // 复合主键做次级排序，保证翻页稳定
        order.add(MarketGateway.order("merchant_code", "ASC"));
        order.add(MarketGateway.order("agent_id", "ASC"));
        Map<String, Object> body = body(range, DIMENSIONS, METRICS, filters, order, limit, offset);
        return client.queryWithMeta(body);
    }

    /**
     * 聚合查询：{@code dimensions:[]}、只要指标，返回一条聚合行。
     * 一级总数传 {@link #topFilters()} + {@code node_count}；顶卡不带过滤。
     */
    public UdsQueryResult aggregate(PromoTime.Range range, List<Map<String, Object>> filters, List<String> metrics)
    {
        Map<String, Object> body = body(range, List.of(), metrics, filters, List.of(), 1, 0);
        return client.queryWithMeta(body);
    }

    /** 请求体；包内可见供测试 */
    Map<String, Object> body(PromoTime.Range range, List<String> dimensions, List<String> metrics,
        List<Map<String, Object>> filters, List<Map<String, Object>> orderBy, int limit, int offset)
    {
        Map<String, Object> time = new LinkedHashMap<>();
        // uds_hh 只放这里：进 dimensions / filters / orderBy 会被平台拒绝
        time.put("dimension", "uds_hh");
        time.put("granularity", "DAY");
        time.put("from", range.firstDay().atStartOfDay().format(DATETIME));
        time.put("to", range.lastDay().atTime(23, 59, 59).format(DATETIME));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", props.getFissionDataset());
        body.put("dimensions", new ArrayList<>(dimensions));
        body.put("metrics", new ArrayList<>(metrics));
        body.put("time", time);
        List<Map<String, Object>> all = new ArrayList<>();
        if (StringUtils.isNotEmpty(props.getMerchantCode()))
        {
            all.add(MarketGateway.cond("merchant_code", "EQ", props.getMerchantCode().trim()));
        }
        if (filters != null)
        {
            all.addAll(filters);
        }
        if (!all.isEmpty())
        {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("and", all);
            body.put("filters", f);
        }
        body.put("orderBy", new ArrayList<>(orderBy));
        body.put("limit", Math.max(1, Math.min(props.getPageSize(), limit)));
        body.put("offset", Math.max(0, offset));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("channel", "ONLINE");
        body.put("options", options);
        return body;
    }

    /**
     * {@code ancestor_ids} / {@code ancestor_logins} 是 JSON <b>字符串</b>，要先解析再用。
     * 解析不了就返回空列表（调用方据此不渲染面包屑），不抛异常。
     */
    public List<String> jsonArray(String text)
    {
        if (text == null || text.isBlank())
        {
            return List.of();
        }
        try
        {
            List<String> out = new ArrayList<>();
            for (Object o : objectMapper.readValue(text, List.class))
            {
                out.add(o == null ? null : String.valueOf(o));
            }
            return out;
        }
        catch (RuntimeException e)
        {
            return List.of();
        }
    }

    /** 数据集或指标被 omit = 该站点这个数据集没接通，不能把空结果当 0 */
    public static boolean pending(UdsQueryResult r)
    {
        return r == null || r.isDatasetOmitted()
            || (r.getOmittedMetrics() != null && !r.getOmittedMetrics().isEmpty());
    }
}
