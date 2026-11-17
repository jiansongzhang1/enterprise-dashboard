package com.fivetech.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.common.enums.BusinessType;

/**
 * 操作日志记录查询操作：数据查询接口要带 @Log(businessType = QUERY, isSaveResponseData = false)，
 * QUERY 的序号与字典 sys_oper_type 的 dict_value = 10 对齐（release_next ⑲）。
 *
 * <p>Controller 在 fivetech-admin 模块里，这里按源码检查（与 MonitorMapperScopeTest 一样从模块目录读文件）。</p>
 */
class OperLogQueryAuditTest
{
    private static final Path CONTROLLERS = Paths.get("..", "fivetech-admin", "src", "main", "java", "com", "fivetech", "web", "controller");

    /** 文件 → 要记录查询的映射注解 */
    private static final Map<String, List<String>> QUERY_ENDPOINTS = Map.of(
            "dashboard/OverviewController.java", List.of("@PostMapping(\"/overview\")", "@PostMapping({\"/rankingboard\"})",
                    "@PostMapping(\"/cohort\")", "@PostMapping(\"/reg-channels\")"),
            "dashboard/MetricSummaryController.java", List.of("@PostMapping(\"/summary\")"),
            "dashboard/PromoAnalysisController.java", List.of("@PostMapping(\"/channels/table\")", "@PostMapping(\"/acquisition/funnel\")",
                    "@PostMapping(\"/cohort/summary\")", "@PostMapping(\"/cohort/curves\")", "@PostMapping(\"/cohort/batches\")",
                    "@PostMapping(\"/fission/agents\")", "@GetMapping(\"/fission/locate\")"),
            "dashboard/RecordQueryController.java", List.of("@PostMapping(\"/member\")", "@PostMapping(\"/deposit\")",
                    "@PostMapping(\"/withdraw\")", "@PostMapping(\"/bet\")"),
            "monitoring/MonitorEventController.java", List.of("@GetMapping"),
            "monitoring/MonitorRuleController.java", List.of("@GetMapping(\"/rules\")", "@PostMapping(\"/rules/preview\")",
                    "@GetMapping(\"/rules/{ruleId}\")"),
            "monitoring/MonitorOrderRuleController.java", List.of("@GetMapping(\"/{ruleId}\")"));

    @Test
    void queryTypeMatchesTheDictValue()
    {
        assertEquals(10, BusinessType.QUERY.ordinal(), "只能追加在最后：库里存的是序号，字典 sys_oper_type 的 10 = 查询");
        assertEquals(9, BusinessType.CLEAN.ordinal(), "原有类型的序号不能变");
    }

    @Test
    void dataQueryEndpointsAreLoggedWithoutResponses() throws Exception
    {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<String>> e : QUERY_ENDPOINTS.entrySet())
        {
            Path file = CONTROLLERS.resolve(e.getKey());
            assertTrue(Files.exists(file), "找不到 " + file.toAbsolutePath());
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String mapping : e.getValue())
            {
                boolean found = false;
                for (int i = 1; i < lines.size(); i++)
                {
                    if (lines.get(i).trim().equals(mapping))
                    {
                        found = true;
                        String prev = lines.get(i - 1);
                        if (!prev.contains("@Log(") || !prev.contains("BusinessType.QUERY") || !prev.contains("isSaveResponseData = false"))
                        {
                            missing.add(e.getKey() + " " + mapping);
                        }
                        checked++;
                    }
                }
                if (!found)
                {
                    missing.add(e.getKey() + " 找不到 " + mapping);
                }
            }
        }
        assertTrue(missing.isEmpty(), "这些查询接口没有按 QUERY 记录（或会记录返回内容）：" + missing);
        assertEquals(21, checked);
    }

    @Test
    void releaseSqlAddsTheQueryDictItem() throws Exception
    {
        String sql = Files.readString(Paths.get("..", "sql", "release_next_postgresql.sql"), StandardCharsets.UTF_8);
        assertTrue(sql.contains("SELECT 10, '查询', '10', 'sys_oper_type'"), "release_next ⑲ 要给 sys_oper_type 加「查询」");
    }
}
