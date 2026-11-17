package com.fivetech.risk;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.view.ViewItemSource;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.risk.catalog.RiskCatalog;
import com.fivetech.risk.catalog.RiskColumn;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.config.RiskConfigService;
import com.fivetech.risk.game.RiskGameService;

/**
 * 风控分析的个人视图配置位：可选项的名称与是否还在，跟着指标管理（指标、列）和配置中心（榜单）走。
 * 指标管理里停用的列、配置中心里停用的榜单，从个人视图里自动消失（用户选过的跳过，库里的记录不动）。
 *
 * <ul>
 *   <li>risk.overview.cards：风控概览的核心指标（指标管理 · RISK 页面）</li>
 *   <li>risk.rank.cards：显示为卡片的系统榜单（我的榜单是否显示为卡片存在 risk_my_board.show_card）；占比档位存在它的 attrs.bands</li>
 *   <li>risk.rank.cols.{榜单}：每张榜单的列设置（指标管理 · RISK_RANK 列）；我的榜单共用 risk.rank.cols.my，
 *       渠道风险榜为 risk.rank.cols.chrisk（channelCols，「各風險類型人數」的编码为 riskTypeCounts，註冊用戶固定不在可选项里）</li>
 *   <li>risk.game.path / risk.game.cols：游戏分析的行维度、列设置（指标管理 · RISK_GAME 列）</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class RiskViewItemSource implements ViewItemSource
{
    public static final String OVERVIEW_CARDS = "risk.overview.cards";

    public static final String RANK_CARDS = "risk.rank.cards";

    public static final String RANK_COLS = "risk.rank.cols.";

    /** 我的榜单共用一个列设置（默认同全部用户）；我的榜单本身不含列设置（FY-68 R1） */
    public static final String RANK_COLS_MY = "risk.rank.cols.my";

    /** 渠道风险榜的个人风险类型（FY-71 R2）；没设过 = 跟随全局默认 */
    public static final String RANK_RISK_TYPES = "risk.rank.riskTypes";

    public static final String GAME_PATH = "risk.game.path";

    public static final String GAME_COLS = "risk.game.cols";

    private final MetricRegistry registry;

    private final RiskCatalog catalog;

    private final RiskConfigService config;

    public RiskViewItemSource(MetricRegistry registry, RiskCatalog catalog, RiskConfigService config)
    {
        this.registry = registry;
        this.catalog = catalog;
        this.config = config;
    }

    @Override
    public boolean supports(ViewSlot slot)
    {
        String c = slot.getSlotCode();
        return OVERVIEW_CARDS.equals(c) || RANK_CARDS.equals(c) || GAME_PATH.equals(c) || GAME_COLS.equals(c) || RANK_RISK_TYPES.equals(c)
            || (c != null && c.startsWith(RANK_COLS));
    }

    @Override
    public Map<String, ItemInfo> items(ViewSlot slot, String site)
    {
        Map<String, ItemInfo> out = new LinkedHashMap<>();
        String c = slot.getSlotCode();
        if (OVERVIEW_CARDS.equals(c))
        {
            for (String code : registry.columnsOf(DashboardPage.RISK, site))
            {
                MetricDefinition d = registry.get(code);
                out.put(code, ItemInfo.of(d == null ? code : d.getLabel()));
            }
        }
        else if (RANK_CARDS.equals(c))
        {
            RiskBoards b = config.boards(site);
            for (RiskBoards.Board x : b.boards)
            {
                if (x.enabled && x.card)
                {
                    out.put(x.code, ItemInfo.of(x.name));
                }
            }
        }
        else if (RANK_RISK_TYPES.equals(c))
        {
            for (RiskBoards.RiskType t : config.boards(site).riskTypes)
            {
                out.put(t.code, ItemInfo.of(t.name));
            }
        }
        else if (c.startsWith(RANK_COLS) && config.boards(site).board(c.substring(RANK_COLS.length())) != null
            && config.boards(site).board(c.substring(RANK_COLS.length())).isChannel())
        {
            // 渠道风险榜的列设置：channelCols（註冊用戶固定，不在可选项里）
            for (RiskBoards.ChannelCol col : config.boards(site).board(c.substring(RANK_COLS.length())).channelCols)
            {
                if (!col.fixed)
                {
                    out.put(col.code, ItemInfo.of(col.name));
                }
            }
        }
        else if (GAME_PATH.equals(c))
        {
            for (String d : RiskGameService.DIMS)
            {
                out.put(d, ItemInfo.of(null));
            }
        }
        else if (GAME_COLS.equals(c))
        {
            for (RiskColumn col : catalog.game().values())
            {
                out.put(col.getCode(), ItemInfo.of(col.getName()));
            }
        }
        else
        {
            for (RiskColumn col : catalog.rank().values())
            {
                if (col.isColumn())
                {
                    out.put(col.getCode(), ItemInfo.of(col.getName()));
                }
            }
        }
        return out;
    }
}
