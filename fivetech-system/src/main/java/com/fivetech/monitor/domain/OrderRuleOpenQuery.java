package com.fivetech.monitor.domain;

/**
 * 对外查询订单告警规则（GET /open/monitor/order-rules）的筛选参数，原样接收 query string。
 *
 * <p>都按字符串接收，由 {@code MonitorOrderRules#filter} 统一校验并转成 {@link MonitorOrderRuleFilter}，
 * 参数不合法时按字段返回中文提示（400），而不是框架的类型转换错误。
 * 多值用英文逗号分隔，同一参数内为「或」，不同参数之间为「且」。</p>
 *
 * @author fivetech
 */
public class OrderRuleOpenQuery
{
    /** 站点编码；API Key 方式必填（不传时用请求头 X-Site-Code / 默认站点） */
    private String siteCode;

    /** 规则 ID，逗号分隔 */
    private String ruleIds;

    /** 规则编号，逗号分隔，如 R-023,R-024 */
    private String ruleNos;

    /** 规则名称包含（不区分大小写），最多 30 字 */
    private String keyword;

    /** true / false；不传 = 启用和暂停都返回 */
    private String enabled;

    /** 订单类型，逗号分隔；本期只有 WITHDRAW */
    private String orderType;

    /** 规则对应的指标，逗号分隔：WITHDRAW_AMOUNT_SINGLE / WITHDRAW_AMOUNT_DAILY_TOTAL */
    private String metric;

    /** 时点，逗号分隔：SUBMIT / PAID */
    private String triggerAt;

    /** 等级，逗号分隔：CRIT / WARN */
    private String level;

    /** 渠道范围：ALL 只查全站规则；g:推广商ID；c:推广商ID|推广渠道ID */
    private String channel;

    /** 是否开启通知：true / false */
    private String notifyEnabled;

    /** 告警群 ID，逗号分隔 */
    private String chatId;

    /** 负责人邮箱，精确匹配（不区分大小写） */
    private String ownerEmail;

    /** 门槛金额下限（含） */
    private String amountMin;

    /** 门槛金额上限（含） */
    private String amountMax;

    /** 只返回 version 大于它的规则 */
    private String sinceVersion;

    /** 更新时间起（含），yyyy-MM-dd HH:mm:ss，站点时区 */
    private String updatedFrom;

    /** 更新时间止（不含），yyyy-MM-dd HH:mm:ss，站点时区 */
    private String updatedTo;

    /** version（默认，从小到大）/ updateTime（从新到旧）/ ruleNo */
    private String orderBy;

    /** 页码，默认 1 */
    private String pageNum;

    /** 每页条数，默认 100，最大 500 */
    private String pageSize;

    public String getSiteCode()
    {
        return siteCode;
    }

    public void setSiteCode(String siteCode)
    {
        this.siteCode = siteCode;
    }

    public String getRuleIds()
    {
        return ruleIds;
    }

    public void setRuleIds(String ruleIds)
    {
        this.ruleIds = ruleIds;
    }

    public String getRuleNos()
    {
        return ruleNos;
    }

    public void setRuleNos(String ruleNos)
    {
        this.ruleNos = ruleNos;
    }

    public String getKeyword()
    {
        return keyword;
    }

    public void setKeyword(String keyword)
    {
        this.keyword = keyword;
    }

    public String getEnabled()
    {
        return enabled;
    }

    public void setEnabled(String enabled)
    {
        this.enabled = enabled;
    }

    public String getOrderType()
    {
        return orderType;
    }

    public void setOrderType(String orderType)
    {
        this.orderType = orderType;
    }

    public String getMetric()
    {
        return metric;
    }

    public void setMetric(String metric)
    {
        this.metric = metric;
    }

    public String getTriggerAt()
    {
        return triggerAt;
    }

    public void setTriggerAt(String triggerAt)
    {
        this.triggerAt = triggerAt;
    }

    public String getLevel()
    {
        return level;
    }

    public void setLevel(String level)
    {
        this.level = level;
    }

    public String getChannel()
    {
        return channel;
    }

    public void setChannel(String channel)
    {
        this.channel = channel;
    }

    public String getNotifyEnabled()
    {
        return notifyEnabled;
    }

    public void setNotifyEnabled(String notifyEnabled)
    {
        this.notifyEnabled = notifyEnabled;
    }

    public String getChatId()
    {
        return chatId;
    }

    public void setChatId(String chatId)
    {
        this.chatId = chatId;
    }

    public String getOwnerEmail()
    {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail)
    {
        this.ownerEmail = ownerEmail;
    }

    public String getAmountMin()
    {
        return amountMin;
    }

    public void setAmountMin(String amountMin)
    {
        this.amountMin = amountMin;
    }

    public String getAmountMax()
    {
        return amountMax;
    }

    public void setAmountMax(String amountMax)
    {
        this.amountMax = amountMax;
    }

    public String getSinceVersion()
    {
        return sinceVersion;
    }

    public void setSinceVersion(String sinceVersion)
    {
        this.sinceVersion = sinceVersion;
    }

    public String getUpdatedFrom()
    {
        return updatedFrom;
    }

    public void setUpdatedFrom(String updatedFrom)
    {
        this.updatedFrom = updatedFrom;
    }

    public String getUpdatedTo()
    {
        return updatedTo;
    }

    public void setUpdatedTo(String updatedTo)
    {
        this.updatedTo = updatedTo;
    }

    public String getOrderBy()
    {
        return orderBy;
    }

    public void setOrderBy(String orderBy)
    {
        this.orderBy = orderBy;
    }

    public String getPageNum()
    {
        return pageNum;
    }

    public void setPageNum(String pageNum)
    {
        this.pageNum = pageNum;
    }

    public String getPageSize()
    {
        return pageSize;
    }

    public void setPageSize(String pageSize)
    {
        this.pageSize = pageSize;
    }
}
