package com.fivetech.dashboard.subscription;

/**
 * 操作订阅的人：能不能改由「负责人本人或管理员」决定（V0.4 原型：其他人只能查看）。
 *
 * <p>管理后台（系统管理 · 订阅简报）的操作人一律按管理员处理；站点页面按 {@code dashboard:subscription:admin} 或超级管理员判断。</p>
 *
 * @author fivetech
 */
public final class SubscriptionActor
{
    private final Long userId;

    private final String username;

    private final boolean admin;

    public SubscriptionActor(Long userId, String username, boolean admin)
    {
        this.userId = userId;
        this.username = username;
        this.admin = admin;
    }

    /** 管理后台、定时任务 */
    public static SubscriptionActor admin(Long userId, String username)
    {
        return new SubscriptionActor(userId, username, true);
    }

    public Long getUserId() { return userId; }

    public String getUsername() { return username; }

    public boolean isAdmin() { return admin; }

    /** 能否修改：管理员；负责人本人；没有负责人的旧数据按建立人 */
    public boolean canEdit(DashboardSubscription s)
    {
        if (admin)
        {
            return true;
        }
        if (s.getOwnerUserId() != null)
        {
            return userId != null && userId.equals(s.getOwnerUserId());
        }
        return username != null && username.equals(s.getCreateBy());
    }
}
