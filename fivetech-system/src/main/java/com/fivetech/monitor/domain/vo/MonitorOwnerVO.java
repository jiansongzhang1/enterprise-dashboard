package com.fivetech.monitor.domain.vo;

/**
 * 可选负责人。
 *
 * @author fivetech
 */
public class MonitorOwnerVO
{
    private Long userId;

    private String email;

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public String getEmail()
    {
        return email;
    }

    public void setEmail(String email)
    {
        this.email = email;
    }
}
