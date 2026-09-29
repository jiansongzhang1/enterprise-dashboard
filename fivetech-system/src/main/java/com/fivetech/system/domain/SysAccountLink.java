package com.fivetech.system.domain;

import java.util.Date;

/**
 * 账号邮件一次性链接 sys_account_link。
 *
 * <p>只保存凭证的 SHA-256 哈希；原文只出现在发给员工的邮件里。</p>
 *
 * @author fivetech
 */
public class SysAccountLink
{
    /** 链接类型：启用账号 */
    public static final String TYPE_ACTIVATE = "ACTIVATE";

    private Long linkId;

    private Long userId;

    private String linkType;

    private String tokenHash;

    private Date expireTime;

    private Date usedTime;

    private Date revokedTime;

    private String createBy;

    private Date createTime;

    /** 当前时刻是否仍可使用：未使用、未作废、未过期 */
    public boolean isUsable(Date now)
    {
        return usedTime == null && revokedTime == null && expireTime != null && expireTime.after(now);
    }

    public Long getLinkId() { return linkId; }

    public void setLinkId(Long linkId) { this.linkId = linkId; }

    public Long getUserId() { return userId; }

    public void setUserId(Long userId) { this.userId = userId; }

    public String getLinkType() { return linkType; }

    public void setLinkType(String linkType) { this.linkType = linkType; }

    public String getTokenHash() { return tokenHash; }

    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Date getExpireTime() { return expireTime; }

    public void setExpireTime(Date expireTime) { this.expireTime = expireTime; }

    public Date getUsedTime() { return usedTime; }

    public void setUsedTime(Date usedTime) { this.usedTime = usedTime; }

    public Date getRevokedTime() { return revokedTime; }

    public void setRevokedTime(Date revokedTime) { this.revokedTime = revokedTime; }

    public String getCreateBy() { return createBy; }

    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }

    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
