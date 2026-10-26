package com.fivetech.common.lark;

/**
 * 机器人所在的 Lark 群（GET /open-apis/im/v1/chats 的一项）。
 *
 * @author fivetech
 */
public class LarkChat
{
    /** oc_ 开头 */
    private final String chatId;

    private final String name;

    public LarkChat(String chatId, String name)
    {
        this.chatId = chatId;
        this.name = name;
    }

    public String getChatId()
    {
        return chatId;
    }

    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return chatId + "(" + name + ")";
    }
}
