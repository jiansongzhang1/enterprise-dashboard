package com.fivetech.monitor.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.mapper.MonitorLarkChatMapper;

/**
 * 拉取成功后在一个事务里刷新本站群缓存（单独一个 Bean，保证 @Transactional 生效）。
 *
 * @author fivetech
 */
@Component
public class MonitorLarkChatWriter
{
    /** 一条 insert 最多带多少个群 */
    static final int BATCH = 500;

    private final MonitorLarkChatMapper chatMapper;

    public MonitorLarkChatWriter(MonitorLarkChatMapper chatMapper)
    {
        this.chatMapper = chatMapper;
    }

    /** 拉到的群 upsert 为 ok；之前有、这次没拉到的置 dead */
    @Transactional(rollbackFor = Exception.class)
    public void refresh(MonitorScope scope, List<MonitorLarkChat> chats, LocalDateTime now)
    {
        for (int from = 0; from < chats.size(); from += BATCH)
        {
            chatMapper.upsertSeenChats(scope, chats.subList(from, Math.min(chats.size(), from + BATCH)), now);
        }
        chatMapper.markUnseenDead(scope, now);
    }
}
