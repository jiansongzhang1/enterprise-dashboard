package com.fivetech.dashboard.view;

import java.util.List;
import java.util.Map;
import com.fivetech.dashboard.view.domain.ViewSlot;

/**
 * 配置位可选项的来源：告诉个人视图哪些项现在还在、叫什么、本站能不能用。由接入的页面提供（如推广分析的指标目录）。
 *
 * <p>没有任何来源认领的配置位：可选项全部取 dashboard_view_slot_item，全部可用。</p>
 *
 * @author fivetech
 */
public interface ViewItemSource
{
    /** 一项的现状 */
    final class ItemInfo
    {
        private final String label;
        private final boolean available;
        private final String reason;

        private ItemInfo(String label, boolean available, String reason)
        {
            this.label = label;
            this.available = available;
            this.reason = reason;
        }

        public static ItemInfo of(String label)
        {
            return new ItemInfo(label, true, null);
        }

        /** 本站暂不可用（弹窗里灰显；用户选过的进 skipped，本站重新提供后自动恢复） */
        public static ItemInfo unavailable(String label, String reason)
        {
            return new ItemInfo(label, false, reason);
        }

        public String getLabel() { return label; }
        public boolean isAvailable() { return available; }
        public String getReason() { return reason; }
    }

    boolean supports(ViewSlot slot);

    /**
     * 本站各项的现状。<b>不在返回里的项视为已下线</b>（指标停用等）：不作为可选项，用户选过的也直接跳过（库里的记录不动）。
     */
    Map<String, ItemInfo> items(ViewSlot slot, String site);

    /** 站点默认（站点排列）；null = 本站没有单独的默认 */
    default List<String> siteDefaults(ViewSlot slot, String site)
    {
        return null;
    }
}
