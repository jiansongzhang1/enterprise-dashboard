package com.fivetech.dashboard.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.SysSite;
import com.fivetech.dashboard.mapper.SysSiteMapper;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import com.fivetech.monitor.service.SiteSettingService;

/**
 * 系统管理 · 站点管理：新增站点、修改站点名称 / 编码 / UDS 站点标识 / 时区 / 币种 / 币种符号（以库为准：存在 monitor_site_setting，
 * 与所属包网、规则号同一行；site_name 不为空的行是站点）。
 *
 * <ul>
 *   <li>改编码：同一事务里把站点行本身，以及所有带 site_code 列的表（角色、告警规则、运行状态、异常事件、发送记录、Lark 群缓存、
 *       站点专用通知模板、判定记录……，按 information_schema 自动找）里的旧编码改成新编码；新编码在任何表里已有数据时拒绝，
 *       免得把两个站点的数据混在一起。</li>
 *   <li>新增站点的编码正好是已去掉的站点留下的设置行（site_name 为空）：沿用这一行（包网、规则号、历史数据都接上）；
 *       大小写不同时拒绝，免得历史数据对不上。</li>
 *   <li>改编码时 UDS 站点标识没填：自动填成旧编码，请求 UDS 的 X-Uds-Site 不变（UDS 那边的站点身份不跟着改）。</li>
 *   <li>默认站点：和其他站点一样可以改名称、编码、UDS 站点标识、时区、币种；不能新增第二个默认站点。</li>
 *   <li>时区、币种：dashboard 各接口（运营总览、指标汇总、明细、导出）按站点取；UDS 该站点的数据按这个时区统计。
 *       留空时用 application.yml 的 dashboard.timezone / dashboard.currency。</li>
 *   <li>保存后在同一事务里重载 {@link SiteRegistry}，失败整体回滚。前端记住旧编码的用户要重新选一次站点。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class SiteManageService
{
    static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,31}$");

    static final Pattern UDS = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    static final Pattern TABLE = Pattern.compile("^[a-z_][a-z0-9_]*$");

    static final int NAME_MAX = 64;

    /** 币种代码：2–10 位英文字母（INR、CNY、USDT），保存为大写 */
    static final Pattern CURRENCY = Pattern.compile("^[A-Za-z]{2,10}$");

    static final int CURRENCY_SYMBOL_MAX = 8;

    static final int PACKAGE_NETWORK_MAX = 64;

    /** 排序号上限（0–99999，从小到大排） */
    static final int SORT_NO_MAX = 99999;

    private final SysSiteMapper mapper;

    private final SiteRegistry registry;

    private final SiteSettingService settings;

    public SiteManageService(SysSiteMapper mapper, SiteRegistry registry, SiteSettingService settings)
    {
        this.mapper = mapper;
        this.registry = registry;
        this.settings = settings;
    }

    /**
     * 新增站点。body：siteCode、siteName、udsSite（空 = 用站点编码）、timezone（空 = 统计时区）、
     * currency（空 = dashboard.currency）、currencySymbol（空 = 按币种推导）、sortNo（空 = 排最后，即最大值 + 1）、
     * packageNetwork（可空）。编码与「未配置」的历史设置行相同时，把这一行配置为站点（沿用包网、规则号）。
     *
     * @return 新站点编码
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(Map<String, String> body, String operator)
    {
        requireTable();
        Map<String, String> errors = new LinkedHashMap<>();
        String code = StringUtils.trimToEmpty(get(body, "siteCode"));
        if (!CODE.matcher(code).matches())
        {
            errors.put("siteCode", "站點編碼以英文字母開頭，只能用英文字母、數字、「-」「_」，最長 32 個字元");
        }
        else if (mapper.countCodeIgnoreCase(code, null) > 0)
        {
            errors.put("siteCode", "站點編碼已存在（不區分大小寫）");
        }
        else
        {
            String orphan = mapper.selectOrphanCode(code);
            if (orphan != null && !orphan.equals(code))
            {
                errors.put("siteCode", "已有編碼為 " + orphan + " 的歷史數據：要沿用請寫成「" + orphan + "」（大小寫一致），否則換一個編碼");
            }
        }
        SysSite site = new SysSite();
        site.setSiteCode(code);
        fillBasic(body, site, false, errors);
        String packageNetwork = packageNetwork(body, errors);
        failIfAny(errors);
        site.setUpdateBy(StringUtils.nvl(operator, ""));
        mapper.insert(site);
        reload();
        if (packageNetwork != null)
        {
            settings.updatePackageNetwork(code, packageNetwork, operator);
        }
        return code;
    }

    /**
     * 修改站点。body：siteCode（新编码，不传或相同 = 不改）、siteName、udsSite、timezone、currency、currencySymbol、
     * sortNo（排序号）；没传（null）的字段沿用原值，传空串表示清空（sortNo 传空串 = 不改）。
     *
     * @return { siteCode: 修改后的编码, renamed: { 表名: 改了几行 } }
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String oldCode, Map<String, String> body, String operator)
    {
        requireTable();
        SysSite existing = StringUtils.isEmpty(oldCode) ? null : mapper.selectByCodeIgnoreCase(oldCode.trim());
        if (existing == null)
        {
            throw MonitorException.notFound("站點不存在");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        SysSite site = new SysSite();
        site.setSiteName(existing.getSiteName());
        site.setUdsSite(existing.getUdsSite());
        site.setTimezone(existing.getTimezone());
        site.setCurrency(existing.getCurrency());
        site.setCurrencySymbol(existing.getCurrencySymbol());
        site.setDefaultSite(existing.isDefaultSite());
        fillBasic(body, site, true, errors);

        String newCode = body == null || body.get("siteCode") == null ? existing.getSiteCode() : body.get("siteCode").trim();
        boolean rename = !newCode.equals(existing.getSiteCode());
        boolean caseOnly = rename && newCode.equalsIgnoreCase(existing.getSiteCode());
        List<String> tables = rename ? siteCodeTables() : List.of();
        if (rename)
        {
            if (!CODE.matcher(newCode).matches())
            {
                errors.put("siteCode", "站點編碼以英文字母開頭，只能用英文字母、數字、「-」「_」，最長 32 個字元");
            }
            else if (!caseOnly && mapper.countCodeIgnoreCase(newCode, existing.getSiteCode()) > 0)
            {
                errors.put("siteCode", "站點編碼已被其他站點使用");
            }
            else if (!caseOnly)
            {
                StringBuilder used = new StringBuilder();
                if (mapper.selectOrphanCode(newCode) != null)
                {
                    used.append("monitor_site_setting（1 行）");
                }
                for (String t : tables)
                {
                    int n = mapper.countSiteCode(t, newCode);
                    if (n > 0)
                    {
                        used.append(used.length() == 0 ? "" : "、").append(t).append("（").append(n).append(" 行）");
                    }
                }
                if (used.length() > 0)
                {
                    errors.put("siteCode", "已有編碼為 " + newCode + " 的歷史數據：" + used + "，不能改成這個編碼");
                }
            }
            // UDS 站点标识没填时原来就用旧编码：改编码后固定写成旧编码，X-Uds-Site 不变
            if (StringUtils.isEmpty(site.getUdsSite()))
            {
                site.setUdsSite(existing.getSiteCode());
            }
        }
        failIfAny(errors);

        Map<String, Integer> renamed = new LinkedHashMap<>();
        if (rename)
        {
            for (String t : tables)
            {
                int n = mapper.renameSiteCode(t, existing.getSiteCode(), newCode);
                if (n > 0)
                {
                    renamed.put(t, n);
                }
            }
        }
        site.setUpdateBy(StringUtils.nvl(operator, ""));
        mapper.update(existing.getSiteCode(), rename ? newCode : null, site);
        reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteCode", newCode);
        out.put("renamed", renamed);
        return out;
    }

    // ------------------------------------------------------------------ 内部

    /** 名称、UDS 站点标识、时区、币种、币种符号、排序号；update = true 时没传的字段沿用 site 里的原值（排序号没传 = 不改） */
    private static void fillBasic(Map<String, String> body, SysSite site, boolean update, Map<String, String> errors)
    {
        String name = get(body, "siteName");
        if (!update || name != null)
        {
            name = StringUtils.trimToNull(name);
            if (name == null)
            {
                errors.put("siteName", "請填寫站點名稱");
            }
            else if (name.codePointCount(0, name.length()) > NAME_MAX)
            {
                errors.put("siteName", "站點名稱不能超過 " + NAME_MAX + " 個字");
            }
            site.setSiteName(name);
        }
        String uds = get(body, "udsSite");
        if (!update || uds != null)
        {
            uds = StringUtils.trimToNull(uds);
            if (uds != null && !UDS.matcher(uds).matches())
            {
                errors.put("udsSite", "UDS 站點標識只能用英文字母、數字、「-」「_」，最長 64 個字元");
            }
            site.setUdsSite(uds);
        }
        String tz = get(body, "timezone");
        if (!update || tz != null)
        {
            tz = StringUtils.trimToNull(tz);
            if (tz != null)
            {
                try
                {
                    tz = ZoneId.of(tz).getId();
                }
                catch (DateTimeException e)
                {
                    errors.put("timezone", "時區不正確，請用 IANA 時區 ID，如 Asia/Kolkata");
                }
            }
            site.setTimezone(tz);
        }
        String currency = get(body, "currency");
        if (!update || currency != null)
        {
            currency = StringUtils.trimToNull(currency);
            if (currency != null && !CURRENCY.matcher(currency).matches())
            {
                errors.put("currency", "幣種請填 2–10 位英文字母，如 INR、CNY");
            }
            site.setCurrency(currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT));
        }
        String symbol = get(body, "currencySymbol");
        if (!update || symbol != null)
        {
            symbol = StringUtils.trimToNull(symbol);
            if (symbol != null && symbol.codePointCount(0, symbol.length()) > CURRENCY_SYMBOL_MAX)
            {
                errors.put("currencySymbol", "幣種符號不能超過 " + CURRENCY_SYMBOL_MAX + " 個字");
            }
            site.setCurrencySymbol(symbol);
        }
        String sort = StringUtils.trimToNull(get(body, "sortNo"));
        if (sort != null)
        {
            Integer n = null;
            try
            {
                n = Integer.valueOf(sort);
            }
            catch (NumberFormatException e)
            {
                // 下面统一报错
            }
            if (n == null || n < 0 || n > SORT_NO_MAX)
            {
                errors.put("sortNo", "排序號請填 0–" + SORT_NO_MAX + " 的整數");
            }
            else
            {
                site.setSortNo(n);
            }
        }
    }

    private static String packageNetwork(Map<String, String> body, Map<String, String> errors)
    {
        String v = get(body, "packageNetwork");
        if (v == null)
        {
            return null;
        }
        String name = v.trim().replaceAll("\\s+", " ");
        if (name.isEmpty())
        {
            return null;
        }
        if (name.codePointCount(0, name.length()) > PACKAGE_NETWORK_MAX)
        {
            errors.put("packageNetwork", "包網名稱不能超過 " + PACKAGE_NETWORK_MAX + " 個字");
        }
        return name;
    }

    private List<String> siteCodeTables()
    {
        List<String> tables = mapper.selectSiteCodeTables();
        for (String t : tables)
        {
            if (t == null || !TABLE.matcher(t).matches())
            {
                throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "表名不合法，已停止改編碼：" + t);
            }
        }
        return tables;
    }

    /** 站点表还没加载时当场重试一次（执行完 SQL 不用重启）；仍失败时把原因带给页面 */
    private void requireTable()
    {
        if (!registry.isFromTable() && !registry.tryLoad())
        {
            String reason = registry.getLoadError();
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST,
                "站點表 monitor_site_setting 加載失敗，目前站點仍來自配置，不能在後台新增或修改。"
                + "請確認已執行 sql/alter_monitor_site_setting_site_postgresql.sql 和 alter_monitor_site_setting_currency_postgresql.sql"
                + "（或 01 上線腳本的 ⑯、⑱），且站點表裡有默認站點（is_default = true）"
                + (StringUtils.isEmpty(reason) ? "" : "。原因：" + reason));
        }
    }

    private void reload()
    {
        try
        {
            registry.reload();
        }
        catch (RuntimeException e)
        {
            throw new MonitorException(MonitorErrorCode.BAD_REQUEST, "保存失敗，站點配置重新加載不通過：" + e.getMessage());
        }
    }

    private static String get(Map<String, String> body, String key)
    {
        return body == null ? null : body.get(key);
    }

    private static void failIfAny(Map<String, String> errors)
    {
        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
    }
}
