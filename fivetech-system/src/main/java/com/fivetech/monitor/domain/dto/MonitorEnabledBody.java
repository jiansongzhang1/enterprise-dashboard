package com.fivetech.monitor.domain.dto;

/**
 * 启用 / 暂停请求体：{@code { "enabled": false }}。
 *
 * @author fivetech
 */
public class MonitorEnabledBody
{
    private Boolean enabled;

    public Boolean getEnabled()
    {
        return enabled;
    }

    public void setEnabled(Boolean enabled)
    {
        this.enabled = enabled;
    }
}
