package com.fivetech.dashboard.view;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fivetech.common.utils.SecurityUtils;
import com.fivetech.dashboard.config.DashboardProperties;
import com.fivetech.dashboard.view.domain.SaveViewRequest;
import com.fivetech.dashboard.view.domain.UserView;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.mapper.UserViewMapper;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 个人视图（个人自定义指标集）：每个人在每个站点、每个配置位上存一份自己的选择，服务端合成后下发。
 *
 * <p>用户一律取自登录令牌，站点取自请求头 X-Site-Code，请求里没有 userId。读写都只针对「我在这个站点」的那一份。</p>
 *
 * @author fivetech
 */
@Service
public class UserViewService
{
    private static final Logger log = LoggerFactory.getLogger(UserViewService.class);

    /** 页面 → 读写个人视图需要的查看权限 */
    public static final Map<String, String> PAGE_PERMS = Map.of(
        "PROMO", "dashboard:promo:view",
        "OVERVIEW", "dashboard:overview:view",
        "SUMMARY", "dashboard:metric:summary");

    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ViewDefinitions definitions;

    private final UserViewMapper mapper;

    private final DashboardProperties properties;

    private final ObjectMapper json;

    private final List<ViewItemSource> sources;

    public UserViewService(ViewDefinitions definitions, UserViewMapper mapper, DashboardProperties properties, ObjectMapper json,
        List<ViewItemSource> sources)
    {
        this.definitions = definitions;
        this.mapper = mapper;
        this.properties = properties;
        this.json = json;
        this.sources = sources == null ? List.of() : sources;
    }

    // ------------------------------------------------------------------ 接口

    /** 页面编码规范化（大写）；不认识的页面 404 */
    public static String page(String page)
    {
        String p = page == null ? "" : page.trim().toUpperCase(Locale.ROOT);
        if (!PAGE_PERMS.containsKey(p))
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "頁面不存在：" + page);
        }
        return p;
    }

    /** 配置位所属页面（权限校验用）；配置位不存在或已停用 404 */
    public String pageOfSlot(String slotCode)
    {
        return slotDef(slotCode).getSlot().getPageCode().toUpperCase(Locale.ROOT);
    }

    /** GET /dashboard/views?page=：本页全部配置位 */
    public Map<String, Object> pageViews(String page)
    {
        String p = page(page);
        Long user = SecurityUtils.getUserId();
        String site = site();
        Map<String, UserView> mine = new HashMap<>();
        List<SlotDef> defs = definitions.ofPage(p);
        if (!defs.isEmpty())
        {
            for (UserView uv : mapper.selectUserViews(user, site))
            {
                mine.put(uv.getSlotCode(), uv);
            }
        }
        List<Map<String, Object>> slots = new ArrayList<>();
        for (SlotDef d : defs)
        {
            slots.add(resolve(d, site, mine.get(d.code())).toMap());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", p);
        out.put("siteCode", site);
        out.put("slots", slots);
        return out;
    }

    /** GET /dashboard/views/{slotCode} */
    public Map<String, Object> slotView(String slotCode)
    {
        SlotDef d = slotDef(slotCode);
        String site = site();
        return resolve(d, site, mapper.selectUserView(SecurityUtils.getUserId(), site, d.code())).toMap();
    }

    /** POST /dashboard/views/{slotCode}：返回保存后的完整结构 */
    public Map<String, Object> save(String slotCode, SaveViewRequest req)
    {
        SlotDef d = slotDef(slotCode);
        Long user = SecurityUtils.getUserId();
        String site = site();
        UserView row = mapper.selectUserView(user, site, d.code());
        UserViewResolver.Stored existing = stored(row);
        SlotView current = resolve(d, site, row);
        int attrsBytes = req == null || req.getAttrs() == null ? 0 : write(req.getAttrs()).getBytes(StandardCharsets.UTF_8).length;
        UserViewResolver.Stored next = UserViewResolver.normalize(current, req, existing, attrsBytes);
        if (next.empty())
        {
            mapper.deleteUserView(user, site, d.code());
            return UserViewResolver.resolve(d, infos(d, site), siteDefaults(d, site), null).toMap();
        }
        UserView v = new UserView();
        v.setUserId(user);
        v.setSiteCode(site);
        v.setSlotCode(d.code());
        v.setMode(next.getMode());
        v.setPresetCode(next.getPresetCode());
        v.setItems(next.getItems() == null ? null : write(next.getItems()));
        v.setLastCustom(next.getLastCustom() == null || next.getLastCustom().isEmpty() ? null : write(next.getLastCustom()));
        v.setAttrs(next.getAttrs() == null || next.getAttrs().isEmpty() ? null : write(next.getAttrs()));
        mapper.upsertUserView(v);
        return UserViewResolver.resolve(d, infos(d, site), siteDefaults(d, site), next).toMap();
    }

    /** POST /dashboard/views/{slotCode}/reset：清空我在本站这个配置位的全部设置（含小状态） */
    public Map<String, Object> reset(String slotCode)
    {
        SlotDef d = slotDef(slotCode);
        String site = site();
        mapper.deleteUserView(SecurityUtils.getUserId(), site, d.code());
        return UserViewResolver.resolve(d, infos(d, site), siteDefaults(d, site), null).toMap();
    }

    /**
     * 取数 / 导出接口不传项列表时用：当前用户在当前站点的生效列表。
     * 拿不到（视图表没建、配置位停用、没有登录用户、读库失败）返回 null，调用方按原来的默认处理。
     */
    public List<String> itemsOf(String slotCode)
    {
        try
        {
            SlotDef d = definitions.get(slotCode);
            if (d == null)
            {
                return null;
            }
            Long user = loginUserId();
            if (user == null)
            {
                // 没有登录用户（定时任务、订阅推送等）：按默认
                return null;
            }
            String site = site();
            List<String> items = resolve(d, site, mapper.selectUserView(user, site, d.code())).getItems();
            return items.isEmpty() ? null : items;
        }
        catch (RuntimeException e)
        {
            log.warn("[user-view] 取 {} 的个人视图失败，按默认：{}", slotCode, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 当前登录用户；没有登录上下文返回 null（不抛异常） */
    static Long loginUserId()
    {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof com.fivetech.common.core.domain.model.LoginUser))
        {
            return null;
        }
        return ((com.fivetech.common.core.domain.model.LoginUser) auth.getPrincipal()).getUserId();
    }

    SlotDef slotDef(String slotCode)
    {
        SlotDef d = definitions.get(slotCode);
        if (d == null)
        {
            throw new MonitorException(MonitorErrorCode.NOT_FOUND, "配置位不存在或已停用");
        }
        return d;
    }

    String site()
    {
        return properties.currentSite().trim().toUpperCase(Locale.ROOT);
    }

    SlotView resolve(SlotDef d, String site, UserView row)
    {
        return UserViewResolver.resolve(d, infos(d, site), siteDefaults(d, site), stored(row));
    }

    private ViewItemSource source(ViewSlot slot)
    {
        for (ViewItemSource s : sources)
        {
            if (s.supports(slot))
            {
                return s;
            }
        }
        return null;
    }

    private Map<String, ViewItemSource.ItemInfo> infos(SlotDef d, String site)
    {
        ViewItemSource s = source(d.getSlot());
        return s == null ? null : s.items(d.getSlot(), site);
    }

    private List<String> siteDefaults(SlotDef d, String site)
    {
        ViewItemSource s = source(d.getSlot());
        return s == null ? null : s.siteDefaults(d.getSlot(), site);
    }

    /** 库里的记录 → 已解析的视图；某个 JSON 列坏了只丢那一列 */
    UserViewResolver.Stored stored(UserView row)
    {
        if (row == null)
        {
            return null;
        }
        return new UserViewResolver.Stored(row.getMode(), row.getPresetCode(), read(row.getItems(), LIST, row),
            read(row.getLastCustom(), LIST, row), read(row.getAttrs(), MAP, row));
    }

    private <T> T read(String text, TypeReference<T> type, UserView row)
    {
        if (text == null || text.trim().isEmpty())
        {
            return null;
        }
        try
        {
            return json.readValue(text, type);
        }
        catch (RuntimeException e)
        {
            log.warn("[user-view] 个人视图 id={} 的 JSON 列解析失败，已忽略：{}", row.getId(), e.getMessage());
            return null;
        }
    }

    private String write(Object o)
    {
        return json.writeValueAsString(o);
    }
}
