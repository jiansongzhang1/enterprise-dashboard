package com.fivetech.configcenter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.configcenter.domain.ConfigItem;
import com.fivetech.configcenter.mapper.ConfigItemMapper;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.monitor.exception.MonitorException;
import com.fivetech.monitor.exception.MonitorFieldException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 系统管理 · 配置中心：按「配置编码 + 站点」存放业务配置（config_center），配置值为 JSON。
 *
 * <ul>
 *   <li>站点为空 = 全站通用；读取时站点自己的优先，没有再用全站通用（{@link #value(String, String)}）。</li>
 *   <li>配置值必须是合法 JSON；{@link ConfigCodes} 里登记的编码另按结构校验。</li>
 *   <li>不缓存：每次读库（表很小，读取方都是低频操作）。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class ConfigCenterService
{
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    static final int PAGE_MAX = 64;

    static final int DESC_MAX = 500;

    static final int VALUE_MAX = 65_536;

    private final ConfigItemMapper mapper;

    private final DashboardProperties dashboard;

    private final ObjectMapper json;

    public ConfigCenterService(ConfigItemMapper mapper, DashboardProperties dashboard, ObjectMapper json)
    {
        this.mapper = mapper;
        this.dashboard = dashboard;
        this.json = json;
    }

    public List<ConfigItem> list(String configCode, String siteCode, String pageName)
    {
        return mapper.selectList(StringUtils.trimToEmpty(configCode), StringUtils.trimToEmpty(siteCode),
            StringUtils.trimToEmpty(pageName));
    }

    public ConfigItem get(Long id)
    {
        ConfigItem item = id == null ? null : mapper.selectById(id);
        if (item == null)
        {
            throw MonitorException.notFound("配置不存在");
        }
        return item;
    }

    /** 新增页的选项：站点、已用过的页面、代码里登记的编码（默认页面、含义、示例） */
    public Map<String, Object> options()
    {
        List<Map<String, String>> sites = new ArrayList<>();
        for (String code : dashboard.allSiteCodes())
        {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("code", code);
            s.put("name", dashboard.siteName(code));
            sites.add(s);
        }
        Set<String> pages = new LinkedHashSet<>();
        ConfigCodes.all().forEach(d -> pages.add(d.getPageName()));
        pages.addAll(mapper.selectPageNames());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", sites);
        out.put("pages", new ArrayList<>(pages));
        out.put("codes", ConfigCodes.all());
        return out;
    }

    public ConfigItem create(Map<String, Object> body, String operator)
    {
        ConfigItem item = new ConfigItem();
        fill(item, body, null);
        item.setCreateBy(StringUtils.nvl(operator, ""));
        mapper.insert(item);
        return get(item.getId());
    }

    public ConfigItem update(Long id, Map<String, Object> body, String operator)
    {
        ConfigItem item = get(id);
        fill(item, body, id);
        item.setUpdateBy(StringUtils.nvl(operator, ""));
        mapper.update(item);
        return get(id);
    }

    public void delete(Long id)
    {
        get(id);
        mapper.deleteById(id);
    }

    /**
     * 读取配置值：站点自己的优先，没有再用全站通用；都没有返回 null。
     */
    public String value(String configCode, String siteCode)
    {
        if (StringUtils.isNotEmpty(siteCode))
        {
            ConfigItem own = mapper.selectOne(configCode, siteCode.trim());
            if (own != null)
            {
                return own.getConfigValue();
            }
        }
        ConfigItem all = mapper.selectOne(configCode, "");
        return all == null ? null : all.getConfigValue();
    }

    // ------------------------------------------------------------------ 校验

    private void fill(ConfigItem item, Map<String, Object> body, Long excludeId)
    {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> b = body == null ? Map.of() : body;

        String code = str(b, "configCode").toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty())
        {
            errors.put("configCode", "請填寫配置編碼");
        }
        else if (!CODE.matcher(code).matches())
        {
            errors.put("configCode", "配置編碼只能用大寫字母、數字、下劃線，以字母開頭，2–64 個字");
        }

        String siteText = str(b, "siteCode");
        String site = "";
        if (!siteText.isEmpty())
        {
            site = dashboard.canonical(siteText);
            if (site == null)
            {
                errors.put("siteCode", "站點不存在：" + siteText);
                site = siteText;
            }
        }

        if (!errors.containsKey("configCode") && !errors.containsKey("siteCode") && mapper.countKey(code, site, excludeId) > 0)
        {
            errors.put("configCode", "「" + code + "」在" + (site.isEmpty() ? "全站通用" : "站點 " + site) + "下已存在");
        }

        String page = str(b, "pageName");
        if (page.isEmpty())
        {
            errors.put("pageName", "請填寫所屬頁面");
        }
        else if (page.length() > PAGE_MAX)
        {
            errors.put("pageName", "所屬頁面不能超過 " + PAGE_MAX + " 個字");
        }

        String desc = str(b, "description");
        if (desc.isEmpty())
        {
            errors.put("description", "請填寫含義");
        }
        else if (desc.length() > DESC_MAX)
        {
            errors.put("description", "含義不能超過 " + DESC_MAX + " 個字");
        }

        String value = b.get("configValue") == null ? "" : String.valueOf(b.get("configValue")).trim();
        String normalized = null;
        if (value.isEmpty())
        {
            errors.put("configValue", "請填寫配置值（JSON）");
        }
        else if (value.length() > VALUE_MAX)
        {
            errors.put("configValue", "配置值不能超過 64 KB");
        }
        else
        {
            JsonNode node = null;
            try
            {
                node = json.readTree(value);
            }
            catch (RuntimeException e)
            {
                errors.put("configValue", "配置值不是合法的 JSON");
            }
            if (node != null)
            {
                ConfigCodes.Def def = ConfigCodes.get(code);
                String err = def == null ? null : def.validator.apply(node);
                if (err != null)
                {
                    errors.put("configValue", err);
                }
                else
                {
                    normalized = json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
                }
            }
        }

        if (!errors.isEmpty())
        {
            throw new MonitorFieldException(errors);
        }
        item.setConfigCode(code);
        item.setSiteCode(site);
        item.setPageName(page);
        item.setDescription(desc);
        item.setConfigValue(normalized);
    }

    private static String str(Map<String, Object> b, String key)
    {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
