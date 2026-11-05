package com.fivetech.promo.budget;

import java.io.IOException;
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
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 推广分析 · 预算与消耗（FY-56）：页面配置、下载模板、导入校验。
 *
 * <p>本系统只做校验，不存上传的文件、不落库；有效行交给 {@link BudgetSpendWriter} 写到 UDS（暂未接入）。
 * 站点取当前请求的 X-Site-Code；「今天」按站点时区；数据起始日取 dashboard.launch-date。</p>
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

    public BudgetSpendService(DashboardProperties dashboard, PromoChannelDictionary channels, BudgetSpendWriter writer)
    {
        this.dashboard = dashboard;
        this.channels = channels;
        this.writer = writer;
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
     * 按所选日期每天 × 全部推广渠道一行，金额留空。预算模板应预填已有预算，数据在 UDS 侧、暂未接入，目前留空。
     */
    public Template template(String kindCode, String fromText, String toText)
    {
        BudgetKind kind = kind(kindCode);
        String site = dashboard.currentSite();
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

        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", BudgetImportValidator.HEADER)).append(',').append(kind.getLabel()).append("\r\n");
        List<PromoChannelDictionary.Promoter> promoters = channels.promotersOf(site);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
        {
            for (PromoChannelDictionary.Promoter p : promoters)
            {
                for (PromoChannelDictionary.Channel ch : p.getChannels())
                {
                    sb.append(d).append(',').append(csv(p.getId())).append(',').append(csv(p.getName())).append(',')
                        .append(csv(ch.getId())).append(',').append(csv(ch.getName())).append(",\r\n");
                }
            }
        }
        String range = from.equals(to) ? from.format(MMDD) : from.format(MMDD) + "-" + to.format(MMDD);
        return new Template(kind.getLabel() + "匯入模板_" + range + ".csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导入（R7–R10）：校验后把有效行交给 writer；文件只在内存里处理，不保存。
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
            written = writer.write(site, kind, out.getRecords(), operator);
        }
        r.setWritten(written);
        if (!written && !out.getRecords().isEmpty())
        {
            r.setWriteNote("數據寫入（UDS）尚未接入，本次只做了校驗，沒有寫入數據。");
        }
        log.info("[promo-budget] 导入 site={} kind={} file={} status={} total={} valid={} failed={} empty={} written={} operator={}",
            site, kind, file.getOriginalFilename(), r.getStatus(), r.getTotal(), r.getValid(), r.getFailed(), r.getEmpty(),
            written, operator);
        return r;
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
