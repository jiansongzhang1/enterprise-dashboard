package com.fivetech.promo.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fivetech.dashboard.domain.MetricDictItem;
import com.fivetech.dashboard.domain.PageMetricConfig;
import com.fivetech.dashboard.domain.query.OverviewQuery;
import com.fivetech.dashboard.domain.vo.OverviewNoticeVO;
import com.fivetech.dashboard.view.UserViewService;
import com.fivetech.promo.analysis.query.ChannelTableQuery;

/** 推广分析取数接口与个人视图：不传 metrics / columns / rows 时按我在本站的视图，传了按传的 */
class PromoViewTest
{
    static PromoMetricCatalog.Metric metric(String code, boolean isDefault)
    {
        MetricDictItem card = new MetricDictItem();
        card.setMetricCode(PromoMetricCatalog.spec(code).getDbCode());
        card.setMetricName(code);
        card.setValueFormat("INT");
        PageMetricConfig page = new PageMetricConfig();
        page.setPageCode("PROMO");
        page.setMetricCode(card.getMetricCode());
        page.setEmphasis("NORMAL");
        page.setIsDefault(isDefault);
        return new PromoMetricCatalog.Metric(PromoMetricCatalog.spec(code), card, page);
    }

    static List<String> codes(List<PromoMetricCatalog.Metric> ms)
    {
        List<String> out = new ArrayList<>();
        ms.forEach(m -> out.add(m.getCode()));
        return out;
    }

    static final List<PromoMetricCatalog.Metric> ALL = List.of(metric("spend", true), metric("reg", true), metric("ftd", false),
        metric("rd", false));

    @Test
    void overviewUsesViewCardsInViewOrderWhenMetricsAbsent()
    {
        List<OverviewNoticeVO> notices = new ArrayList<>();
        OverviewQuery q = new OverviewQuery();
        assertEquals(List.of("rd", "spend"), codes(PromoOverviewService.resolveMetrics(q, ALL, notices, List.of("rd", "gone", "spend"))));
        // 没有个人视图 / 视图里的都不认识：页面默认
        assertEquals(List.of("spend", "reg"), codes(PromoOverviewService.resolveMetrics(q, ALL, notices, null)));
        assertEquals(List.of("spend", "reg"), codes(PromoOverviewService.resolveMetrics(q, ALL, notices, List.of("gone"))));
        // 显式传了 metrics：按传的
        q.setMetrics(List.of("ftd"));
        assertEquals(List.of("ftd"), codes(PromoOverviewService.resolveMetrics(q, ALL, notices, List.of("rd"))));
    }

    @Test
    void channelQueryFillsColumnsAndRowsFromView()
    {
        PromoAnalysisService svc = new PromoAnalysisService(null, null, null, null, null);
        svc.setViews(new UserViewService(null, null, null, null, null)
        {
            final Map<String, List<String>> mine = Map.of(PromoViewItemSource.COLUMNS, List.of("reg", "ftd"),
                PromoViewItemSource.ROWS, List.of("date"));

            @Override
            public List<String> itemsOf(String slotCode)
            {
                return mine.get(slotCode);
            }
        });
        ChannelTableQuery q = new ChannelTableQuery();
        svc.applyView(q);
        assertEquals(List.of("reg", "ftd"), q.getColumns());
        assertEquals(List.of("date"), q.getRows());
        ChannelTableQuery given = new ChannelTableQuery();
        given.setColumns(List.of("spend"));
        given.setRows(List.of("agent", "channel"));
        svc.applyView(given);
        assertEquals(List.of("spend"), given.getColumns());
        assertEquals(List.of("agent", "channel"), given.getRows());
    }
}
