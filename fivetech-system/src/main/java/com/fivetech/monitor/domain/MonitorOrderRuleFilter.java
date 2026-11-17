package com.fivetech.monitor.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单规则查询条件（已校验、已转类型），给 MonitorOrderRuleMapper.selectOrderRuleList 用。
 *
 * <p>只在服务内部组装，字段直接公开（MyBatis 按字段读取）。列表为空表示不按该项筛选。
 * 时间已从站点时区换成库里的统计时区（dashboard.timezone）。</p>
 *
 * @author fivetech
 */
public class MonitorOrderRuleFilter
{
    public List<Long> ruleIds = List.of();

    public List<Integer> ruleNos = List.of();

    /** 小写 */
    public String keyword;

    public Boolean enabled;

    public List<String> orderTypes = List.of();

    /** SINGLE / DAILY_TOTAL（由 metric 换算） */
    public List<String> amountModes = List.of();

    public List<String> triggerAts = List.of();

    public List<String> levels = List.of();

    /** null 不筛选；ALL 只查全站；PROMOTER / CHANNEL 按 promoterId、channelId */
    public String channelMode;

    public String promoterId;

    public String channelId;

    public Boolean notifyEnabled;

    public List<String> chatIds = List.of();

    /** 小写 */
    public String ownerEmail;

    public BigDecimal amountMin;

    public BigDecimal amountMax;

    public Long sinceVersion;

    public LocalDateTime updatedFrom;

    public LocalDateTime updatedTo;

    /** version / updateTime / ruleNo */
    public String orderBy = "version";

    public int pageNum = 1;

    public int pageSize = 100;
}
