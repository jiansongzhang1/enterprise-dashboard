package com.fivetech.dashboard.gateway.uds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.domain.vo.ColumnMetaVO;
import com.fivetech.dashboard.service.RecordColumnRegistry;

/**
 * 用户明细 · 推广分析三列（《推广分析 · 指标与 UDS 数据集字段映射》第 5 节）：
 * 注册终端、上级账号取用户快照 terminal / agent_login；最近登录时间由后端从市场会员快照补，不进 UDS 导出。
 * 读真实的 application.yml，防止配置漏写导致「暫時不能按它篩選」。
 */
class UdsMemberPromoColumnsTest
{
    private static UdsProperties.DetailDataset member() throws Exception
    {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("app",
            new FileSystemResource("../fivetech-admin/src/main/resources/application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        UdsProperties uds = Binder.get(env).bind("dashboard.gateway.uds", UdsProperties.class).get();
        return uds.getDetails().get("member");
    }

    @Test
    void ymlMapsTerminalAndAgentOnPlayerSnapshot() throws Exception
    {
        UdsProperties.DetailDataset d = member();
        assertEquals("dashboard_player_snapshot_v3", d.getDataset());
        assertEquals("terminal", d.getFields().get("registerTerminal"));
        assertEquals("agent_login", d.getFields().get("agentAccount"));
        assertNull(d.getFields().get("lastLoginTime"), "最近登录时间不在用户快照");
        assertEquals(Map.of("web", "Web", "android", "Android", "ios", "iOS", "unidentified", "Unknown"),
            d.getValueMaps().get("registerTerminal"));
        assertEquals(List.of("lastLoginTime"), d.getPageOnlyColumns());
        // 流失用户的 UDS 字段先留空（带 lost = true 时报错，不带不影响）
        assertEquals("", d.getFilterFields().get("lost"));
        assertNull(d.getValueMaps().get("lost"));
        // 注册推广渠道仍待数仓确认
        assertNull(d.getFilterFields().get("registerChannel"));
        assertNull(d.getFields().get("registerChannel"));
    }

    @Test
    void exportSkipsPageOnlyColumnsAndMapsNewOnes() throws Exception
    {
        UdsProperties.DetailDataset d = member();
        assertEquals(List.of("player_id", "terminal", "agent_login"),
            UdsMetricDataGateway.exportColumns(d, List.of("userId", "registerTerminal", "lastLoginTime", "agentAccount")));
        int code = 0;
        try
        {
            UdsMetricDataGateway.exportColumns(d, List.of("nope"));
        }
        catch (ServiceException e)
        {
            code = e.getCode();
        }
        assertEquals(4001, code, "没映射的列仍然拒绝");
    }

    @Test
    void terminalLabels()
    {
        assertEquals("Web", UdsMetricDataGateway.terminalLabel("web"));
        assertEquals("iOS", UdsMetricDataGateway.terminalLabel("ios"));
        assertEquals("未識別", UdsMetricDataGateway.terminalLabel("unidentified"));
        assertEquals("PWA", UdsMetricDataGateway.terminalLabel("PWA"));
        assertNull(UdsMetricDataGateway.terminalLabel(null));
    }

    @Test
    void memberColumnsHaveThreePromoColumns()
    {
        List<ColumnMetaVO> cols = new RecordColumnRegistry().memberColumns();
        assertEquals(29, cols.size()); // 原型 26 列 + 推广分析 3 列
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        cols.forEach(c -> labels.put(c.getCode(), c.getLabel()));
        assertEquals("註冊終端", labels.get("registerTerminal"));
        assertEquals("上級帳號", labels.get("agentAccount"));
        assertEquals("最近登錄時間", labels.get("lastLoginTime"));
        ColumnMetaVO last = cols.stream().filter(c -> "lastLoginTime".equals(c.getCode())).findFirst().get();
        assertTrue(!last.isSortable() && !last.isFilterable(), "跨数据集补的列不能排序、筛选");
    }
}
