package com.fivetech.monitor.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * 订单状态与用户信息的实时查询（UDS），供「訂單告警」列表用。实现见 {@link UdsOrderLookup}。
 *
 * @author fivetech
 */
public interface OrderLookup
{
    /** 订单当前状态：status 为 PENDING / PAID / REJECTED（或 UDS 原值大写） */
    record OrderInfo(String status, String userId)
    {
    }

    record UserInfo(String account, LocalDateTime registerTime)
    {
    }

    /** 接入了没有 */
    boolean available();

    /**
     * 订单号 → 状态；查不到的不在结果里。UDS 失败抛异常（列表照常返回，udsOk = false）。
     *
     * @param since 最早的事件时间（统计时区），用来限定订单数据集的时间窗口
     */
    Map<String, OrderInfo> orders(String siteCode, Collection<String> orderNos, LocalDateTime since);

    /** 用户 ID → 账号、注册时间 */
    Map<String, UserInfo> users(String siteCode, Collection<String> userIds);
}
