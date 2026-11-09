package com.fivetech.dashboard.view;

import java.util.List;
import com.fivetech.dashboard.view.domain.ViewPreset;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.domain.ViewSlotItem;

/**
 * 一个配置位的完整定义（内存快照里的一项，不可变）：配置位、启用的可选项（按 sort_no）、预置组合（按 sort_no）、顺序规则。
 *
 * @author fivetech
 */
public final class SlotDef
{
    /** 顺序规则：before 须在 after 之前（之上） */
    public static final class OrderRule
    {
        private final String before;
        private final String after;
        private final String message;

        public OrderRule(String before, String after, String message)
        {
            this.before = before;
            this.after = after;
            this.message = message;
        }

        public String getBefore() { return before; }
        public String getAfter() { return after; }
        public String getMessage() { return message; }
    }

    private final ViewSlot slot;
    private final List<ViewSlotItem> items;
    private final List<ViewPreset> presets;
    private final List<OrderRule> rules;

    public SlotDef(ViewSlot slot, List<ViewSlotItem> items, List<ViewPreset> presets, List<OrderRule> rules)
    {
        this.slot = slot;
        this.items = List.copyOf(items);
        this.presets = List.copyOf(presets);
        this.rules = List.copyOf(rules);
    }

    public ViewSlot getSlot() { return slot; }
    public List<ViewSlotItem> getItems() { return items; }
    public List<ViewPreset> getPresets() { return presets; }
    public List<OrderRule> getRules() { return rules; }
    public String code() { return slot.getSlotCode(); }
}
