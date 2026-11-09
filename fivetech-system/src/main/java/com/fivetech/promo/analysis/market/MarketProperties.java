package com.fivetech.promo.analysis.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推广分析 · UDS 市场数据集（《推广分析：页面指标与数据集字段对照》2026-11-05）。
 *
 * <ul>
 *   <li>{@code dashboard_market_hour_v1}：小时宽表（注册、首存、复存金额、激活…）与精确区间人数（存款、复存、投注人数，存款额、投注额）；</li>
 *   <li>{@code dashboard_market_member_snapshot_v1}：会员当前状态（流失人数、裂变层级的直属下级）。</li>
 * </ul>
 * 站点由请求头 X-Uds-Site 区分（与其他 UDS 查询相同）；merchant-code 不为空时每个查询另加 merchant_code 过滤。
 *
 * @author fivetech
 */
@Component
@ConfigurationProperties(prefix = "promo.market")
public class MarketProperties
{
    /** 关闭后推广分析不调 UDS，全部按「数据待接入」返回 */
    private boolean enabled = true;

    private String hourDataset = "dashboard_market_hour_v1";

    private String memberDataset = "dashboard_market_member_snapshot_v1";

    /** 可选：merchant_code 过滤值（Postman 联调包里的 merchant_code）；为空不加 */
    private String merchantCode = "";

    /** 单次查询的 limit（UDS 上限 1000），超过自动翻页 */
    private int pageSize = 1000;

    /** 裂变层级进入某用户时，最多读取的直属下级人数（排序、分页在后端做） */
    private int maxChildren = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHourDataset() { return hourDataset; }
    public void setHourDataset(String hourDataset) { this.hourDataset = hourDataset; }
    public String getMemberDataset() { return memberDataset; }
    public void setMemberDataset(String memberDataset) { this.memberDataset = memberDataset; }
    public String getMerchantCode() { return merchantCode; }
    public void setMerchantCode(String merchantCode) { this.merchantCode = merchantCode; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxChildren() { return maxChildren; }
    public void setMaxChildren(int maxChildren) { this.maxChildren = maxChildren; }
}
