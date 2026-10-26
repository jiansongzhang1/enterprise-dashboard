package com.fivetech.framework.notification;

import com.fivetech.common.lark.LarkClient;
import com.fivetech.common.lark.LarkResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Lark 文本消息通知服务（配置文件 notification.lark.* 里的那一个应用）。
 *
 * <p>2026-10-21 起改为调用 {@link LarkClient}（token 按 appId 缓存、带错误码的结果），对外行为不变：
 * 未启用或未配置时抛 IllegalStateException，发送失败抛 IllegalStateException 并带上 Lark 的错误信息。</p>
 */
@Service
public class LarkNotificationService
{
    private final LarkProperties properties;
    private final LarkClient larkClient;

    public LarkNotificationService(LarkProperties properties, LarkClient larkClient)
    {
        this.properties = properties;
        this.larkClient = larkClient;
    }

    /**
     * 向 Lark 用户或群聊发送文本消息。
     *
     * @param receiveId 用户 open_id 或群聊 chat_id
     * @param receiveIdType open_id 或 chat_id
     * @param message 文本消息
     */
    public void sendText(String receiveId, String receiveIdType, String message)
    {
        validateConfiguration();
        LarkResult<String> result = larkClient.sendText(properties.getAppId(), properties.getAppSecret(),
            receiveIdType, receiveId, message);
        if (!result.isSuccess())
        {
            // token 失败的 msg 已经是「Lark访问令牌获取失败：…」
            throw new IllegalStateException(result.isTokenFailure() ? result.getMsg() : "Lark消息发送失败：" + result.getMsg());
        }
    }

    private void validateConfiguration()
    {
        if (!properties.isEnabled())
        {
            throw new IllegalStateException("Lark通知未启用，请配置 notification.lark.enabled=true");
        }
        if (!StringUtils.hasText(properties.getAppId()))
        {
            throw new IllegalStateException("Lark App ID未配置，请配置 notification.lark.app-id");
        }
        if (!StringUtils.hasText(properties.getAppSecret()))
        {
            throw new IllegalStateException("Lark App Secret未配置，请配置 notification.lark.app-secret");
        }
    }
}
