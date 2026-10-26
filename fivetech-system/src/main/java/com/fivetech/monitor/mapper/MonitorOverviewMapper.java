package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorCardRule;
import com.fivetech.monitor.domain.MonitorOverviewEvent;
import com.fivetech.monitor.domain.MonitorScope;

/**
 * 运营总览「核心指标」下方异常提醒条（anomalies）的只读查询，全部按站点（MonitorScope）限定。
 *
 * @author fivetech
 */
public interface MonitorOverviewMapper
{
    /** 本站启用的规则数；为 0 时总览不显示提醒条（anomalies = null） */
    int countEnabledRules(@Param("scope") MonitorScope scope);

    /** 本站异常中（status = open）的告警事件 */
    List<MonitorOverviewEvent> selectOpenEvents(@Param("scope") MonitorScope scope);

    /** 本站未处理（handle_status = pending）的告警事件数，不随所选日期变化 */
    int countPendingEvents(@Param("scope") MonitorScope scope);

    /** 本站最近一个已判定的数据小时；没有判定过返回 null */
    LocalDateTime selectLastJudgedHour(@Param("scope") MonitorScope scope);

    /** 本站全部未删除的规则（含已暂停）+ 运行状态 + 异常中事件，供卡片 alert 使用 */
    List<MonitorCardRule> selectCardRules(@Param("scope") MonitorScope scope);
}
