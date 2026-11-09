package com.fivetech.dashboard.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.PageMetricLayout;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.service.MetricRegistry;
import com.fivetech.dashboard.view.domain.ViewSlot;

/**
 * 叠加站点排列的配置位（slot.site_layout_page 不为空，如以后的运营总览卡片）：
 * 项 = 该页面挂的指标（指标注册表），站点里隐藏的为本站不可用；站点有自定义排列时，它就是站点默认。
 *
 * @author fivetech
 */
@Component
public class SiteLayoutViewItemSource implements ViewItemSource
{
    private final MetricRegistry registry;

    public SiteLayoutViewItemSource(MetricRegistry registry)
    {
        this.registry = registry;
    }

    static DashboardPage page(ViewSlot slot)
    {
        String p = slot.getSiteLayoutPage();
        if (p == null || p.trim().isEmpty())
        {
            return null;
        }
        try
        {
            return DashboardPage.valueOf(p.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    @Override
    public boolean supports(ViewSlot slot)
    {
        return page(slot) != null;
    }

    @Override
    public Map<String, ItemInfo> items(ViewSlot slot, String site)
    {
        Map<String, ItemInfo> out = new LinkedHashMap<>();
        for (PageMetricLayout l : registry.layoutOf(page(slot), site))
        {
            MetricDefinition def = registry.get(l.getMetricCode());
            String label = def == null ? l.getMetricCode() : def.getLabel();
            out.put(l.getMetricCode(), l.isVisible() ? ItemInfo.of(label) : ItemInfo.unavailable(label, "本站已隱藏"));
        }
        return out;
    }

    @Override
    public List<String> siteDefaults(ViewSlot slot, String site)
    {
        DashboardPage page = page(slot);
        return registry.customized(page, site) ? registry.columnsOf(page, site) : null;
    }
}
