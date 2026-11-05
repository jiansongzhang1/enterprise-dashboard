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
}
