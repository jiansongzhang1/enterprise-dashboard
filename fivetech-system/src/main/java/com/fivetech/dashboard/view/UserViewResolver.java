package com.fivetech.dashboard.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fivetech.dashboard.view.domain.SaveViewRequest;
import com.fivetech.dashboard.view.domain.UserView;
import com.fivetech.dashboard.view.domain.ViewPreset;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.domain.ViewSlotItem;
import com.fivetech.monitor.exception.MonitorFieldException;

/**
 * 个人视图的合成与保存校验（纯逻辑，不读库）。读视图、保存后的返回、取数接口不传项列表时都走 {@link #resolve}，结果只有一种。
 *
 * <pre>
 * resolve(user, site, slot):
 *   1. 可选项 C = slot_item(启用) 按 sort_no；项来源不认的（指标停用等）去掉；本站不可用的留在 C 里但 available = false
 *   2. 默认 D = 站点排列（有就用）→ is_default 的预置组合 → default_sort 不为空的项；只留可用项
 *   3. 个人 V：没有 / DEFAULT → D；PRESET → 该组合（组合已删 → D）；CUSTOM → V.items
 *   4. 清理：去重；已下线的跳过；本站不可用的放进 skipped（不改库）
 *   5. 整理：group_adjacent → 同组靠拢（组先后按组内第一项的位置）；违反顺序规则 → 把 before 移到 after 前面
 *   6. 兜底：少于 min → D（提示 VIEW_FALLBACK）；多于 max → 截断
 * </pre>
 *
 * @author fivetech
 */
public final class UserViewResolver
{
    /** attrs 序列化后的上限（字节） */
    public static final int MAX_ATTRS_BYTES = 4096;

    public static final String NOTICE_FALLBACK = "VIEW_FALLBACK";

    private UserViewResolver()
    {
    }

    /** 库里的个人视图（JSON 已解析） */
    public static final class Stored
    {
        String mode;
        String presetCode;
        List<String> items;
        List<String> lastCustom;
        Map<String, Object> attrs;

        public Stored()
        {
        }

        public Stored(String mode, String presetCode, List<String> items, List<String> lastCustom, Map<String, Object> attrs)
        {
            this.mode = mode;
            this.presetCode = presetCode;
            this.items = items;
            this.lastCustom = lastCustom;
            this.attrs = attrs;
        }

        public String getMode() { return mode; }
        public String getPresetCode() { return presetCode; }
        public List<String> getItems() { return items; }
        public List<String> getLastCustom() { return lastCustom; }
        public Map<String, Object> getAttrs() { return attrs; }

        /** 跟随默认、没有小状态、没有最近一次自定义：不用存（有记录就删掉） */
        public boolean empty()
        {
            return UserView.DEFAULT.equals(mode) && (attrs == null || attrs.isEmpty()) && (lastCustom == null || lastCustom.isEmpty());
        }
    }

    // ------------------------------------------------------------------ 合成

    /**
     * @param infos        项来源给的现状；null = 没有来源，全部按 slot_item、全部可用
     * @param siteDefaults 站点默认；null / 空 = 没有
     * @param stored       个人视图；null = 没有记录
     */
    public static SlotView resolve(SlotDef def, Map<String, ViewItemSource.ItemInfo> infos, List<String> siteDefaults, Stored stored)
    {
        ViewSlot slot = def.getSlot();
        SlotView v = new SlotView();
        v.slotCode = slot.getSlotCode();
        v.pageCode = slot.getPageCode();
        v.name = slot.getName();
        v.kind = slot.getKind();
        v.minItems = slot.min();
        v.maxItems = slot.getMaxItems();
        v.groupAdjacent = slot.adjacent();
        v.rules = new ArrayList<>(def.getRules());

        // 1. 可选项
        Set<String> available = new LinkedHashSet<>();
        Map<String, String> groupOf = new HashMap<>();
        for (ViewSlotItem it : def.getItems())
        {
            ViewItemSource.ItemInfo info = infos == null ? null : infos.get(it.getItemCode());
            if (infos != null && info == null)
            {
                continue;
            }
            String label = notBlank(it.getItemName()) ? it.getItemName()
                : info != null && notBlank(info.getLabel()) ? info.getLabel() : it.getItemCode();
            boolean ok = info == null || info.isAvailable();
            v.candidates.add(new SlotView.Candidate(it.getItemCode(), label, it.getGroupCode(), it.getGroupName(), ok,
                ok ? null : info.getReason()));
            groupOf.put(it.getItemCode(), it.getGroupCode() == null ? "" : it.getGroupCode());
            if (ok)
            {
                available.add(it.getItemCode());
            }
        }
        Set<String> known = new LinkedHashSet<>();
        v.candidates.forEach(c -> known.add(c.code));

        // 预置组合（去掉不可用项）
        ViewPreset defaultPreset = null;
        for (ViewPreset p : def.getPresets())
        {
            List<String> pItems = arrange(keep(splitCsv(p.getItems()), available), v.groupAdjacent, groupOf, v.rules);
            v.presets.add(new SlotView.Preset(p.getPresetCode(), p.getName(), p.getTip(), cap(pItems, v.maxItems), p.defaultPreset()));
            if (defaultPreset == null && p.defaultPreset())
            {
                defaultPreset = p;
            }
        }

        // 2. 默认
        List<String> site = siteDefaults == null ? List.of() : keep(siteDefaults, available);
        if (!site.isEmpty())
        {
            v.defaultItems = arrange(site, v.groupAdjacent, groupOf, v.rules);
            v.defaultPresetCode = null;
        }
        else if (defaultPreset != null)
        {
            v.defaultItems = v.preset(defaultPreset.getPresetCode()).items;
            v.defaultPresetCode = defaultPreset.getPresetCode();
        }
        else
        {
            List<ViewSlotItem> ds = new ArrayList<>();
            for (ViewSlotItem it : def.getItems())
            {
                if (it.getDefaultSort() != null && available.contains(it.getItemCode()))
                {
                    ds.add(it);
                }
            }
            ds.sort(Comparator.comparing(ViewSlotItem::getDefaultSort));
            List<String> d = new ArrayList<>();
            ds.forEach(it -> d.add(it.getItemCode()));
            v.defaultItems = arrange(d, v.groupAdjacent, groupOf, v.rules);
        }
        v.defaultItems = cap(v.defaultItems, v.maxItems);

        // 3–4. 个人
        String mode = stored == null || stored.mode == null ? UserView.DEFAULT : stored.mode;
        List<String> list;
        boolean chosen = false;
        if (UserView.PRESET.equals(mode) && stored.presetCode != null && v.preset(stored.presetCode) != null)
        {
            list = new ArrayList<>(v.preset(stored.presetCode).items);
            chosen = true;
        }
        else if (UserView.CUSTOM.equals(mode) && stored.items != null)
        {
            list = new ArrayList<>();
            for (String c : new LinkedHashSet<>(stored.items))
            {
                if (available.contains(c))
                {
                    list.add(c);
                }
                else if (known.contains(c))
                {
                    v.skipped.add(c);
                }
            }
            chosen = true;
        }
        else
        {
            list = new ArrayList<>(v.defaultItems);
        }

        // 5. 整理
        list = arrange(list, v.groupAdjacent, groupOf, v.rules);

        // 6. 兜底
        if (chosen && (list.size() < v.minItems || list.isEmpty()))
        {
            list = new ArrayList<>(v.defaultItems);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("code", NOTICE_FALLBACK);
            n.put("message", "你選的項目已不可用，暫用默認");
            v.notices.add(n);
        }
        v.items = cap(list, v.maxItems);

        List<String> last = stored == null || stored.lastCustom == null ? List.of() : keep(stored.lastCustom, available);
        v.lastCustom = last.isEmpty() ? null : arrange(last, v.groupAdjacent, groupOf, v.rules);
        v.attrs = stored == null || stored.attrs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored.attrs);
        return v;
    }

    /** 同组靠拢（组先后按组内第一项出现的位置，组内保持原顺序）；再按顺序规则把 before 移到 after 前面 */
    static List<String> arrange(List<String> list, boolean adjacent, Map<String, String> groupOf, List<SlotDef.OrderRule> rules)
    {
        List<String> out = new ArrayList<>(list);
        if (adjacent)
        {
            Map<String, List<String>> byGroup = new LinkedHashMap<>();
            for (String c : out)
            {
                byGroup.computeIfAbsent(groupOf.getOrDefault(c, ""), k -> new ArrayList<>()).add(c);
            }
            out = new ArrayList<>();
            for (List<String> g : byGroup.values())
            {
                out.addAll(g);
            }
        }
        for (SlotDef.OrderRule r : rules)
        {
            int a = out.indexOf(r.getBefore());
            int b = out.indexOf(r.getAfter());
            if (a >= 0 && b >= 0 && a > b)
            {
                out.remove(a);
                out.add(b, r.getBefore());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 按请求算出要存的个人视图：校验不过抛 400（{@code data.fields} 指向 items / presetCode / attrs / mode）；
     * CUSTOM 的内容等于默认 / 某个预置组合时自动归为 DEFAULT / PRESET。
     *
     * @param current  当前合成结果（候选项、默认、预置组合都从这里取）
     * @param existing 库里的记录；null = 没有
     * @param attrsBytes 请求 attrs 序列化后的字节数（没传 attrs 时忽略）
     */
    public static Stored normalize(SlotView current, SaveViewRequest req, Stored existing, int attrsBytes)
    {
        if (req == null)
        {
            throw MonitorFieldException.of("mode", "請求體不能為空");
        }
        if (req.getAttrs() != null && attrsBytes > MAX_ATTRS_BYTES)
        {
            throw MonitorFieldException.of("attrs", "attrs 不能超過 4 KB");
        }
        Stored out = new Stored();
        Stored base = existing == null ? new Stored(UserView.DEFAULT, null, null, null, null) : existing;
        out.mode = base.mode == null ? UserView.DEFAULT : base.mode;
        out.presetCode = base.presetCode;
        out.items = base.items;
        out.lastCustom = base.lastCustom;
        out.attrs = req.getAttrs() != null ? new LinkedHashMap<>(req.getAttrs()) : base.attrs;

        String mode = req.getMode() == null ? null : req.getMode().trim().toUpperCase(java.util.Locale.ROOT);
        if (mode == null || mode.isEmpty())
        {
            if (req.getAttrs() == null)
            {
                throw MonitorFieldException.of("mode", "mode 與 attrs 不能都為空");
            }
            return out;
        }
        switch (mode)
        {
            case UserView.DEFAULT:
                setDefault(out);
                break;
            case UserView.PRESET:
            {
                String code = req.getPresetCode() == null ? "" : req.getPresetCode().trim();
                SlotView.Preset p = current.preset(code);
                if (p == null)
                {
                    throw MonitorFieldException.of("presetCode", "預置組合不存在：" + code);
                }
                if (code.equals(current.defaultPresetCode) || same(p.items, current.defaultItems))
                {
                    setDefault(out);
                }
                else
                {
                    out.mode = UserView.PRESET;
                    out.presetCode = code;
                    out.items = null;
                }
                break;
            }
            case UserView.CUSTOM:
            {
                List<String> items = req.getItems() == null ? List.of() : trimAll(req.getItems());
                String error = validate(current, items);
                if (error != null)
                {
                    throw MonitorFieldException.of("items", error);
                }
                SlotView.Preset hit = null;
                for (SlotView.Preset p : current.presets)
                {
                    if (same(items, p.items))
                    {
                        hit = p;
                        break;
                    }
                }
                if (same(items, current.defaultItems))
                {
                    setDefault(out);
                }
                else if (hit != null)
                {
                    out.mode = UserView.PRESET;
                    out.presetCode = hit.code;
                    out.items = null;
                }
                else
                {
                    out.mode = UserView.CUSTOM;
                    out.presetCode = null;
                    out.items = new ArrayList<>(items);
                    out.lastCustom = new ArrayList<>(items);
                }
                break;
            }
            default:
                throw MonitorFieldException.of("mode", "mode 只能是 DEFAULT / PRESET / CUSTOM");
        }
        return out;
    }

    private static void setDefault(Stored s)
    {
        s.mode = UserView.DEFAULT;
        s.presetCode = null;
        s.items = null;
    }

    /** 与前端即时校验同一套规则、同一顺序，返回第一条错误；null = 通过 */
    static String validate(SlotView v, List<String> items)
    {
        if (items.size() < Math.max(1, v.minItems))
        {
            return v.name + "至少選 " + Math.max(1, v.minItems) + " 個";
        }
        if (v.maxItems != null && items.size() > v.maxItems)
        {
            return v.name + "最多選 " + v.maxItems + " 個";
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String c : items)
        {
            if (!seen.add(c))
            {
                return "有重複的項：" + c;
            }
        }
        Map<String, SlotView.Candidate> byCode = new HashMap<>();
        v.candidates.forEach(c -> byCode.put(c.code, c));
        for (String c : items)
        {
            SlotView.Candidate cand = byCode.get(c);
            if (cand == null)
            {
                return "不認識的項：" + c;
            }
            if (!cand.available)
            {
                return "本站不可用：" + cand.label;
            }
        }
        for (SlotDef.OrderRule r : v.rules)
        {
            int a = items.indexOf(r.getBefore());
            int b = items.indexOf(r.getAfter());
            if (a >= 0 && b >= 0 && a > b)
            {
                return r.getMessage();
            }
        }
        if (v.groupAdjacent)
        {
            Set<String> done = new LinkedHashSet<>();
            String prev = null;
            for (String c : items)
            {
                String g = byCode.get(c).groupCode == null ? "" : byCode.get(c).groupCode;
                if (!g.equals(prev) && done.contains(g))
                {
                    return "同一組的列需要相鄰";
                }
                done.add(g);
                prev = g;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具

    static boolean same(List<String> a, List<String> b)
    {
        return a != null && b != null && a.equals(b);
    }

    static List<String> keep(List<String> list, Set<String> allowed)
    {
        List<String> out = new ArrayList<>();
        for (String c : new LinkedHashSet<>(list))
        {
            if (allowed.contains(c))
            {
                out.add(c);
            }
        }
        return out;
    }

    static List<String> cap(List<String> list, Integer max)
    {
        return max != null && max >= 0 && list.size() > max ? new ArrayList<>(list.subList(0, max)) : new ArrayList<>(list);
    }

    static List<String> trimAll(List<String> list)
    {
        List<String> out = new ArrayList<>();
        for (String s : list)
        {
            out.add(s == null ? "" : s.trim());
        }
        return out;
    }

    public static List<String> splitCsv(String csv)
    {
        List<String> out = new ArrayList<>();
        if (csv == null)
        {
            return out;
        }
        for (String s : csv.split(","))
        {
            if (!s.trim().isEmpty())
            {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static boolean notBlank(String s)
    {
        return s != null && !s.trim().isEmpty();
    }
}
