package com.fivetech.promo.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.gateway.uds.UdsProperties;
import com.fivetech.promo.budget.PromoChannelDictionary;

/**
 * 用户明细（FY-55）· 终端与推广筛选：可选项与请求参数的校验、换算。
 *
 * <ul>
 *   <li>terminals：注册终端编码（终端字典），可多选；unidentified = 未識別；</li>
 *   <li>agents / channels：推广商 / 推广渠道编码，取并集；推广商由后端换算成它的推广渠道列表；</li>
 *   <li>按注册时的归属筛选，下推为 UDS 的 IN 条件：{@link #TERMINAL_FILTER}、{@link #CHANNEL_FILTER}。
 *       UDS 用户明细数据集还没有这两个字段（Q28）：application.yml 的 details.member 没有映射时，带了这些筛选直接报错，
 *       不静默忽略（忽略会返回一张看似正常、其实没筛的表）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class MemberPromoFilter
{
    /** 筛选编码：注册终端（details.member.filter-fields / fields 里映射 UDS 字段） */
    public static final String TERMINAL_FILTER = "registerTerminal";

    /** 筛选编码：注册时的推广渠道 ID */
    public static final String CHANNEL_FILTER = "registerChannel";

    private final DashboardProperties properties;

    private final PromoChannelDictionary channels;

    private final PromoTerminalDictionary terminals;

    private final UdsProperties uds;

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
