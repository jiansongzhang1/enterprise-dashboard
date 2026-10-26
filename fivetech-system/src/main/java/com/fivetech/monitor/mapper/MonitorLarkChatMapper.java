package com.fivetech.monitor.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.monitor.domain.MonitorLarkChat;
import com.fivetech.monitor.domain.MonitorScope;
import com.fivetech.monitor.domain.vo.MonitorLarkChatOptionVO;

/**
 * Lark 群列表缓存（monitor_lark_chat）。
 *
 * <p>GET /monitor/lark/chats 每次实时拉取成功后刷新：拉到的群 upsert 为 ok，之前有、这次没拉到的置 dead。
 * 发送消息时 Lark 返回机器人不在群里（被踢出）、群已解散也置 dead（{@code IMonitorLarkChatService.markDeadIfChatGone}）；
 * 机器人被拉回同一个群后，下次拉取时恢复为 ok。</p>
 *
 * @author fivetech
 */
public interface MonitorLarkChatMapper
{
    /** 本站缓存里的群；从没拉到过返回 null */
    MonitorLarkChat selectChat(@Param("scope") MonitorScope scope, @Param("chatId") String chatId);

    /** 最近一次成功拉取的时间 = 本站 last_seen_at 的最大值；从没拉到过群返回 null */
    LocalDateTime selectLastSeenAt(@Param("scope") MonitorScope scope);

    /**
     * 拉到的群 upsert 为 ok，更新群名和 last_seen_at。
     * 并发拉取时只接受更新的那次（last_seen_at 不回退）。
     */
    int upsertSeenChats(@Param("scope") MonitorScope scope, @Param("chats") List<MonitorLarkChat> chats,
            @Param("now") LocalDateTime now);

    /** 本次没拉到的群（last_seen_at 早于本次拉取时间）置 dead */
    int markUnseenDead(@Param("scope") MonitorScope scope, @Param("now") LocalDateTime now);

    /** 发送时 Lark 返回机器人不在群里、群已解散或 chat_id 无效：该群置 dead；返回 0 表示原来就是 dead 或不在缓存里 */
    int markChatDead(@Param("scope") MonitorScope scope, @Param("chatId") String chatId, @Param("now") LocalDateTime now);

    /**
     * 群下拉的选项：status = ok 的全部返回；dead 的只在被规则（含暂停的，不含已删除的）引用时返回。
     * refCount = 引用它的规则数。排序由 Service 按群名自然排序。
     */
    List<MonitorLarkChatOptionVO> selectChatOptions(@Param("scope") MonitorScope scope);
}
