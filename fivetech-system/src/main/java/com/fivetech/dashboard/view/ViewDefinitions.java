package com.fivetech.dashboard.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fivetech.dashboard.view.domain.ViewPreset;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.domain.ViewSlotItem;
import com.fivetech.dashboard.view.mapper.UserViewMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 个人视图的定义表（dashboard_view_slot / _slot_item / _preset）的内存快照 + 热加载。
 *
 * <p>与指标注册表同一套做法：启动时加载；每隔 {@code dashboard.config-refresh-seconds} 秒（默认 60，0 = 关闭）查一次三张表的版本
 * （行数 + 最后修改时间），变了就整体换掉快照。读不到（表还没建）或加载失败只记警告，继续用旧快照（启动时为空：视图接口 404，
 * 取数接口按原来的默认）。</p>
 *
 * @author fivetech
 */
@Component
public class ViewDefinitions
{
    private static final Logger log = LoggerFactory.getLogger(ViewDefinitions.class);

    private final UserViewMapper mapper;

    private final ObjectMapper json;

    private final int seconds;

    /** 启用的配置位：slot_code → 定义 */
    private volatile Map<String, SlotDef> slots = Map.of();

    private volatile String version;

    private volatile String lastVersionError;

    private ScheduledExecutorService executor;

    public ViewDefinitions(UserViewMapper mapper, ObjectMapper json, @Value("${dashboard.config-refresh-seconds:60}") int seconds)
    {
        this.mapper = mapper;
        this.json = json;
        this.seconds = seconds;
    }

    @PostConstruct
    public void start()
    {
        version = currentVersion();
        reload();
        if (seconds <= 0)
        {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "view-config-refresh");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::check, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop()
    {
        if (executor != null)
        {
            executor.shutdownNow();
        }
    }

    /** 启用的配置位；停用或不存在返回 null */
    public SlotDef get(String slotCode)
    {
        return slotCode == null ? null : slots.get(slotCode.trim());
    }

    /** 某页面启用的配置位，按 sort_no */
    public List<SlotDef> ofPage(String pageCode)
    {
        List<SlotDef> out = new ArrayList<>();
        for (SlotDef d : slots.values())
        {
            if (d.getSlot().getPageCode() != null && d.getSlot().getPageCode().equalsIgnoreCase(pageCode))
            {
                out.add(d);
            }
        }
        return out;
    }

    void check()
    {
        String now = currentVersion();
        if (now == null || now.equals(version))
        {
            return;
        }
        if (reload())
        {
            log.info("[user-view] 视图定义有变更（{} → {}），已重新加载", version, now);
            version = now;
        }
    }

    private String currentVersion()
    {
        try
        {
            return mapper.selectDefinitionVersion();
        }
        catch (RuntimeException e)
        {
            // 表还没建时每分钟都会失败：同样的错误只记一次
            String msg = String.valueOf(e.getMessage());
            if (!msg.equals(lastVersionError))
            {
                log.warn("[user-view] 读取视图定义版本失败（是否已执行 sql/release_next_postgresql.sql ⑦）：{}", msg);
                lastVersionError = msg;
            }
            return null;
        }
    }

    /** @return 是否加载成功 */
    public synchronized boolean reload()
    {
        try
        {
            this.slots = build(mapper.selectSlots(), mapper.selectSlotItems(), mapper.selectPresets());
            log.info("[user-view] 视图定义已加载：配置位 {} 个 {}", slots.size(), slots.keySet());
            return true;
        }
        catch (RuntimeException e)
        {
            log.warn("[user-view] 加载视图定义失败，继续用旧定义（是否已执行 sql/release_next_postgresql.sql ⑦）：{}", e.getMessage());
            return false;
        }
    }

    /** 组装快照（包内可见供测试）：只要启用的配置位与可选项；预置组合里不认识的项、违反规则的组合只记警告 */
    Map<String, SlotDef> build(List<ViewSlot> slotRows, List<ViewSlotItem> itemRows, List<ViewPreset> presetRows)
    {
        Map<String, List<ViewSlotItem>> items = new HashMap<>();
        for (ViewSlotItem it : itemRows)
        {
            if (it.isEnabled())
            {
                items.computeIfAbsent(it.getSlotCode(), k -> new ArrayList<>()).add(it);
            }
        }
        Map<String, List<ViewPreset>> presets = new HashMap<>();
        for (ViewPreset p : presetRows)
        {
            presets.computeIfAbsent(p.getSlotCode(), k -> new ArrayList<>()).add(p);
        }
        Map<String, SlotDef> out = new LinkedHashMap<>();
        for (ViewSlot s : slotRows)
        {
            if (!s.isEnabled())
            {
                continue;
            }
            List<ViewSlotItem> its = new ArrayList<>(items.getOrDefault(s.getSlotCode(), List.of()));
            its.sort((a, b) -> Integer.compare(a.getSortNo() == null ? Integer.MAX_VALUE : a.getSortNo(),
                b.getSortNo() == null ? Integer.MAX_VALUE : b.getSortNo()));
            List<ViewPreset> ps = new ArrayList<>(presets.getOrDefault(s.getSlotCode(), List.of()));
            ps.sort((a, b) -> Integer.compare(a.getSortNo() == null ? Integer.MAX_VALUE : a.getSortNo(),
                b.getSortNo() == null ? Integer.MAX_VALUE : b.getSortNo()));
            SlotDef def = new SlotDef(s, its, ps, rules(s));
            checkPresets(def);
            out.put(s.getSlotCode(), def);
        }
        return Collections.unmodifiableMap(out);
    }

    private List<SlotDef.OrderRule> rules(ViewSlot s)
    {
        List<SlotDef.OrderRule> out = new ArrayList<>();
        if (s.getRules() == null || s.getRules().trim().isEmpty())
        {
            return out;
        }
        try
        {
            JsonNode arr = json.readTree(s.getRules());
            for (JsonNode n : arr)
            {
                String before = n.path("before").asString();
                String after = n.path("after").asString();
                if (!before.isEmpty() && !after.isEmpty())
                {
                    String msg = n.path("message").asString();
                    out.add(new SlotDef.OrderRule(before, after, msg.isEmpty() ? before + " 須在 " + after + " 之前" : msg));
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("[user-view] 配置位 {} 的 rules 不是合法 JSON，已忽略：{}", s.getSlotCode(), e.getMessage());
        }
        return out;
    }

    private static void checkPresets(SlotDef def)
    {
        Map<String, String> groups = new HashMap<>();
        def.getItems().forEach(i -> groups.put(i.getItemCode(), i.getGroupCode() == null ? "" : i.getGroupCode()));
        for (ViewPreset p : def.getPresets())
        {
            List<String> codes = UserViewResolver.splitCsv(p.getItems());
            List<String> unknown = new ArrayList<>();
            codes.forEach(c -> {
                if (!groups.containsKey(c))
                {
                    unknown.add(c);
                }
            });
            if (!unknown.isEmpty())
            {
                log.warn("[user-view] 预置组合 {}.{} 有不在可选项里的项（会被忽略）：{}", def.code(), p.getPresetCode(), unknown);
            }
            List<String> arranged = UserViewResolver.arrange(codes, def.getSlot().adjacent(), groups, def.getRules());
            if (!arranged.equals(codes))
            {
                log.warn("[user-view] 预置组合 {}.{} 不满足同组相邻 / 顺序规则，下发时会按规则整理", def.code(), p.getPresetCode());
            }
        }
    }
}
