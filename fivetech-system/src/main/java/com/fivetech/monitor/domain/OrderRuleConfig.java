package com.fivetech.monitor.domain;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 订单规则的条件，存在 monitor_rule.config（jsonb）。
 *
 * <p>字段名即 JSON 键名；对外查询接口按它组装 metric / threshold / trigger / notify（见 {@code MonitorOrderRules#view}）。
 * 读的时候忽略不认识的键，以后加字段不影响旧数据。</p>
 *
 * @author fivetech
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderRuleConfig
{
    /** 订单类型：本期只有 WITHDRAW */
    private String orderType;

    /** 时点：SUBMIT 提交时 / PAID 出款成功时 */
    private String triggerAt;

    /** 金额口径：SINGLE 单笔 / DAILY_TOTAL 账号当日累计 */
    private String amountMode;

    /** 金额门槛（≥），站点币种 */
    private BigDecimal amountThreshold;

    /** 渠道范围；null = 全站 */
    private ChannelScope channelScope;

    /** 每天最多发送条数：0（不限）/ 10 / 20 / 50 / 100 */
    private Integer dailyCap;

    public String getOrderType()
    {
        return orderType;
    }

    public void setOrderType(String orderType)
    {
        this.orderType = orderType;
    }

    public String getTriggerAt()
    {
        return triggerAt;
    }

    public void setTriggerAt(String triggerAt)
    {
        this.triggerAt = triggerAt;
    }

    public String getAmountMode()
    {
        return amountMode;
    }

    public void setAmountMode(String amountMode)
    {
        this.amountMode = amountMode;
    }

    public BigDecimal getAmountThreshold()
    {
        return amountThreshold;
    }

    public void setAmountThreshold(BigDecimal amountThreshold)
    {
        this.amountThreshold = amountThreshold;
    }

    public ChannelScope getChannelScope()
    {
        return channelScope;
    }

    public void setChannelScope(ChannelScope channelScope)
    {
        this.channelScope = channelScope;
    }

    public Integer getDailyCap()
    {
        return dailyCap;
    }

    public void setDailyCap(Integer dailyCap)
    {
        this.dailyCap = dailyCap;
    }

    /**
     * 渠道范围：PROMOTER = 某推广商合计；CHANNEL = 推广商 › 推广渠道。
     * 名称是保存时从配置中心 PROMO_CHANNELS 复制的快照，展示用；判定只看 ID。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChannelScope
    {
        /** PROMOTER / CHANNEL */
        private String type;

        private String promoterId;

        private String promoterName;

        /** type = CHANNEL 时有值 */
        private String channelId;

        private String channelName;

        public String getType()
        {
            return type;
        }

        public void setType(String type)
        {
            this.type = type;
        }

        public String getPromoterId()
        {
            return promoterId;
        }

        public void setPromoterId(String promoterId)
        {
            this.promoterId = promoterId;
        }

        public String getPromoterName()
        {
            return promoterName;
        }

        public void setPromoterName(String promoterName)
        {
            this.promoterName = promoterName;
        }

        public String getChannelId()
        {
            return channelId;
        }

        public void setChannelId(String channelId)
        {
            this.channelId = channelId;
        }

        public String getChannelName()
        {
            return channelName;
        }

        public void setChannelName(String channelName)
        {
            this.channelName = channelName;
        }
    }
}
