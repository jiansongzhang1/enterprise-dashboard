package com.fivetech.common.core.domain.model;

/**
 * 在线用户展示对象，不暴露登录 Token 和密码等敏感信息。
 */
public class OnlineUser
{
    private String tokenId;
    private Long userId;
    private String userName;
    private String nickName;
    private String ipaddr;
    private String loginLocation;
    private String browser;
    private String os;
    private Long loginTime;
    private Long lastHeartbeat;
    private Long expireTime;

    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }
    public String getIpaddr() { return ipaddr; }
    public void setIpaddr(String ipaddr) { this.ipaddr = ipaddr; }
    public String getLoginLocation() { return loginLocation; }
    public void setLoginLocation(String loginLocation) { this.loginLocation = loginLocation; }
    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public Long getLoginTime() { return loginTime; }
    public void setLoginTime(Long loginTime) { this.loginTime = loginTime; }
    public Long getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public Long getExpireTime() { return expireTime; }
    public void setExpireTime(Long expireTime) { this.expireTime = expireTime; }
}
