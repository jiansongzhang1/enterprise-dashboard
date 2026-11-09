package com.fivetech.promo.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.gateway.uds.UdsClient;
import com.fivetech.dashboard.gateway.uds.UdsQueryException;
import com.fivetech.dashboard.gateway.uds.UdsQueryResult;
import com.fivetech.dashboard.gateway.uds.UdsRow;

/**
 * 从 UDS 读已发布的消耗 / 预算（《市场预算、消耗：后端端到端联调手册》2026-11-06 第 4 节）。
 *
 * <p>请求体同《市场预算消耗端到端联调》第 4 节与《cmp1 端到端联调》第 3、4 条：维度带上推广商 / 推广渠道名称
 * （promoter_name、channel_name，导入时随行保存的名称快照）。导入整日替换时，当天已有的行要连同名称原样带回去，
 * 否则重新提交会把名称写成 null。不带 orderBy，后端自己排序。</p>
 *
 * <ul>
 *   <li>数据集 {@code import_yata_promo_cost / budget}，按 biz_date、推广商、推广渠道明细；站点走 UDS 客户端的 X-Uds-Site；</li>
 *   <li>一次查询、{@code offset = 0}，不做偏移翻页（手册 4.5）；ONLINE 单次最多 10000 行，
 *       {@code meta.imported.complete = false} 表示被截断，报错让用户缩小日期范围，不当全量、不补零；</li>
 *   <li>某格没有行 = 没有数据，页面显示「—」。普通查询不读 coverage。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class BudgetDataReader
{
    /** 已发布的一行（日期 × 推广渠道） */
    public static final class Row
    {
        private final LocalDate date;
        private final String promoterId;
        private final String promoterName;
        private final String channelId;
        private final String channelName;
        private final BigDecimal amount;

        public Row(LocalDate date, String promoterId, String promoterName, String channelId, String channelName, BigDecimal amount)
        {
            this.date = date;
            this.promoterId = promoterId;
            this.promoterName = promoterName;
            this.channelId = channelId;
            this.channelName = channelName;
            this.amount = amount;
        }

        public LocalDate getDate() { return date; }
        public String getPromoterId() { return promoterId; }
        public String getPromoterName() { return promoterName; }
        public String getChannelId() { return channelId; }
        public String getChannelName() { return channelName; }
        public BigDecimal getAmount() { return amount; }
    }

    private final UdsClient client;

    private final BudgetImportProperties props;

    public BudgetDataReader(UdsClient client, BudgetImportProperties props)
    {
        this.client = client;
        this.props = props;
    }

    /** [from, to] 两端都含 */
    public List<Row> rows(BudgetKind kind, LocalDate from, LocalDate to)
    {
        String amount = BudgetImportProperties.amountField(kind);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", props.udsDataset(kind));
        body.put("metrics", List.of(amount, "row_count"));
        body.put("dimensions", List.of("biz_date", "promoter_id", "promoter_name", "channel_id", "channel_name"));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("dimension", "biz_date");
        time.put("granularity", "DAY");
        time.put("from", from.toString());
        time.put("to", to.toString());
        body.put("time", time);
        body.put("limit", Math.max(1, Math.min(10_000, props.getUdsLimit())));
        body.put("offset", 0);
        body.put("options", Map.of("channel", "ONLINE"));
        UdsQueryResult r = client.queryWithMeta(body);
        if (Boolean.FALSE.equals(r.getImportedComplete()))
        {
            throw new UdsQueryException("數據平台返回的" + kind.getLabel() + "數據被截斷（超過單次上限），請縮小日期範圍後重試", 200);
        }
        List<Row> out = new ArrayList<>();
        for (UdsRow u : r.getRows() == null ? List.<UdsRow>of() : r.getRows())
        {
            LocalDate d = date(u.str("biz_date"));
            if (d != null)
            {
                out.add(new Row(d, u.str("promoter_id"), u.str("promoter_name"), u.str("channel_id"), u.str("channel_name"),
                    u.num(amount)));
            }
        }
        // 不向 UDS 要排序：按 日期 → 推广商 → 推广渠道 排好
        out.sort(java.util.Comparator.comparing(Row::getDate)
            .thenComparing(x -> x.getPromoterId() == null ? "" : x.getPromoterId())
            .thenComparing(x -> x.getChannelId() == null ? "" : x.getChannelId()));
        return out;
    }

    /** biz_date：yyyy-MM-dd（也接受带时间的写法，只取前 10 位） */
    static LocalDate date(String s)
    {
        if (s == null || s.length() < 10)
        {
            return null;
        }
        try
        {
            return LocalDate.parse(s.substring(0, 10));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** 金额文本：十进制字符串，最多 4 位小数（数据平台 DECIMAL，16 位整数 + 4 位小数），不用科学计数法 */
    public static String amountText(BigDecimal v)
    {
        BigDecimal x = v.scale() > 4 ? v.setScale(4, RoundingMode.HALF_UP) : v;
        x = x.stripTrailingZeros();
        if (x.scale() < 0)
        {
            x = x.setScale(0);
        }
        return x.toPlainString();
    }
}
