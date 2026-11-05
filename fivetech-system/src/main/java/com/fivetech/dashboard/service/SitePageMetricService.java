package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.domain.MetricDefinition;
import com.fivetech.dashboard.domain.MetricGroupConfig;
import com.fivetech.dashboard.domain.PageMetricLayout;
import com.fivetech.dashboard.domain.SitePageMetric;
import com.fivetech.dashboard.enums.DashboardPage;
import com.fivetech.dashboard.enums.PageDisplayType;
import com.fivetech.dashboard.mapper.SitePageMetricMapper;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 系统管理 · 站点管理 · 指標配置：按站点调整运营总览（OVERVIEW）、指标汇总（SUMMARY）上指标的顺序与显示
 * （dashboard_page_metric_site）。每个站点页面有两套指标集（displayType）：DEFAULT 默认（页面取数用这一套）、
 * REPORT 报表（目前只做配置，不影响取数），各自独立保存、恢复默认。保存后当场重载 {@link MetricRegistry}，其他实例按 dashboard.config-refresh-seconds 跟上。
 *
 * <ul>
 *   <li>没有配置的站点 = 全站默认（dashboard_page_metric）的顺序、全部显示；</li>
 *   <li>保存时传完整顺序：items 的先后即顺序；漏传的指标按全站默认排在后面、显示；</li>
 *   <li>至少显示 1 个；指标必须挂在该页面上（全站默认里有）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class SitePageMetricService
{
    private final SitePageMetricMapper mapper;

    private final MetricRegistry registry;

    private final DashboardProperties dashboard;

    public SitePageMetricService(SitePageMetricMapper mapper, MetricRegistry registry, DashboardProperties dashboard)
    {
        this.mapper = mapper;
        this.registry = registry;
        this.dashboard = dashboard;
    }

    /**
     * 某站点某页面默认指标集（DEFAULT）的排列，见 {@link #get(String, String, String)}。
     */
    public Map<String, Object> get(String siteCode, String pageCode)
    {
        return get(siteCode, pageCode, null);
    }

    /**
     * 某站点某页面某指标集的排列（含隐藏的指标），供管理后台拖动排序。
     *
     * @param displayType DEFAULT（不传）/ REPORT
     * @return { siteCode, page, displayType, customized, items: [{ metricCode, metricName, groupCode, groupName, core, defaultSortNo, sortNo, visible }] }
     */
    public Map<String, Object> get(String siteCode, String pageCode, String displayType)
    {
        String site = site(siteCode);
        DashboardPage page = page(pageCode);
        PageDisplayType type = displayType(displayType);
        Set<String> core = registry.coreMetricCodes();
        List<Map<String, Object>> items = new ArrayList<>();
        for (PageMetricLayout l : registry.layoutOf(page, site, type))
        {
            MetricDefinition def = registry.get(l.getMetricCode());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metricCode", l.getMetricCode());
            m.put("metricName", def == null ? l.getMetricCode() : OverviewMetricText.label(l.getMetricCode(), def.getLabel()));
            m.put("groupCode", def == null ? null : def.getGroup());
            MetricGroupConfig g = def == null ? null : registry.groupOf(def.getGroup());
            m.put("groupName", g == null ? null : OverviewMetricText.group(g.getGroupName()));
            m.put("core", core.contains(l.getMetricCode()));
            m.put("defaultSortNo", l.getDefaultSortNo());
            m.put("sortNo", l.getPosition());
            m.put("visible", l.isVisible());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteCode", site);
        out.put("page", page.name());
        out.put("displayType", type.name());
        out.put("customized", registry.customized(page, site, type));
        out.put("items", items);
        return out;
    }

    /** 保存默认指标集（DEFAULT），见 {@link #save(String, String, String, List, String)} */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(String siteCode, String pageCode, List<Map<String, Object>> items, String operator)
    {
        return save(siteCode, pageCode, null, items, operator);
    }

    /**
     * 保存某站点某页面某指标集的顺序与显示（整体替换，只动这一套）。
     *
     * @param displayType DEFAULT（不传）/ REPORT
     * @param items 按顺序排列的 { metricCode, visible }；visible 不传按显示
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(String siteCode, String pageCode, String displayType, List<Map<String, Object>> items,
            String operator)
    {
        String site = site(siteCode);
        DashboardPage page = page(pageCode);
        PageDisplayType type = displayType(displayType);
        List<String> base = registry.columnsOf(page);
        Map<String, String> errors = new LinkedHashMap<>();
        if (items == null || items.isEmpty())
        {
            errors.put("items", "請傳入指標順序");
            throw new MonitorFieldException(errors);
        }
        List<SitePageMetric> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        List<String> dup = new ArrayList<>();
        for (Map<String, Object> it : items)
        {
            String code = it == null || it.get("metricCode") == null ? null : String.valueOf(it.get("metricCode")).trim();
            if (StringUtils.isEmpty(code) || !base.contains(code))
            {
                unknown.add(StringUtils.isEmpty(code) ? "（空）" : code);
                continue;
            }
            if (!seen.add(code))
            {
                dup.add(code);
                continue;
            }
            Object v = it.get("visible");
            SitePageMetric r = new SitePageMetric();
            r.setSiteCode(site);
            r.setPageCode(page.name());
            r.setDisplayType(type.name());
            r.setMetricCode(code);
            r.setVisible(v == null || Boolean.parseBoolean(String.valueOf(v)));
            r.setUpdateBy(StringUtils.nvl(operator, ""));
            rows.add(r);
        }
        if (!unknown.isEmpty())
        {
            errors.put("items", "不在" + label(page) + "上的指標：" + String.join("、", unknown));
        }
        else if (!dup.isEmpty())
        {
            errors.put("items", "指標重複：" + String.join("、", dup));
        }
        // 漏传的指标：按全站默认顺序排在后面、显示
        for (String code : base)
        {
            if (!seen.contains(code))
            {
                SitePageMetric r = new SitePageMetric();
                r.setSiteCode(site);
                r.setPageCode(page.name());
                r.setDisplayType(type.name());
                r.setMetricCode(code);
                r.setVisible(true);
                r.setUpdateBy(StringUtils.nvl(operator, ""));
                rows.add(r);
            }
        }
        if (errors.isEmpty() && rows.stream().noneMatch(SitePageMetric::isVisible))
        {
            errors.put("items", "至少顯示 1 個指標");
        }
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        for (int i = 0; i < rows.size(); i++)
        {
            rows.get(i).setSortNo((i + 1) * 10);
        }
        mapper.deleteBySitePage(site, page.name(), type.name());
        mapper.insertBatch(rows);
        reload();
        return get(site, page.name(), type.name());
    }

    /** 默认指标集（DEFAULT）恢复全站默认 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reset(String siteCode, String pageCode)
    {
        return reset(siteCode, pageCode, null);
    }

    /** 恢复全站默认：删除这个站点该页面该指标集的配置（另一套不动） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reset(String siteCode, String pageCode, String displayType)
    {
        String site = site(siteCode);
        DashboardPage page = page(pageCode);
        PageDisplayType type = displayType(displayType);
        mapper.deleteBySitePage(site, page.name(), type.name());
        reload();
        return get(site, page.name(), type.name());
    }

    private static PageDisplayType displayType(String displayType)
    {
        PageDisplayType t = PageDisplayType.of(displayType);
        if (t == null)
        {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put("displayType", "指標集只能是 DEFAULT（預設）或 REPORT（報表）");
            throw new MonitorFieldException(errors);
        }
        return t;
    }

    private void reload()
    {
        try
        {
            registry.reload();
        }
        catch (RuntimeException e)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "保存失敗，指標配置重新加載不通過：" + e.getMessage());
        }
    }

    /** 站点必须是已配置的站点；返回规范写法（大小写与站点表一致） */
    private String site(String siteCode)
    {
        String code = dashboard.canonical(StringUtils.trimToEmpty(siteCode));
        if (code == null)
        {
            throw MonitorException.notFound("站點不存在");
        }
        return code;
    }

    private static DashboardPage page(String pageCode)
    {
        String p = StringUtils.trimToEmpty(pageCode).toUpperCase(java.util.Locale.ROOT);
        if (p.isEmpty())
        {
            return DashboardPage.OVERVIEW;
        }
        for (DashboardPage d : DashboardPage.values())
        {
            // 推广分析的指标还是「仅登记」、不进注册表，暂不支持按站点调整
            if (d != DashboardPage.PROMO && d.name().equals(p))
            {
                return d;
            }
        }
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("page", "頁面只能是 OVERVIEW（運營總覽）或 SUMMARY（指標匯總）");
        throw new MonitorFieldException(errors);
    }

    private static String label(DashboardPage page)
    {
        return page == DashboardPage.OVERVIEW ? "運營總覽" : "指標匯總";
    }
}
