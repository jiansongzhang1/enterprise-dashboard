package com.fivetech.promo.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.view.ViewItemSource;
import com.fivetech.dashboard.view.domain.ViewSlot;

/**
 * 推广分析的 3 个配置位（个人自定义指标集一期）：项编码与取数接口一致。
 *
 * <ul>
 *   <li>{@link #CARDS} 概览 · 核心指标卡：概览可选的指标（{@link PromoMetricCatalog#overviewMetrics()}，指标管理里停用或从页面摘掉的不再出现），
 *       名称取指标字典；对应概览请求的 {@code metrics}</li>
 *   <li>{@link #COLUMNS} 渠道表现 · 表格列：{@link PromoMetricCatalog#COLUMNS}；对应 {@code /channels/table|export} 的 {@code columns}</li>
 *   <li>{@link #ROWS} 渠道表现 · 行维度：agent / channel / date / terminal（relation 2026-11-12 去掉）；对应 {@code rows}</li>
 * </ul>
 *
 * <p>UDS 按站点不提供的指标（本站不可用）待数据平台给出按站点的数据集清单后在这里标 unavailable，一期全部可用。</p>
 *
 * @author fivetech
 */
@Component
public class PromoViewItemSource implements ViewItemSource
{
    public static final String CARDS = "promo.overview.cards";

    public static final String COLUMNS = "promo.channel.columns";

    public static final String ROWS = "promo.channel.rows";

    static final Set<String> SLOTS = Set.of(CARDS, COLUMNS, ROWS);

    private final PromoMetricCatalog catalog;

    public PromoViewItemSource(PromoMetricCatalog catalog)
    {
        this.catalog = catalog;
    }

    @Override
    public boolean supports(ViewSlot slot)
    {
        return SLOTS.contains(slot.getSlotCode());
    }

    @Override
    public Map<String, ItemInfo> items(ViewSlot slot, String site)
    {
        Map<String, ItemInfo> out = new LinkedHashMap<>();
        switch (slot.getSlotCode())
        {
            case CARDS:
                for (PromoMetricCatalog.Metric m : catalog.overviewMetrics())
                {
                    out.put(m.getCode(), ItemInfo.of(m.getName()));
                }
                break;
            case COLUMNS:
                for (PromoMetricCatalog.Column c : PromoMetricCatalog.COLUMNS)
                {
                    out.put(c.getCode(), ItemInfo.of(c.getName()));
                }
                break;
            default:
                ChannelTree.DIM_NAMES.forEach((k, v) -> out.put(k, ItemInfo.of(v)));
                break;
        }
        return out;
    }
}
