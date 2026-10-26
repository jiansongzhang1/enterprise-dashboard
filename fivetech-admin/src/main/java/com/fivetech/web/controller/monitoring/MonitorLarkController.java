package com.fivetech.web.controller.monitoring;

import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.monitor.service.IMonitorLarkChatService;
import com.fivetech.monitor.service.MonitorScopeResolver;

/**
 * 【Dashboard】监控管理 · Lark 群。
 *
 * <p>机器人在哪个群，规则就能直接选哪个群：客户把机器人拉进群即可，不需要在 Yata 里绑定。
 * 站点取自请求头 {@code X-Site-Code}（不传为默认站点）。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/monitor/lark")
public class MonitorLarkController extends BaseController
{
    private final IMonitorLarkChatService chatService;

    private final MonitorScopeResolver scopeResolver;

    public MonitorLarkController(IMonitorLarkChatService chatService, MonitorScopeResolver scopeResolver)
    {
        this.chatService = chatService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 机器人所在的群（实时拉取）：规则抽屉打开「通知」下拉时调用。
     * 返回 { fetched, fetchedAt, chats: [{ chatId, name, status, refCount }] }；凭证无效 4802。
     */
    @PreAuthorize("@ss.hasAnyPermi('monitor:rule:add,monitor:rule:edit')")
    @GetMapping("/chats")
    public AjaxResult chats()
    {
        return success(chatService.listChats(scopeResolver.resolve()));
    }

    /**
     * 群连接测试（FY-30 R17 ②）：向该群发一条群连接测试卡片（灰色表头「【測試】Yata 群連接測試」，
     * 行：機器人 / 群 / 發送人 / 時間）。不关联事件、不计入静默、不更新最近发送。
     * 成功：msg「測試訊息已發送到「{群名}」，請到群組確認」，data = { chatId, name, messageId }。
     * 群不存在 404；群已失效 4805；凭证无效 4802；其他发送失败 4808（msg 为原因）。
     */
    @PreAuthorize("@ss.hasAnyPermi('monitor:rule:add,monitor:rule:edit')")
    @PostMapping("/chats/{chatId}/test")
    public AjaxResult testChat(@PathVariable String chatId)
    {
        Map<String, Object> data = chatService.testChat(scopeResolver.resolve(), chatId, currentSender());
        return AjaxResult.success("測試訊息已發送到「" + data.get("name") + "」，請到群組確認", data);
    }

    /** 点击「測試」的账号邮箱；没有邮箱用登录名 */
    private static String currentSender()
    {
        LoginUser user = SecurityUtils.getLoginUser();
        String email = user == null || user.getUser() == null ? null : user.getUser().getEmail();
        return email != null && !email.isBlank() ? email.trim() : SecurityUtils.getUsername();
    }
}
