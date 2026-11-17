package com.fivetech.dashboard.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.dashboard.subscription.DashboardSubscription;
import com.fivetech.dashboard.subscription.DashboardSubscriptionLog;

/**
 * 订阅简报（dashboard_subscription / dashboard_subscription_log）。删除为逻辑删除（del_flag = '2'）。
 *
 * @author fivetech
 */
public interface DashboardSubscriptionMapper
{
    /** 列表：siteCode / enabled 为空不过滤；按站点、创建先后 */
    List<DashboardSubscription> selectList(@Param("siteCode") String siteCode, @Param("enabled") Boolean enabled);

    DashboardSubscription selectById(@Param("id") Long id);

    /** 定时任务：全部启用中的订阅 */
    List<DashboardSubscription> selectEnabled();

    /** 同一站点下同名（不区分大小写、排除自己）的数量 */
    int countName(@Param("siteCode") String siteCode, @Param("name") String name, @Param("excludeId") Long excludeId);

    int insert(DashboardSubscription row);

    int update(DashboardSubscription row);

    int updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled, @Param("updateBy") String updateBy);

    int softDelete(@Param("id") Long id, @Param("updateBy") String updateBy);

    /**
     * 记录一次发送结果。{@code lastSlot} 为空表示不是定时发送（立即发送 / 测试），不改 last_slot。
     */
    int markRun(@Param("id") Long id, @Param("lastSlot") String lastSlot, @Param("runAt") LocalDateTime runAt,
            @Param("status") String status, @Param("error") String error);

    /**
     * 定时发送抢占：last_slot 还不是这个发送时刻才改成它，返回 1 = 抢到（多实例 / 锁过期时不重复发）。
     */
    int claimSlot(@Param("id") Long id, @Param("slot") String slot);

    int insertLog(DashboardSubscriptionLog log);

    /** 最近的发送记录，新的在前 */
    List<DashboardSubscriptionLog> selectLogs(@Param("subscriptionId") Long subscriptionId, @Param("limit") int limit);

    // ---------------- 推送批次与发送历史（2026-11-13） ----------------

    /** 开始一次推送（result 为空 = RUNNING）；回填 id */
    int insertRun(com.fivetech.dashboard.subscription.DashboardSubscriptionRun run);

    /** 结束一次推送：写结果、计数、AI 与截图状态 */
    int finishRun(com.fivetech.dashboard.subscription.DashboardSubscriptionRun run);

    /** 开始超过 minutes 分钟仍是 RUNNING 的批次改为 FAIL（发送中断） */
    int failStaleRuns(@Param("minutes") int minutes);

    /** 该订阅 minutes 分钟内开始、仍在进行中的批次数 */
    int countRunning(@Param("subscriptionId") Long subscriptionId, @Param("minutes") int minutes);

    /** 本站各订阅最近一次结束的批次 */
    List<com.fivetech.dashboard.subscription.DashboardSubscriptionRun> selectLastRuns(@Param("siteCode") String siteCode);

    /** 某批次的逐群结果 */
    List<DashboardSubscriptionLog> selectRunLogs(@Param("runId") Long runId);

    /** 发送历史（分页） */
    List<com.fivetech.dashboard.subscription.SubscriptionHistoryRow> selectHistory(
            @Param("q") com.fivetech.dashboard.subscription.SubscriptionHistoryQuery q);

    long countHistory(@Param("q") com.fivetech.dashboard.subscription.SubscriptionHistoryQuery q);

    /** 发送历史里出现过的群：[{chatId, name}] */
    List<java.util.Map<String, Object>> selectHistoryChats(@Param("siteCode") String siteCode);

    /** 本站全部订阅（含已删除）：[{id, name, deleted}] */
    List<java.util.Map<String, Object>> selectAllWithDeleted(@Param("siteCode") String siteCode);
}
