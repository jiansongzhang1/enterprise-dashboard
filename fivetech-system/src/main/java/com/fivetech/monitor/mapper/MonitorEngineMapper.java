package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.engine.domain.MonitorEngineRule;
import com.fivetech.monitor.engine.domain.MonitorEventRecord;
import com.fivetech.monitor.engine.domain.MonitorNotifyRecord;
import com.fivetech.monitor.engine.domain.MonitorRuleStateRecord;

/**
 * 判定引擎的读写。除 {@link #selectEngineSites()} 外都按站点（MonitorScope）限定。
 *
 * @author fivetech
 */
public interface MonitorEngineMapper
{
    /** 有启用规则的站点（跨站点，判定任务入口用） */
    List<String> selectEngineSites();

    /** 本站启用的规则 + 运行状态 + 异常中的事件，按规则号排序 */
    List<MonitorEngineRule> selectEnabledRules(@Param("scope") MonitorScope scope);

    /** 本站一条未删除的规则（启用或暂停都返回），字段同 selectEnabledRules；手动触发测试告警用 */
    MonitorEngineRule selectEngineRule(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId);

    // ---------------------------------------------------------------- 小时检查进度

    int insertHourCheck(@Param("scope") MonitorScope scope, @Param("hour") LocalDateTime hour);

    String selectHourStatus(@Param("scope") MonitorScope scope, @Param("hour") LocalDateTime hour);

    int touchHourCheck(@Param("scope") MonitorScope scope, @Param("hour") LocalDateTime hour,
            @Param("now") LocalDateTime now);

    /** waiting → judged / missing（乐观锁：只改仍是 waiting 的行） */
    int finishHourCheck(@Param("scope") MonitorScope scope, @Param("hour") LocalDateTime hour,
            @Param("status") String status, @Param("now") LocalDateTime now);

    /** 早于给定小时、仍是 waiting 的小时（服务停过）直接标 missing，不补判 */
    int markStaleHoursMissing(@Param("scope") MonitorScope scope, @Param("before") LocalDateTime before,
            @Param("now") LocalDateTime now);

    // ---------------------------------------------------------------- 状态、事件、发送

    int upsertRuleState(@Param("scope") MonitorScope scope, @Param("st") MonitorRuleStateRecord state);

    int insertEvent(MonitorEventRecord event);

    int updateEvent(@Param("scope") MonitorScope scope, @Param("e") MonitorEventRecord event);

    int updateRuleJudged(@Param("scope") MonitorScope scope, @Param("ruleId") Long ruleId,
            @Param("judgedAt") LocalDateTime judgedAt, @Param("judgeError") String judgeError);

    /** 该事件还有没发出去的记录时不再追加，避免发送任务没跑时越积越多 */
    int countPendingNotify(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId);

    /** 同一事件同一通知类型已有的发送记录数（不论成败）：同一事件同一类型只发一次 */
    int countNotifyOfType(@Param("scope") MonitorScope scope, @Param("eventId") Long eventId,
            @Param("notifyType") String notifyType);

    int insertNotifyLog(MonitorNotifyRecord record);
}
