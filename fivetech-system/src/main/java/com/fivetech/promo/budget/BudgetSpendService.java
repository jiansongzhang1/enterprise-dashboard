package com.fivetech.promo.budget;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 推广分析 · 预算与消耗（FY-56）：页面配置、表格数据、下载模板、导入。
 *
 * <p>数据存放在数据平台：读走 UDS（{@link BudgetDataReader}）；写由 {@link BudgetSpendWriter} 同步提交到采集平台。
 * 本系统不存上传的文件、不落库。站点取当前请求的 X-Site-Code；「今天」按站点时区；数据起始日取 dashboard.launch-date。</p>
 *
 * @author fivetech
 */
@Service
public class BudgetSpendService
{
    private static final Logger log = LoggerFactory.getLogger(BudgetSpendService.class);

    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");

    private final DashboardProperties dashboard;

    private final PromoChannelDictionary channels;

    private final BudgetSpendWriter writer;

    private final BudgetDataReader reader;

    private final BudgetImportProperties props;

    public BudgetSpendService(DashboardProperties dashboard, PromoChannelDictionary channels, BudgetSpendWriter writer,
        BudgetDataReader reader, BudgetImportProperties props)
    {
        this.dashboard = dashboard;
        this.channels = channels;
        this.writer = writer;
        this.reader = reader;
        this.props = props;
    }

    /**
     * 页面配置：站点、币种、今天、两个页签的可选范围与默认范围、上限、渠道字典、是否已接入写入。
     */
    public Map<String, Object> meta()
    {
        String site = dashboard.currentSite();
        LocalDate today = today(site);
        LocalDate start = dataStart();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("siteCode", site);
        m.put("siteName", dashboard.siteName(site));
        m.put("currency", dashboard.siteCurrency(site));
        m.put("currencySymbol", dashboard.siteCurrencySymbol(site));
        m.put("timezone", dashboard.siteTimezone(site));
        m.put("today", today.toString());
        m.put("dataStart", start == null ? null : start.toString());
        m.put("maxDays", BudgetImportValidator.MAX_DAYS);
        m.put("maxRows", BudgetImportValidator.MAX_ROWS);

        LocalDate yesterday = today.minusDays(1);
        m.put("spend", range(start, yesterday, yesterday.minusDays(6), yesterday));
        LocalDate budgetMax = today.plusDays(BudgetImportValidator.BUDGET_AHEAD_DAYS);
        m.put("budget", range(start, budgetMax, today.plusDays(1), today.plusDays(7)));

        List<Map<String, Object>> promoters = new ArrayList<>();
        for (PromoChannelDictionary.Promoter p : channels.promotersOf(site))
        {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            List<Map<String, Object>> chs = new ArrayList<>();
            for (PromoChannelDictionary.Channel c : p.getChannels())
            {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", c.getId());
                cm.put("name", c.getName());
                cm.put("status", c.getStatus());
                chs.add(cm);
            }
            pm.put("channels", chs);
            promoters.add(pm);
        }
        m.put("promoters", promoters);
        m.put("writeEnabled", writer.isEnabled());
        m.put("readEnabled", props.isReadEnabled());
        return m;
    }

    private static Map<String, Object> range(LocalDate min, LocalDate max, LocalDate defFrom, LocalDate defTo)
    {
        LocalDate from = min != null && defFrom.isBefore(min) ? min : defFrom;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("min", min == null ? null : min.toString());
        r.put("max", max.toString());
        r.put("defaultFrom", from.toString());
        r.put("defaultTo", defTo.toString());
        return r;
    }

    /** 下载的模板 */
    public static final class Template
    {
        private final String fileName;
        private final byte[] content;

        Template(String fileName, byte[] content)
        {
            this.fileName = fileName;
            this.content = content;
        }

        public String getFileName() { return fileName; }
        public byte[] getContent() { return content; }
    }

    /**
     * 下载模板（R6）：CSV（UTF-8 带 BOM，Excel 直接打开不乱码），六列 日期, 推廣商 ID, 推廣商名稱, 推廣渠道 ID, 推廣渠道名稱, 消耗 / 預算；
     * 按所选日期每天 × 全部推广渠道一行；消耗金额留空，预算预填已有预算（读 UDS，没有的留空）。
     */
    public Template template(String kindCode, String fromText, String toText)
    {
        BudgetKind kind = kind(kindCode);
        String site = dashboard.currentSite();
        LocalDate[] r = range(kind, site, fromText, toText);
        LocalDate from = r[0];
        LocalDate to = r[1];
        // 预算模板预填已有预算（R6）；读不到时不给模板，免得填错
        Map<String, BigDecimal> existing = new LinkedHashMap<>();
        if (kind == BudgetKind.BUDGET && props.isReadEnabled())
        {
            for (BudgetDataReader.Row row : readRows(kind, from, to))
            {
                if (row.getAmount() != null)
                {
                    existing.put(row.getDate() + "|" + row.getChannelId(), row.getAmount());
                }
            }
        }

        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", BudgetImportValidator.HEADER)).append(',').append(kind.getLabel()).append("\r\n");
        List<PromoChannelDictionary.Promoter> promoters = channels.promotersOf(site);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
        {
            for (PromoChannelDictionary.Promoter p : promoters)
            {
                for (PromoChannelDictionary.Channel ch : p.getChannels())
                {
                    BigDecimal v = existing.get(d + "|" + ch.getId());
                    sb.append(d).append(',').append(csv(p.getId())).append(',').append(csv(p.getName())).append(',')
                        .append(csv(ch.getId())).append(',').append(csv(ch.getName())).append(',')
                        .append(v == null ? "" : BudgetDataReader.amountText(v)).append("\r\n");
                }
            }
        }
        String range = from.equals(to) ? from.format(MMDD) : from.format(MMDD) + "-" + to.format(MMDD);
        return new Template(kind.getLabel() + "匯入模板_" + range + ".csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导入（R7–R10）：校验后把有效行同步写到数据平台（可能要用户确认覆盖，见 {@link #resume}）；文件只在内存里处理，不保存。
     */
    public BudgetImportResult importFile(String kindCode, MultipartFile file, String operator)
    {
        BudgetKind kind = kind(kindCode);
        if (file == null)
        {
            throw MonitorFieldException.of("file", "請選擇文件");
        }
        String site = dashboard.currentSite();
        byte[] bytes;
        try
        {
            bytes = file.getBytes();
        }
        catch (IOException e)
        {
            throw MonitorFieldException.of("file", "文件讀取失敗，請重新上傳");
        }
        BudgetImportValidator.Context ctx = new BudgetImportValidator.Context(kind, today(site), dataStart(),
            channels.promotersOf(site));
        BudgetImportValidator.Outcome out = BudgetImportValidator.validate(file.getOriginalFilename(), bytes, ctx);
        BudgetImportResult r = out.getResult();
        boolean written = false;
        if (!out.getRecords().isEmpty())
        {
            BudgetWriteOutcome w = writer.write(site, kind, out.getRecords(), operator);
            r.setWrite(w);
            written = w.isWritten();
            if (BudgetWriteOutcome.DISABLED.equals(w.getStatus()))
            {
                r.setWriteNote(w.getMessage());
            }
        }
        r.setWritten(written);
        log.info("[promo-budget] 导入 site={} kind={} file={} status={} total={} valid={} failed={} empty={} written={} operator={}",
            site, kind, file.getOriginalFilename(), r.getStatus(), r.getTotal(), r.getValid(), r.getFailed(), r.getEmpty(),
            written, operator);
        return r;
    }

    // ------------------------------------------------------------------ 表格数据

    /**
     * 表格数据：所选日期范围（单次最多 31 天）的已发布行 rows[]：{date, promoterId, promoterName, channelId, channelName, amount}
     * （amount 为十进制字符串）。某格没有行显示「—」，不补零。
     */
    public Map<String, Object> data(String kindCode, String fromText, String toText)
    {
        BudgetKind kind = kind(kindCode);
        String site = dashboard.currentSite();
        LocalDate[] r = range(kind, site, fromText, toText);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.name());
        out.put("from", r[0].toString());
        out.put("to", r[1].toString());
        out.put("readEnabled", props.isReadEnabled());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (props.isReadEnabled())
        {
            List<BudgetDataReader.Row> list;
            try
            {
                list = reader.rows(kind, r[0], r[1]);
            }
            catch (RuntimeException e)
            {
                log.warn("[promo-budget] 读取 {} 失败 site={} {}~{}：{}", kind, site, r[0], r[1], e.getMessage());
                throw new ServiceException("讀取數據平台的" + kind.getLabel() + "失敗：" + e.getMessage());
            }
            for (BudgetDataReader.Row row : list)
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", row.getDate().toString());
                m.put("promoterId", row.getPromoterId());
                m.put("promoterName", row.getPromoterName());
                m.put("channelId", row.getChannelId());
                m.put("channelName", row.getChannelName());
                m.put("amount", row.getAmount() == null ? null : BudgetDataReader.amountText(row.getAmount()));
                rows.add(m);
            }
        }
        out.put("rows", rows);
        return out;
    }

    private List<BudgetDataReader.Row> readRows(BudgetKind kind, LocalDate from, LocalDate to)
    {
        try
        {
            return reader.rows(kind, from, to);
        }
        catch (RuntimeException e)
        {
            log.warn("[promo-budget] 读取已有{}失败 {}~{}：{}", kind.getLabel(), from, to, e.getMessage());
            throw new ServiceException("讀取已有" + kind.getLabel() + "失敗，暫時不能下載預填模板：" + e.getMessage());
        }
    }

    /** 模板、表格共用的日期范围校验：格式、先后、最多 31 天、不早于数据起始日、消耗到昨天 / 预算到今天 + 90 天 */
    private LocalDate[] range(BudgetKind kind, String site, String fromText, String toText)
    {
        LocalDate today = today(site);
        LocalDate from = date("from", fromText);
        LocalDate to = date("to", toText);
        if (to.isBefore(from))
        {
            throw MonitorFieldException.of("to", "結束日期不能早於開始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > BudgetImportValidator.MAX_DAYS)
        {
            throw MonitorFieldException.of("to", "單次最多 " + BudgetImportValidator.MAX_DAYS + " 天");
        }
        LocalDate start = dataStart();
        if (start != null && from.isBefore(start))
        {
            throw MonitorFieldException.of("from", "早於數據起始日 " + start);
        }
        LocalDate max = kind == BudgetKind.SPEND ? today.minusDays(1) : today.plusDays(BudgetImportValidator.BUDGET_AHEAD_DAYS);
        if (to.isAfter(max))
        {
            throw MonitorFieldException.of("to", kind == BudgetKind.SPEND ? "消耗只能選到昨天" : "預算最遠到今天起 90 天");
        }
        return new LocalDate[] { from, to };
    }

    /**
     * 接着上一次导入的写入：确认覆盖（confirm = true）/ 取消（false），或在「處理中」时原样重发查结果。
     */
    public BudgetWriteOutcome resume(String token, Boolean confirm, String operator)
    {
        if (token == null || token.isBlank())
        {
            throw MonitorFieldException.of("token", "缺少 token");
        }
        BudgetWriteOutcome o = writer.resume(token.trim(), Boolean.TRUE.equals(confirm), dashboard.currentSite(), operator);
        log.info("[promo-budget] 继续写入 site={} confirm={} → {} operator={}", dashboard.currentSite(), confirm, o.getStatus(), operator);
        return o;
    }

    // ------------------------------------------------------------------ helpers

    private LocalDate today(String site)
    {
        return LocalDate.now(dashboard.siteZone(site));
    }

    /** 数据起始日：dashboard.launch-date（yyyy-MM-dd）；没配或写错时不限制 */
    private LocalDate dataStart()
    {
        try
        {
            String s = dashboard.getLaunchDate();
            return s == null || s.trim().isEmpty() ? null : LocalDate.parse(s.trim());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static BudgetKind kind(String code)
    {
        BudgetKind k = BudgetKind.of(code);
        if (k == null)
        {
            throw MonitorFieldException.of("kind", "類型只能是 SPEND（消耗）或 BUDGET（預算）");
        }
        return k;
    }

    private static LocalDate date(String field, String text)
    {
        try
        {
            return LocalDate.parse(text == null ? "" : text.trim());
        }
        catch (RuntimeException e)
        {
            throw MonitorFieldException.of(field, "日期格式應為 yyyy-MM-dd");
        }
    }

    private static String csv(String s)
    {
        String v = s == null ? "" : s;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
        {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
