package com.fivetech.monitor.domain.vo;

/**
 * 规则负责人（只显示邮箱）。
 *
 * @author fivetech
 */
public class OwnerVO
{
    private Long userId;

    private String email;

    private Boolean valid;

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

    public Boolean getValid()
    {
        return valid;
    }

    public void setValid(Boolean valid)
    {
        this.valid = valid;
    }
}
