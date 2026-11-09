package com.fivetech.copilot.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.copilot.AiClient;
import com.fivetech.copilot.CopilotProperties;

/** AI 助手：对话整理（角色、合并、截断、长度）与系统提示 */
class CopilotChatServiceTest
{
    private static Map<String, Object> m(String role, String content)
    {
        return Map.of("role", role, "content", content);
    }

    private static String error(Runnable r)
    {
        try
        {
            r.run();
        }
        catch (ServiceException e)
        {
            return e.getMessage();
        }
        return null;
    }

    @Test
    void normalizeMergesTrimsAndKeepsUserLast()
    {
        CopilotProperties.Chat cfg = new CopilotProperties.Chat();
        cfg.setMaxMessages(3);
        List<AiClient.Message> out = CopilotChatService.normalize(List.of(
            m("assistant", "歡迎"), m("user", "昨天註冊多少"), m("assistant", "777"), m("user", "  "), m("user", "首存呢"),
            m("user", "和前天比")), cfg);
        // 去掉空的、合并连续的 user、只留最近 3 条、第一条必须是 user
        assertEquals(3, out.size());
        assertEquals("user", out.get(0).getRole());
        assertEquals("首存呢\n\n和前天比", out.get(2).getContent());
        assertEquals("請輸入問題", error(() -> CopilotChatService.normalize(List.of(m("assistant", "hi")), cfg)));
        assertEquals("請輸入問題", error(() -> CopilotChatService.normalize(new ArrayList<>(), cfg)));
        cfg.setMaxChars(5);
        assertTrue(error(() -> CopilotChatService.normalize(List.of(m("user", "123456")), cfg)).contains("5"));
    }

    @Test
    void systemPromptCarriesSiteRulesAndData()
    {
        String p = CopilotChatService.systemPrompt("uWin", "SITE-IN", "Asia/Kolkata", "運營總覽", "- 註冊人數｜777｜702｜+10.7%");
        assertTrue(p.contains("uWin（SITE-IN）"));
        assertTrue(p.contains("使用者正在看的頁面：運營總覽"));
        assertTrue(p.contains("不能查詢資料庫"));
        assertTrue(p.endsWith("- 註冊人數｜777｜702｜+10.7%"));
    }

    @Test
    void defaultChatFlattensHistory()
    {
        String[] seen = new String[1];
        AiClient c = (model, system, user, maxTokens) -> {
            seen[0] = user;
            return new AiClient.AiResult("ok", model, 1, 1, "end_turn");
        };
        c.chat("m", "s", List.of(new AiClient.Message("user", "a"), new AiClient.Message("assistant", "b"), new AiClient.Message("user", "c")), 10);
        assertEquals("使用者：a\n\n助手：b\n\n使用者：c", seen[0]);
    }
}
