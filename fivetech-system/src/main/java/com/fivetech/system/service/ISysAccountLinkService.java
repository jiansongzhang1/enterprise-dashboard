package com.fivetech.system.service;

import java.util.Date;
import com.fivetech.system.domain.SysAccountLink;

/**
 * 账号邮件一次性链接 服务层
 *
 * @author fivetech
 */
public interface ISysAccountLinkService
{
    /**
     * 为账号生成新链接。同一账号之前未使用的链接全部作废。
     *
     * @param userId 用户ID
     * @param linkType 链接类型
     * @param expireHours 有效小时数
     * @param createBy 操作人
     * @return 新链接（含凭证原文，仅此一次可见）
     */
    public IssuedLink issue(Long userId, String linkType, int expireHours, String createBy);

    /**
     * 按凭证原文查找仍可使用的链接；不改变链接状态（邮件网关预先打开链接不会把它用掉）。
     *
     * @return 可用的链接；已使用、已作废、已过期、类型不符或不存在时返回 null
     */
    public SysAccountLink findUsable(String rawToken, String linkType);

    /**
     * 标记链接已使用。
     *
     * @return true 成功；false 表示并发下已被使用或已失效
     */
    public boolean markUsed(Long linkId);

    /**
     * 作废账号所有未使用的链接。
     */
    public int revokeAll(Long userId);

    /** 新生成的链接 */
    public static class IssuedLink
    {
        private final String token;

        private final Date expireTime;

        public IssuedLink(String token, Date expireTime)
        {
            this.token = token;
            this.expireTime = expireTime;
        }

        public String getToken() { return token; }

        public Date getExpireTime() { return expireTime; }
    }
}
