package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.MonitorSiteSetting;

/**
 * 监控站点级数据：站点设置（规则号）、判定进度。
 *
 * @author fivetech
 */
public interface MonitorSiteMapper
{
    /** 站点设置行不存在时补一行（next_rule_no = 1），已存在则不动 */
    int ensureSiteSetting(@Param("scope") MonitorScope scope);

    /** 锁住本站设置行并取下一个规则号；必须在事务里调用 */
    Integer lockNextRuleNo(@Param("scope") MonitorScope scope);

    /** 规则号 +1（与 lockNextRuleNo 同一事务） */
    int increaseNextRuleNo(@Param("scope") MonitorScope scope);

    /** 本站最近一个已判定的数据小时；还没有判定过返回 null */
    LocalDateTime selectLastJudgedHour(@Param("scope") MonitorScope scope);

    /** 全部站点的设置行（跨站点；系统管理 · 站点管理用） */
    List<MonitorSiteSetting> selectAllSiteSettings();

    /** 已用过的包网名称（去重、排序） */
    List<String> selectPackageNetworks();

    /** 设置站点所属包网；packageNetwork 为 null 表示清除 */
    int upsertPackageNetwork(@Param("scope") MonitorScope scope, @Param("packageNetwork") String packageNetwork,
            @Param("updateBy") String updateBy, @Param("now") LocalDateTime now);
}
