package com.fivetech.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.configcenter.ConfigCodes;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.risk.config.RiskBoards;
import com.fivetech.risk.config.RiskConfigService;

/**
 * 风控配置（配置中心 RISK_RANK_THRESHOLDS / RISK_RANK_BOARDS / RISK_GAME_DICT）：默认值、结构校验、改榜单条件。
 */
class RiskConfigTest
{
    @Test
    void defaultsAreValidAndRegistered()
    {
        assertNull(RiskConfigService.validateThresholds(RiskConfigService.DEFAULT_THRESHOLDS));
        assertNull(RiskConfigService.validateBoards(RiskConfigService.DEFAULT_BOARDS));
        assertNull(RiskConfigService.validateGameDict(RiskConfigService.DEFAULT_GAME_DICT));
        assertTrue(ConfigCodes.get("RISK_RANK_BOARDS") != null && ConfigCodes.get("RISK_RANK_THRESHOLDS") != null
            && ConfigCodes.get("RISK_GAME_DICT") != null, "在配置中心登记了示例与校验");
        assertNull(RiskConfigService.validateGameDict(ConfigCodes.get("RISK_GAME_DICT").getExample()), "示例本身合法");
    }

    @Test
    void structureErrors()
    {
        assertTrue(RiskConfigService.validateThresholds("{\"values\":{\"bigOne\":500},\"limits\":{\"bigOne\":{\"min\":1000}}}").contains("超出"));
        assertTrue(RiskConfigService.validateBoards("{\"boards\":[]}").contains("boards"));
        assertTrue(RiskConfigService.validateBoards("{\"boards\":[{\"code\":\"a\",\"name\":\"A\",\"rankCol\":\"x\",\"shareMode\":\"NOPE\"}]}")
            .contains("shareMode"));
        assertTrue(RiskConfigService.validateBoards("{\"boards\":[{\"code\":\"a\",\"name\":\"A\",\"rankCol\":\"x\"},{\"code\":\"a\",\"name\":\"B\",\"rankCol\":\"x\"}]}")
            .contains("重複"));
        assertTrue(RiskConfigService.validateGameDict("{\"platforms\":[{\"code\":\"PG\",\"name\":\"PG\"}],\"games\":[{\"code\":\"g\",\"name\":\"G\",\"platform\":\"JL\"}]}")
            .contains("JL"));
    }

    @Test
    void boardsReferenceThresholds()
    {
        RiskFixtures f = new RiskFixtures();
        RiskBoards b = f.config.boards(null);
        RiskBoards.Board nb = b.board("new");
        assertEquals("newDays", nb.entry.get(0).ref);
        assertEquals(new BigDecimal("7"), nb.entry.get(0).resolve(b.thresholds, null));
        assertEquals(new BigDecimal("14"), nb.entry.get(0).resolve(b.thresholds, Map.of("newDays", new BigDecimal("14"))));
        assertEquals(4, b.riskTypes.size());
    }

    @Test
    void saveThresholdsWithinLimits()
    {
        RiskFixtures f = new RiskFixtures();
        MonitorFieldException e = assertThrows(MonitorFieldException.class,
            () -> f.config.saveThresholds(null, Map.of("newDays", 120), "risk@x"));
        assertTrue(e.getFields().get("newDays").contains("1 – 90"));
        assertThrows(MonitorFieldException.class, () -> f.config.saveThresholds(null, Map.of("nope", 1), "risk@x"));
        f.config.saveThresholds(null, Map.of("newDays", 14), "risk@x");
        assertEquals(new BigDecimal("14"), f.config.boards(null).thresholds.get("newDays"), "写进配置中心后生效");
        assertEquals(new BigDecimal("50000"), f.config.boards(null).thresholds.get("bigOne"), "没改的保留");
        assertEquals("risk@x", f.config.thresholdItem(null).getCreateBy());
    }
}
