package com.fivetech.promo.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.vo.MemberRecordVO;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.dashboard.gateway.uds.UdsRow;
import com.fivetech.promo.analysis.market.MarketGateway;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 用户明细（FY-55）· 终端与推广筛选：可选项与请求参数的校验、换算。
 *
 * <ul>
 *   <li>terminals：注册终端编码（终端字典），可多选；unidentified = 未識別；</li>
 *   <li>agents / channels：推广商 / 推广渠道编码，取并集；推广商由后端换算成它的推广渠道列表；</li>
 *   <li>按注册时的归属筛选，下推为 UDS 的 IN 条件：{@link #TERMINAL_FILTER}、{@link #CHANNEL_FILTER}。
 *       注册终端取用户快照的 terminal（application.yml 的 details.member.fields.registerTerminal，取值换算见 value-maps）；
 *       注册推广渠道还没有字段（用户快照 channel 能否对应推广渠道 ID 待数仓确认）。没有映射时带了筛选直接报错，
 *       不静默忽略（忽略会返回一张看似正常、其实没筛的表）。</li>
 *   <li>流失用户（{@link #lostFilter}）：请求 lost = true 时只看注册后还没首存的用户，UDS 字段待数据平台提供（filter-fields.lost 留空时报错）。</li>
 *   <li>最近登录时间：用户快照没有，{@link #fillLastLogin} 按本页用户 ID 从市场会员快照补（《推广分析 · 指标与 UDS 数据集字段映射》第 5 节）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class MemberPromoFilter
{
    private static final Logger log = LoggerFactory.getLogger(MemberPromoFilter.class);

    /** 筛选编码：注册终端（details.member.filter-fields / fields 里映射 UDS 字段） */
    public static final String TERMINAL_FILTER = "registerTerminal";

    /** 筛选编码：注册时的推广渠道 ID */
    public static final String CHANNEL_FILTER = "registerChannel";

    /** 筛选编码：流失用户（注册后截至目前还没首存）；UDS 字段与取值在 details.member.filter-fields.lost / value-maps.lost */
    public static final String LOST_FILTER = "lost";

    private final DashboardProperties properties;

    private final PromoChannelDictionary channels;

    private final PromoTerminalDictionary terminals;

    private final UdsProperties uds;

    /** 市场会员快照（补最近登录时间）；没注入时（单测）不补 */
    private MarketGateway market;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMarket(MarketGateway market)
    {
        this.market = market;
    }

    public MemberPromoFilter(DashboardProperties properties, PromoChannelDictionary channels, PromoTerminalDictionary terminals,
        UdsProperties uds)
    {
        this.properties = properties;
        this.channels = channels;
        this.terminals = terminals;
        this.uds = uds;
    }

    /** GET /dashboard/records/member/options */
    public Map<String, Object> options()
    {
        String site = properties.currentSite();
        List<Map<String, Object>> ts = new ArrayList<>();
        for (PromoTerminalDictionary.Terminal t : terminals.of(site).getTerminals())
        {
            ts.add(PromoAnalysisService.codeName(t.getCode(), t.getName()));
        }
        ts.add(PromoAnalysisService.codeName(PromoTerminalDictionary.UNIDENTIFIED, PromoTerminalDictionary.UNIDENTIFIED_NAME));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terminals", ts);
        out.put("agents", PromoAnalysisService.agents(channels.promotersOf(site), false));
        // 当前站点是否实时站点（站点管理里设置），与指标汇总 summary 的 realtime 相同
        out.put("realtime", properties.isRealtime(site));
        return out;
    }

    /**
     * 校验并换算：返回要放进明细请求 filters 的条件（编码 → 取值列表）；都为空返回空 map。
     * 不认识的编码 4004；数据集没有映射字段时报错。
     */
    public Map<String, Object> filters(List<String> terminalCodes, List<String> agentCodes, List<String> channelCodes)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        String site = properties.currentSite();
        List<String> ts = nonBlank(terminalCodes);
        if (!ts.isEmpty())
        {
            PromoTerminalDictionary.Config cfg = terminals.of(site);
            Set<String> values = new LinkedHashSet<>();
            for (String t : ts)
            {
                String k = t.toLowerCase(Locale.ROOT);
                if (!PromoTerminalDictionary.UNIDENTIFIED.equals(k) && cfg.find(k) == null)
                {
                    throw new ServiceException("不認識的終端：" + t, PromoErrors.UNKNOWN_FILTER);
                }
                values.add(k);
            }
            requireMapped(TERMINAL_FILTER, "註冊終端");
            out.put(TERMINAL_FILTER, new ArrayList<>(values));
        }
        if (!nonBlank(agentCodes).isEmpty() || !nonBlank(channelCodes).isEmpty())
        {
            PromoScope scope = PromoScope.resolve(channels.promotersOf(site), agentCodes, channelCodes, null, null);
            List<String> ids = new ArrayList<>();
            scope.getChannels().forEach(c -> ids.add(c.getId()));
            requireMapped(CHANNEL_FILTER, "註冊推廣渠道");
            out.put(CHANNEL_FILTER, ids);
        }
        return out;
    }

    /**
     * 流失用户：lost = true 时返回 {lost: "true"}，网关按 value-maps.lost 换成 UDS 取值下推为等于条件；
     * 为空 / false 返回空 map（原逻辑）。UDS 字段没配时直接报错，不静默忽略。
     */
    public Map<String, Object> lostFilter(Boolean lost)
    {
        if (!Boolean.TRUE.equals(lost))
        {
            return new LinkedHashMap<>();
        }
        requireMapped(LOST_FILTER, "流失");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(LOST_FILTER, "true");
        return out;
    }

    /**
     * 最近登录时间：按本页的用户 ID 一次查市场会员快照（只含 2026-07-26 起注册的会员），查不到的为 null。
     * 会员快照查不到或查询失败只记日志，不影响明细本身。
     */
    public void fillLastLogin(List<MemberRecordVO> rows)
    {
        if (market == null || !market.isEnabled() || rows == null || rows.isEmpty())
        {
            return;
        }
        Set<Object> ids = new LinkedHashSet<>();
        for (MemberRecordVO r : rows)
        {
            String id = r.getUserId() == null ? null : r.getUserId().trim();
            if (id != null && !id.isEmpty())
            {
                ids.add(id.matches("\\d{1,18}") ? (Object) Long.valueOf(id) : id);
            }
        }
        if (ids.isEmpty())
        {
            return;
        }
        try
        {
            List<UdsRow> found = market.members(List.of(MarketGateway.cond("player_id", "IN", new ArrayList<>(ids))),
                List.of("player_id", "last_login_time"), List.of(), ids.size(), 0);
            Map<String, String> byId = new HashMap<>();
            for (UdsRow row : found)
            {
                byId.put(row.str("player_id"), row.str("last_login_time"));
            }
            rows.forEach(r -> r.setLastLoginTime(r.getUserId() == null ? null : byId.get(r.getUserId().trim())));
        }
        catch (RuntimeException e)
        {
            log.warn("[member] 最近登录时间（市场会员快照）读取失败，本页不显示：{}", e.getMessage());
        }
    }

    private void requireMapped(String code, String label)
    {
        UdsProperties.DetailDataset d = uds.getDetails().get("member");
        boolean mapped = d != null && (StringUtils.isNotEmpty(d.getFilterFields().get(code)) || StringUtils.isNotEmpty(d.getFields().get(code)));
        if (!mapped)
        {
            throw new ServiceException("用戶明細的數據還沒有「" + label + "」字段（數據平台待提供），暫時不能按它篩選", PromoErrors.DATA_FAILED);
        }
    }

    private static List<String> nonBlank(List<String> codes)
    {
        List<String> out = new ArrayList<>();
        if (codes != null)
        {
            codes.stream().filter(c -> c != null && !c.trim().isEmpty()).forEach(c -> out.add(c.trim()));
        }
        return out;
    }
}
