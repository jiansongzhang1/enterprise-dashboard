package com.fivetech.web.controller.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.alibaba.fastjson2.JSON;
import com.fivetech.common.annotation.Log;
import com.fivetech.common.core.controller.BaseController;
import com.fivetech.common.core.domain.AjaxResult;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.enums.BusinessType;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.system.service.ISysUserService;

/** 系统通知联系人管理。 */
@RestController
@RequestMapping("/notification/contacts")
public class NotificationContactController extends BaseController
{
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("telegram", "whatsapp", "lark");
    private static final Set<String> SUPPORTED_LARK_IDENTIFIER_TYPES = Set.of("open_id", "chat_id");

    private final ISysUserService userService;

    public NotificationContactController(ISysUserService userService)
    {
        this.userService = userService;
    }

    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping
    public AjaxResult list()
    {
        List<NotificationContactView> contacts = userService.selectUserList(new SysUser()).stream()
            .map(user -> new NotificationContactView(user.getUserId(), user.getUserName(), user.getNickName(),
                user.getEmail(), user.getPhonenumber(), user.getNotificationContacts()))
            .collect(Collectors.toList());
        return success(contacts);
    }

    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "通知联系人", businessType = BusinessType.UPDATE)
    @PutMapping("/{userId}")
    public AjaxResult update(@PathVariable Long userId,
                             @Validated @RequestBody NotificationContactUpdateRequest request)
    {
        String channel = StringUtils.trim(request.getChannel()).toLowerCase();
        String value = StringUtils.trim(request.getValue());
        if (!SUPPORTED_CHANNELS.contains(channel))
        {
            return error("不支持的通知渠道");
        }
        String identifierType = StringUtils.trim(request.getIdentifierType());
        identifierType = StringUtils.isBlank(identifierType) ? "open_id" : identifierType.toLowerCase();
        if ("lark".equals(channel) && !SUPPORTED_LARK_IDENTIFIER_TYPES.contains(identifierType))
        {
            return error("Lark联系人类型只支持 open_id 或 chat_id");
        }
        userService.checkUserDataScope(userId);
        Map<String, Object> contact = new HashMap<>();
        if ("telegram".equals(channel))
        {
            contact.put("chatId", value);
        }
        else if ("lark".equals(channel))
        {
            contact.put("receiveId", value);
            contact.put("receiveIdType", identifierType);
        }
        else
        {
            contact.put("value", value);
        }
        contact.put("enabled", true);
        contact.put("updatedAt", System.currentTimeMillis());
        String contactJson = JSON.toJSONString(contact);
        return toAjax(userService.updateNotificationContact(userId, channel, contactJson));
    }
}
