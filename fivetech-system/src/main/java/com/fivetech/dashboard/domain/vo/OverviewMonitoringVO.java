package com.fivetech.dashboard.domain.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 监控绑定状态。只影响 {@code alert} 字段怎么填，不改变任何指标数值。
 *
 * @author fivetech
 */
public class OverviewMonitoringVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 是否已有启用的告警规则 */
    private boolean configured;

    /** 已绑定的通知通道 */
    private List<String> channels = new ArrayList<>();

    public boolean isConfigured()
    {
        return configured;
    }

    public void setConfigured(boolean configured)
    {
        this.configured = configured;
    }

    public List<String> getChannels()
    {
        return channels;
    }

    public void setChannels(List<String> channels)
    {
        this.channels = channels;
    }

}