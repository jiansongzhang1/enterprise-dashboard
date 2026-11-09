package com.fivetech.web.controller.copilot;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.copilot.chat.CopilotChatService;

/**
 * 管理后台 · AI 助手（右下角聊天）。权限 {@code copilot:chat:use}；站点取请求头 X-Site-Code。
 *
 * <p>对话内容不落库、不写操作日志（只在 copilot_usage_log 记次数与 token 用量），前端把最近的对话存在浏览器里、每次一起发来。</p>
 *
 * @author fivetech
 */
@RestController
@RequestMapping("/copilot/chat")
public class CopilotChatController extends BaseController
{
    private final CopilotChatService service;

    public CopilotChatController(CopilotChatService service)
    {
        this.service = service;
    }

    /** { available, reason, model, modelLabel, dailyLimit, usedToday, maxChars, siteCode, siteName, contextNote } */
    @PreAuthorize("@ss.hasPermi('copilot:chat:use')")
    @GetMapping("/meta")
    public AjaxResult meta()
    {
        return success(service.meta(getUsername()));
    }

    /**
     * 请求：{ messages: [{ role: user | assistant, content }], page: 当前页面标题（选填） }，最后一条是这次的提问。
     * 返回：{ reply, model, modelLabel, contextAsOf, usedToday, dailyLimit }。
     */
    @PreAuthorize("@ss.hasPermi('copilot:chat:use')")
    @PostMapping
    @SuppressWarnings("unchecked")
    public AjaxResult chat(@RequestBody Map<String, Object> body)
    {
        Object messages = body == null ? null : body.get("messages");
        Object page = body == null ? null : body.get("page");
        return success(service.chat(messages instanceof List ? (List<Map<String, Object>>) messages : null,
            page == null ? null : String.valueOf(page), getUsername()));
    }
}
