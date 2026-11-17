package com.fivetech.monitor.order;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 「訂單告警」列表实时查 UDS 的配置（application.yml 的 {@code monitor.order-lookup}，都可用环境变量覆盖）：
 * 订单状态与用户信息不存库，展示时按订单号、用户 ID 批量查。数据集名为空 = 未接入，列表照常返回、这两列为空（udsOk = false）。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "monitor.order-lookup")
public class OrderLookupProperties
{
    /** 提款订单数据集（订单号 → 当前状态、用户 ID） */
    private String orderDataset = "";

    /** 用户数据集（用户 ID → 账号、注册时间），一般是玩家快照 */
    private String userDataset = "";

    /** 订单数据集的时间维度（按提交时间分区），查询窗口 = 最早事件时间往前 1 天到现在 */
    private String orderTimeField = "submit_time";

    /** 站点字段；为空则不按站点过滤（订单号全局唯一时可留空） */
    private String siteField = "site_code";

    /** 计数指标名（UDS 要求至少一个指标） */
    private String countMetric = "record_count";

    private String orderNoField = "order_no";

    private String orderStatusField = "status";

    private String orderUserField = "user_id";

    private String userIdField = "user_id";

    private String accountField = "account";

    private String regTimeField = "register_time";

    /** UDS 的订单状态值 → PENDING 審核中 / PAID 已出款 / REJECTED 已拒絕；没配的按原值大写 */
    private Map<String, String> statusMap = new LinkedHashMap<>();

    /** 每次最多查多少个（UDS 单次上限） */
    private int batchSize = 100;

    /** 终态（已出款、已拒绝）缓存秒数 */
    private int finalCacheSeconds = 600;

    /** 審核中缓存秒数 */
    private int pendingCacheSeconds = 30;

    /** 按订单状态筛选时，最多先取多少条事件再过滤 */
    private int filterMax = 2000;

    public String getOrderDataset() { return orderDataset; }
    public void setOrderDataset(String v) { this.orderDataset = v == null ? "" : v.trim(); }
    public String getUserDataset() { return userDataset; }
    public void setUserDataset(String v) { this.userDataset = v == null ? "" : v.trim(); }
    public String getOrderTimeField() { return orderTimeField; }
    public void setOrderTimeField(String v) { this.orderTimeField = v; }
    public String getSiteField() { return siteField; }
    public void setSiteField(String v) { this.siteField = v == null ? "" : v.trim(); }
    public String getCountMetric() { return countMetric; }
    public void setCountMetric(String v) { this.countMetric = v; }
    public String getOrderNoField() { return orderNoField; }
    public void setOrderNoField(String v) { this.orderNoField = v; }
    public String getOrderStatusField() { return orderStatusField; }
    public void setOrderStatusField(String v) { this.orderStatusField = v; }
    public String getOrderUserField() { return orderUserField; }
    public void setOrderUserField(String v) { this.orderUserField = v; }
    public String getUserIdField() { return userIdField; }
    public void setUserIdField(String v) { this.userIdField = v; }
    public String getAccountField() { return accountField; }
    public void setAccountField(String v) { this.accountField = v; }
    public String getRegTimeField() { return regTimeField; }
    public void setRegTimeField(String v) { this.regTimeField = v; }
    public Map<String, String> getStatusMap() { return statusMap; }
    public void setStatusMap(Map<String, String> v) { this.statusMap = v == null ? new LinkedHashMap<>() : v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public int getFinalCacheSeconds() { return finalCacheSeconds; }
    public void setFinalCacheSeconds(int v) { this.finalCacheSeconds = v; }
    public int getPendingCacheSeconds() { return pendingCacheSeconds; }
    public void setPendingCacheSeconds(int v) { this.pendingCacheSeconds = v; }
    public int getFilterMax() { return filterMax; }
    public void setFilterMax(int v) { this.filterMax = v; }
}
