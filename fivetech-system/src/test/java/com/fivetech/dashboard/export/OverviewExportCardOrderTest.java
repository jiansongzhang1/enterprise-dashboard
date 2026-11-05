package com.fivetech.dashboard.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.vo.MetricCardVO;
import com.fivetech.dashboard.domain.vo.MetricsBlockVO;
import com.fivetech.dashboard.domain.vo.OverviewVO;

/** 运营总览导出的「指標快照 / 時間序列」按卡片 sortNo（站点顺序，与页面一致）排列 */
class OverviewExportCardOrderTest
{
    private static MetricCardVO card(String code, Integer sortNo)
    {
        MetricCardVO c = new MetricCardVO();
        c.setCode(code);
        c.setSortNo(sortNo);
        return c;
    }

    @Test
    void cardsFollowSortNoThenTemplate()
    {
        OverviewVO vo = new OverviewVO();
        MetricsBlockVO block = new MetricsBlockVO();
        List<MetricCardVO> items = new ArrayList<>();
        items.add(card("reg", 3));
        items.add(card("net", 1));
        items.add(card("dep", 2));
        items.add(card("zzz", null));      // 没有 sortNo：排最后
        block.setItems(items);
        vo.getBlocks().put("METRICS", block);
        List<String> codes = OverviewWorkbookExporter.cards(vo).stream().map(MetricCardVO::getCode).toList();
        assertEquals(List.of("net", "dep", "reg", "zzz"), codes);
    }

    @Test
    void noMetricsBlockIsEmpty()
    {
        assertEquals(0, OverviewWorkbookExporter.cards(new OverviewVO()).size());
        assertEquals(0, OverviewWorkbookExporter.cards(null).size());
    }
}
