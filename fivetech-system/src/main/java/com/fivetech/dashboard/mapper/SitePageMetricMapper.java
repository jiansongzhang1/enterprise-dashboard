package com.fivetech.dashboard.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.dashboard.domain.SitePageMetric;

/**
 * 站点自定义的页面指标顺序与显示（dashboard_page_metric_site）。
 *
 * @author fivetech
 */
public interface SitePageMetricMapper
{
    /** 全部站点、全部页面的配置，按站点、页面、顺序排列 */
    List<SitePageMetric> selectAll();

    /** 版本（行数 + 最后修改时间），指标注册表热刷新用 */
    String selectVersion();

    /** 删除某站点某页面某指标集的配置（恢复默认，或保存前清空） */
    int deleteBySitePage(@Param("siteCode") String siteCode, @Param("pageCode") String pageCode,
            @Param("displayType") String displayType);

    int insertBatch(@Param("rows") List<SitePageMetric> rows);
}
