package com.fivetech.monitor.service;

import com.fivetech.common.lark.LarkResult;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatsVO;

/**
 * 监控管理 · Lark 群（机器人在哪个群，规则就能选哪个群）。
 *
 * @author fivetech
 */
public interface IMonitorLarkChatService
{
    /**
     * 实时拉取机器人所在的群并刷新本站缓存，返回群下拉选项。
     * 拉取失败（超时、5xx、限流）返回上次结果且 fetched = false；凭证无效抛 4802。
     */
    MonitorLarkChatsVO listChats(MonitorScope scope);

    /**
     * 向 Lark 群发消息后调用：返回里看出机器人已不在群里（被踢出，230002）、群已解散（232009）
     * 或 chat_id 无效（230034）时，把本站缓存里的这个群置为失效（dead）。
     * 其他结果（成功、被禁言、超时、限流等）不动群状态。机器人被拉回同一个群后，下次拉取群列表时自动恢复。
     *
     * @return 这次是否把群置为了失效（原来就是失效或不在缓存里返回 false）
     */
    boolean markDeadIfChatGone(MonitorScope scope, String chatId, LarkResult<?> sendResult);

    /**
     * 群连接测试（FY-30 R17 ②）：向本站缓存里的群发一条群连接测试卡片，不写事件和发送记录。
     * 群不存在 404；群已失效、机器人已不在群里 4805；凭证无效或未配置 4802；其他发送失败 4808。
     *
     * @return 成功时 { chatId, name, messageId }
     * @param sender 点击「測試」的账号邮箱（没有邮箱时为登录名）
     */
    java.util.Map<String, Object> testChat(MonitorScope scope, String chatId, String sender);
}
