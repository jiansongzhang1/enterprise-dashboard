package com.fivetech.dashboard.subscription.snapshot;

/**
 * 截图用的临时登录令牌（实现在 framework 模块：签发要用 TokenService，framework 依赖 system，所以这里只放接口）。
 *
 * @author fivetech
 */
public interface SnapshotTokens
{
    /** 一个已签发的令牌 */
    final class Issued
    {
        private final String jwt;
        private final String uuid;
        private final Long userId;

        public Issued(String jwt, String uuid, Long userId)
        {
            this.jwt = jwt;
            this.uuid = uuid;
            this.userId = userId;
        }

        /** 请求头 Authorization: Bearer 的值，前端存的就是它 */
        public String getJwt() { return jwt; }
        /** 会话编号（作废用） */
        public String getUuid() { return uuid; }
        public Long getUserId() { return userId; }
    }

    /**
     * 为账号签发临时令牌：不走密码、不走 GA，权限与该账号正常登录相同；账号不存在、已删除或停用时抛 IllegalStateException。
     *
     * @param ttlSeconds 有效期（秒），到期自动失效、不续期
     */
    Issued issue(String username, int ttlSeconds);

    /** 立即作废 */
    void revoke(Issued token);
}
