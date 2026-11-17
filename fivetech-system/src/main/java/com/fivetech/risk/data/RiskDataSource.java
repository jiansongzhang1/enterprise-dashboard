package com.fivetech.risk.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 风控分析的取数口。实现见 {@link UdsRiskDataSource}（数据集名与维度字段来自 {@code risk.*} 配置，
 * 指标字段来自指标管理）；单测用内存实现。
 *
 * <p>返回的行：维度用逻辑名（userId、date、account、regTime、agentId、channelId、parent、game、gameName、platform、
 * brand、gameType、orderNo、orderAmount、submitTime、paidTime、settleTime），指标用调用方传入的 UDS 字段名。</p>
 *
 * @author fivetech
 */
public interface RiskDataSource
{
    /** 数据种类 */
    enum Kind
    {
        USER_DAY, USER_GAME, WITHDRAW, GAME, SNAPSHOT,
        /** 按推广渠道的获客：注册用户、首存人数与金额（渠道风险榜）。TODO：UDS 口径未定，接入前不可用 */
        CHANNEL_ACQ,
        /** 用户资料（账号、注册时间、推广渠道、上级）：全部用户搜索、用户详情抽屉的抬头 */
        USER,
        /** 存款订单明细（用户详情抽屉 · 資金流水） */
        DEPOSIT,
        /** 优惠发放明细（用户详情抽屉 · 資金流水） */
        BONUS
    }

    /** 这类数据接入了没有（数据集名不为空且总开关打开） */
    boolean available(Kind kind);

    /** 用户 × 日：每行一个用户一天，维度 userId、date、account、regTime、agentId、channelId、parent */
    List<Map<String, Object>> userDays(RiskFilter filter, Collection<String> fields);

    /** 用户 × 游戏 × 日：维度 userId、date、game、gameName、platform；filter 的游戏平台 / 品牌 / 类型生效 */
    List<Map<String, Object>> userGameDays(RiskFilter filter, Collection<String> fields);

    /** 玩家快照：按用户 ID 批量取（userId + fields） */
    List<Map<String, Object>> snapshots(Collection<String> userIds, Collection<String> fields);

    /** 成功出款的提款订单，单笔 ≥ minAmount；维度 orderNo、userId、account、orderAmount、submitTime、paidTime、agentId、channelId */
    List<Map<String, Object>> paidWithdrawals(RiskFilter filter, BigDecimal minAmount);

    /**
     * 游戏数据集按 dims（逻辑名：platform、brand、gameType、game、date）分组的区间汇总；eq 为上层展开的限定（逻辑名 → 值）。
     * 每次按所给的 dims 由 UDS 汇总（去重人数每层单独去重），不在后端把下级相加。
     */
    List<Map<String, Object>> games(RiskFilter filter, List<String> dims, Map<String, String> eq, Collection<String> fields);

    /** 某个游戏按日（date）汇总，from–to 为日期闭区间 */
    List<Map<String, Object>> gameDays(String siteCode, String game, LocalDate from, LocalDate to, Collection<String> fields);

    /**
     * 按推广渠道的获客（渠道风险榜）：每行 agentId、channelId、regUsers（所选时间内注册的用户数，含没有活动的）、
     * ftdUsers（所选时间内完成首存的用户数，按首存时间）、ftdAmt（这些用户的首存金额合计）。
     * TODO：UDS 的注册、首存口径未定，接入前返回空、{@link Kind#CHANNEL_ACQ} 不可用。
     */
    default List<Map<String, Object>> channelAcquisition(RiskFilter filter)
    {
        return List.of();
    }

    /** 全部用户搜索：账号包含或用户 ID 开头，最多 limit 条；每行 userId、account、regTime、agentId、channelId */
    default List<Map<String, Object>> searchUsers(String keyword, int limit)
    {
        return List.of();
    }

    /** 单个用户的资料：userId、account、regTime、agentId、channelId、parent、parentId；找不到返回 null */
    default Map<String, Object> userProfile(String userId)
    {
        return null;
    }

    /**
     * 单个用户按日或按小时的序列（用户详情抽屉 · 趋势）：kind 为 USER_DAY 或 USER_GAME，granularity 为 DAY / HOUR；
     * 每行 date（yyyy-MM-dd 或 yyyy-MM-dd HH:00）+ fields。
     */
    default List<Map<String, Object>> userSeries(Kind kind, String userId, java.time.LocalDateTime from, java.time.LocalDateTime to,
        String granularity, Collection<String> fields)
    {
        return List.of();
    }

    /**
     * 单个用户的订单（用户详情抽屉 · 資金流水）：kind 为 DEPOSIT / WITHDRAW / BONUS；按创建（申请、发放）时间落在区间内；
     * 每行 orderNo、orderAmount、orderStatus（原始值）、submitTime、paidTime、payMethod、bonusType。
     */
    default List<Map<String, Object>> userOrders(Kind kind, String userId, java.time.LocalDateTime from, java.time.LocalDateTime to)
    {
        return List.of();
    }
}
