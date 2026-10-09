package com.fivetech.common.core.domain.model;

/**
 * Google Authenticator 绑定信息
 *
 * @author fivetech
 */
public class GaBindInfo
{
    private Integer gaStatus;

    private String issuer;

    private String account;

    private String otpauthUrl;

    /** 设置密钥（Base32）：无法扫码时在验证器里选「输入设置密钥」手动填写；仅待绑定状态返回 */
    private String secret;

    public String getSecret()
    {
        return secret;
    }

    public void setSecret(String secret)
    {
        this.secret = secret;
    }

    public Integer getGaStatus()
    {
        return gaStatus;
    }

    public void setGaStatus(Integer gaStatus)
    {
        this.gaStatus = gaStatus;
    }

    public String getIssuer()
    {
        return issuer;
    }

    public void setIssuer(String issuer)
    {
        this.issuer = issuer;
    }

    public String getAccount()
    {
        return account;
    }

    public void setAccount(String account)
    {
        this.account = account;
    }

    public String getOtpauthUrl()
    {
        return otpauthUrl;
    }

    public void setOtpauthUrl(String otpauthUrl)
    {
        this.otpauthUrl = otpauthUrl;
    }
}
