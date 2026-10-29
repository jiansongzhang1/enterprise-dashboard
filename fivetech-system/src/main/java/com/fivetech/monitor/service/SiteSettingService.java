package com.fivetech.monitor.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.MonitorSiteSetting;
import com.fivetech.monitor.domain.vo.SiteSettingVO;
import com.fivetech.monitor.mapper.MonitorSiteMapper;

/**
 * 系统管理 · 站点管理：站点来自 monitor_site_setting 里 site_name 不为空的行（经 {@link com.fivetech.dashboard.service.SiteRegistry} 加载；表不可用时为 application.yml），
 * 站点级设置（所属包网、规则号）存 monitor_site_setting。新增、修改站点见 {@link com.fivetech.dashboard.service.SiteManageService}。
 * <p>一个包网可对应多个站点，一个站点只属于一个包网：包网直接存在站点设置行上，同名（去掉首尾空白）即同一包网。</p>
 *
 * @author fivetech
 */
@Service
public class SiteSettingService
{
    /** monitor_site_setting.package_network 的长度 */
    static final int PACKAGE_NETWORK_MAX = 64;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MonitorSiteMapper siteMapper;

    private final DashboardProperties dashboard;

    public SiteSettingService(MonitorSiteMapper siteMapper, DashboardProperties dashboard)
    {
        this.siteMapper = siteMapper;
        this.dashboard = dashboard;
    }

    /**
     * 全部站点：按创建先后（排序号 sort_no 从小到大，新增站点取最大值 + 1）；数据库里有设置行、但不是站点的排在最后（configured = false）。
     *
     * @param packageNetwork 按包网筛选（精确匹配）；为空不筛选，传「-」只看未设置的
     */
    public List<SiteSettingVO> list(String packageNetwork)
    {
        Map<String, MonitorSiteSetting> rows = new LinkedHashMap<>();
        for (MonitorSiteSetting row : siteMapper.selectAllSiteSettings())
        {
            rows.put(row.getSiteCode().toUpperCase(), row);
        }
        List<SiteSettingVO> list = new ArrayList<>();
        // 按创建先后（站点表加载后为排序号从小到大）；不是站点的设置行排在最后
        List<String> codes = dashboard.allSiteCodes();
        for (String code : codes)
        {
            list.add(toVO(code, true, rows.remove(code.toUpperCase())));
        }
        for (MonitorSiteSetting orphan : rows.values())
        {
            list.add(toVO(orphan.getSiteCode(), false, orphan));
        }
        String filter = StringUtils.trim(packageNetwork);
        if (StringUtils.isEmpty(filter))
        {
            return list;
        }
        List<SiteSettingVO> filtered = new ArrayList<>();
        for (SiteSettingVO vo : list)
        {
            boolean match = "-".equals(filter) ? StringUtils.isEmpty(vo.getPackageNetwork())
                    : filter.equals(vo.getPackageNetwork());
            if (match)
            {
                filtered.add(vo);
            }
        }
        return filtered;
    }

    /** 已用过的包网名称：页面输入时做候选 */
    public List<String> packageNetworks()
    {
        return siteMapper.selectPackageNetworks();
    }

    /**
     * 设置站点所属包网。
     *
     * @param packageNetwork 为空表示清除（未设置）
     */
    public void updatePackageNetwork(String siteCode, String packageNetwork, String operator)
    {
        String code = dashboard.canonical(siteCode);
        if (code == null)
        {
            throw new ServiceException("站點不存在或未配置：" + StringUtils.nvl(siteCode, ""));
        }
        String name = normalize(packageNetwork);
        LocalDateTime now = LocalDateTime.now(ZoneId.of(dashboard.getTimezone())).truncatedTo(ChronoUnit.SECONDS);
        siteMapper.upsertPackageNetwork(new MonitorScope(code, null), name, StringUtils.nvl(operator, ""), now);
    }

    /** 去掉首尾空白、连续空白合成一个；空字符串视为清除；超长报错 */
    static String normalize(String packageNetwork)
    {
        if (packageNetwork == null)
        {
            return null;
        }
        String name = packageNetwork.trim().replaceAll("\\s+", " ");
        if (name.isEmpty())
        {
            return null;
        }
        if (name.codePointCount(0, name.length()) > PACKAGE_NETWORK_MAX)
        {
            throw new ServiceException("包網名稱不能超過 " + PACKAGE_NETWORK_MAX + " 個字");
        }
        return name;
    }

    private SiteSettingVO toVO(String code, boolean configured, MonitorSiteSetting row)
    {
        SiteSettingVO vo = new SiteSettingVO();
        vo.setSiteCode(code);
        vo.setConfigured(configured);
        vo.setDefaultSite(configured && code.equalsIgnoreCase(dashboard.getDefaultSite()));
        vo.setSiteName(configured ? dashboard.siteName(code) : code);
        vo.setUdsSite(configured ? dashboard.udsSite(code) : null);
        vo.setEffectiveTimezone(configured ? dashboard.siteTimezone(code) : null);
        vo.setEffectiveCurrency(configured ? dashboard.siteCurrency(code) : null);
        vo.setEffectiveCurrencySymbol(configured ? dashboard.siteCurrencySymbol(code) : null);
        if (configured && vo.isDefaultSite())
        {
            vo.setTimezone(StringUtils.isEmpty(dashboard.getDefaultSiteTimezone()) ? null : dashboard.getDefaultSiteTimezone().trim());
            vo.setCurrency(StringUtils.isEmpty(dashboard.getDefaultSiteCurrency()) ? null : dashboard.getDefaultSiteCurrency());
            vo.setCurrencySymbol(StringUtils.isEmpty(dashboard.getDefaultSiteCurrencySymbol()) ? null : dashboard.getDefaultSiteCurrencySymbol());
        }
        if (configured && !vo.isDefaultSite())
        {
            for (DashboardProperties.Site s : dashboard.getSites())
            {
                if (code.equalsIgnoreCase(s.getCode()))
                {
                    vo.setTimezone(StringUtils.isEmpty(s.getTimezone()) ? null : s.getTimezone().trim());
                    vo.setCurrency(StringUtils.isEmpty(s.getCurrency()) ? null : s.getCurrency().trim());
                    vo.setCurrencySymbol(StringUtils.isEmpty(s.getCurrencySymbol()) ? null : s.getCurrencySymbol().trim());
                }
            }
        }
        vo.setNextRuleNo(1);
        if (row != null)
        {
            vo.setPackageNetwork(row.getPackageNetwork());
            vo.setNextRuleNo(row.getNextRuleNo() == null ? 1 : row.getNextRuleNo());
            vo.setSortNo(row.getSortNo());
            vo.setUpdateBy(row.getUpdateBy());
            vo.setUpdateTime(row.getUpdateTime() == null ? null : row.getUpdateTime().format(MINUTE));
        }
        return vo;
    }
}
