package com.fivetech.monitor.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorNotifyTemplate;

/**
 * 告警通知模板。模板是系统管理的配置（全站默认 + 各站点专用），列表与取模板都跨站点，不走 MonitorScope。
 *
 * @author fivetech
 */
public interface MonitorNotifyTemplateMapper
{
    /** 全部模板（管理页面） */
    List<MonitorNotifyTemplate> selectAll();

    MonitorNotifyTemplate selectById(@Param("templateId") Long templateId);

    /** 某站点某类型可用的模板：站点专用（启用）在前，全站默认（启用）在后，最多 2 行 */
    List<MonitorNotifyTemplate> selectCandidates(@Param("siteCode") String siteCode, @Param("notifyType") String notifyType,
            @Param("platform") String platform);

    int insert(MonitorNotifyTemplate t);

    /** 修改内容 / 启用状态，version + 1 */
    int update(MonitorNotifyTemplate t);

    /** 只能删站点专用的（全站默认不能删） */
    int deleteSiteTemplate(@Param("templateId") Long templateId);
}
