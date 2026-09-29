package com.fivetech.dashboard.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.dashboard.domain.MetricCardConfig;
import com.fivetech.dashboard.domain.PageMetricConfig;
import com.fivetech.dashboard.enums.DashboardPage;

/**
 * 看板配置校验器。
 *
 * <p>配置化换来了「改口径不发版」，代价是<b>丢掉了编译期检查</b>。
 * 这个类把那份检查补回来，在启动时一次性跑完。</p>
 *
 * <p>分两档：</p>
 * <ul>
 *   <li><b>ERROR</b> —— 会让页面显示错数或直接崩的，抛异常让应用起不来。
 *       宁可起不来，也别等运营点开页面才发现 GGR 是空的。</li>
 *   <li><b>WARN</b> —— 看起来不对但可能是有意为之的，只记日志。
 *       比如两个页面的核心指标集分叉了——那是两个页面各自的决策，
 *       不该由校验器替运营做主。</li>
 * </ul>
 *
 * @author fivetech
 */
@Component
public class DashboardConfigValidator
{
    private static final Logger log = LoggerFactory.getLogger(DashboardConfigValidator.class);

    /**
     * 校验结果。errors 非空即视为配置不可用。
     */
    public static final class Result
    {
        private final List<String> errors = new ArrayList<>();

        private final List<String> warnings = new ArrayList<>();

        public List<String> getErrors()
        {
            return errors;
        }

        public List<String> getWarnings()
        {
            return warnings;
        }

        public boolean hasErrors()
        {
            return !errors.isEmpty();
        }
    }

    /**
     * @param cards        全部指标卡（含停用）
     * @param pageMetrics  全部页面关系（含停用）
     * @param groupCodes   启用的分组编码
     */
    public Result validate(List<MetricCardConfig> cards, List<PageMetricConfig> pageMetrics,
            List<String> groupCodes)
    {
        Result result = new Result();
        Map<String, MetricCardConfig> byCode = new LinkedHashMap<>();
        for (MetricCardConfig card : cards)
        {
            if (byCode.put(card.getMetricCode(), card) != null)
            {
                // 唯一约束理论上挡住了，但配置可能从别的环境同步过来
                result.getErrors().add("指标编码重复：" + card.getMetricCode());
            }
        }
        if (byCode.isEmpty())
        {
            result.getErrors().add(
                "dashboard_metric_card 一行配置都没有。请先执行 sql/dashboard_metric_card_postgresql.sql");
            return result;
        }

        checkGroups(byCode, groupCodes, result);
        checkOperands(byCode, result);
        checkCycles(byCode, result);
        checkSubtractUnits(byCode, result);
        checkPages(byCode, pageMetrics, result);
        return result;
    }

    /** 启动时调用：有 ERROR 就让应用起不来 */
    public void validateOrFail(List<MetricCardConfig> cards, List<PageMetricConfig> pageMetrics,
            List<String> groupCodes)
    {
        Result result = validate(cards, pageMetrics, groupCodes);
        for (String warning : result.getWarnings())
        {
            log.warn("[dashboard-config] {}", warning);
        }
        if (result.hasErrors())
        {
            for (String error : result.getErrors())
            {
                log.error("[dashboard-config] {}", error);
            }
            throw new IllegalStateException("看板配置校验未通过，共 " + result.getErrors().size()
                + " 项错误，详见上方日志。修正 dashboard_metric_card / dashboard_page_metric 后重启。");
        }
        log.info("[dashboard-config] 校验通过：指标 {} 个，页面关系 {} 条，告警 {} 项",
            cards.size(), pageMetrics.size(), result.getWarnings().size());
    }

    // ===================== 各项检查 =====================

    private void checkGroups(Map<String, MetricCardConfig> byCode, List<String> groupCodes, Result result)
    {
        if (groupCodes == null || groupCodes.isEmpty())
        {
            result.getWarnings().add("dashboard_metric_group 为空，分组名将退化为分组编码");
            return;
        }
        Set<String> known = new HashSet<>(groupCodes);
        for (MetricCardConfig card : byCode.values())
        {
            if (card.isEnabled() && StringUtils.isNotEmpty(card.getGroupCode())
                && !known.contains(card.getGroupCode()))
            {
                result.getWarnings().add("指标 " + card.getMetricCode()
                    + " 的分组 " + card.getGroupCode() + " 不在分组字典里，前端会显示成编码");
            }
        }
    }

    /**
     * 派生指标的操作数必须存在且启用。
     * <p>外键只保证「存在」，管不住有人把分子停用——那会让派生指标静默变 null，
     * 页面不报错，只是空着。
     */
    private void checkOperands(Map<String, MetricCardConfig> byCode, Result result)
    {
        for (MetricCardConfig card : byCode.values())
        {
            if (!card.isEnabled() || !card.isDerived())
            {
                continue;
            }
            for (String operand : new String[] { card.getLeftCode(), card.getRightCode() })
            {
                if (StringUtils.isEmpty(operand))
                {
                    result.getErrors().add("派生指标 " + card.getMetricCode()
                        + " 的操作数为空，calc_type=" + card.getCalcType());
                    continue;
                }
                MetricCardConfig ref = byCode.get(operand);
                if (ref == null)
                {
                    result.getErrors().add("派生指标 " + card.getMetricCode()
                        + " 引用了不存在的指标 " + operand);
                }
                else if (!ref.isEnabled())
                {
                    result.getErrors().add("派生指标 " + card.getMetricCode()
                        + " 的操作数 " + operand + " 已停用，该指标会静默变成空值");
                }
            }
        }
    }

    /**
     * 公式依赖不能成环。
     * <p>{@code a = b ÷ c} 配上 {@code c = a − d} 这种环，CHECK 和外键都拦不住，
     * 解析时直接栈溢出。只能在这里用染色 DFS 挡。
     */
    private void checkCycles(Map<String, MetricCardConfig> byCode, Result result)
    {
        // 0 未访问 / 1 在当前递归栈上 / 2 已完成
        Map<String, Integer> state = new HashMap<>();
        for (String code : byCode.keySet())
        {
            List<String> stack = new ArrayList<>();
            detectCycle(code, byCode, state, stack, result);
        }
    }

    private boolean detectCycle(String code, Map<String, MetricCardConfig> byCode,
            Map<String, Integer> state, List<String> stack, Result result)
    {
        Integer mark = state.get(code);
        if (mark != null && mark == 2)
        {
            return false;
        }
        if (mark != null && mark == 1)
        {
            int from = stack.indexOf(code);
            List<String> loop = new ArrayList<>(stack.subList(Math.max(0, from), stack.size()));
            loop.add(code);
            result.getErrors().add("指标公式存在循环依赖：" + String.join(" → ", loop));
            return true;
        }
        MetricCardConfig card = byCode.get(code);
        if (card == null || !card.isDerived())
        {
            state.put(code, 2);
            return false;
        }
        state.put(code, 1);
        stack.add(code);
        boolean found = false;
        for (String operand : new String[] { card.getLeftCode(), card.getRightCode() })
        {
            if (StringUtils.isNotEmpty(operand) && byCode.containsKey(operand))
            {
                found |= detectCycle(operand, byCode, state, stack, result);
            }
        }
        stack.remove(stack.size() - 1);
        state.put(code, 2);
        return found;
    }

    /**
     * SUBTRACT 的两个操作数必须同量纲。
     * <p>DIVIDE 跨量纲是正常的（金额 ÷ 人数 = ARPPU），减法不是：
     * 「金额 − 人数」算得出来，只是毫无意义，而且不会报错。
     * CHECK 是行级的，看不到被引用行的 value_format，只能在这里查。
     */
    private void checkSubtractUnits(Map<String, MetricCardConfig> byCode, Result result)
    {
        for (MetricCardConfig card : byCode.values())
        {
            if (!card.isEnabled() || !"SUBTRACT".equalsIgnoreCase(card.getCalcType()))
            {
                continue;
            }
            MetricCardConfig left = byCode.get(card.getLeftCode());
            MetricCardConfig right = byCode.get(card.getRightCode());
            if (left == null || right == null)
            {
                continue;   // 已由 checkOperands 报过
            }
            if (!equalsIgnoreCase(left.getValueFormat(), right.getValueFormat()))
            {
                result.getErrors().add("减法指标 " + card.getMetricCode() + " 的两个操作数量纲不同："
                    + left.getMetricCode() + "=" + left.getValueFormat()
                    + "，" + right.getMetricCode() + "=" + right.getValueFormat());
            }
        }
    }

    /**
     * 页面关系检查。这里是「一个指标被多个页面引用」最容易出事的地方。
     */
    private void checkPages(Map<String, MetricCardConfig> byCode,
            List<PageMetricConfig> pageMetrics, Result result)
    {
        Map<String, Set<String>> usedByPage = new LinkedHashMap<>();
        Map<String, Set<String>> defaultsByPage = new LinkedHashMap<>();
        Map<String, Set<String>> coreByPage = new LinkedHashMap<>();
        Set<String> referenced = new HashSet<>();

        for (PageMetricConfig relation : pageMetrics)
        {
            if (!relation.isEnabled())
            {
                continue;
            }
            String page = relation.getPageCode();
            if (DashboardPage.of(page) == null)
            {
                result.getErrors().add("页面关系引用了未知页面 " + page
                    + "，DashboardPage 枚举里没有对应实现");
                continue;
            }
            MetricCardConfig card = byCode.get(relation.getMetricCode());
            if (card == null)
            {
                result.getErrors().add("页面 " + page + " 引用了不存在的指标 " + relation.getMetricCode());
                continue;
            }
            // 外键只管 DELETE，管不住 status 改成 '1'——这是最可能真实发生的一种
            if (!card.isEnabled())
            {
                result.getErrors().add("页面 " + page + " 挂着已停用的指标 "
                    + relation.getMetricCode() + "（" + card.getMetricName() + "）");
                continue;
            }
            if (!card.isShowAsCard())
            {
                result.getErrors().add("页面 " + page + " 挂了隐藏原子量 " + relation.getMetricCode()
                    + "，它只供派生指标引用，不是一个可展示的列");
                continue;
            }
            referenced.add(relation.getMetricCode());
            usedByPage.computeIfAbsent(page, k -> new LinkedHashSet<>()).add(relation.getMetricCode());
            if (relation.getIsDefault())
            {
                defaultsByPage.computeIfAbsent(page, k -> new LinkedHashSet<>())
                    .add(relation.getMetricCode());
            }
            if (relation.isCore())
            {
                coreByPage.computeIfAbsent(page, k -> new LinkedHashSet<>())
                    .add(relation.getMetricCode());
            }
        }

        for (DashboardPage page : DashboardPage.values())
        {
            Set<String> used = usedByPage.get(page.name());
            if (used == null || used.isEmpty())
            {
                result.getErrors().add("页面 " + page.name() + " 没有任何可用指标，页面会是空的");
                continue;
            }
            Set<String> defaults = defaultsByPage.get(page.name());
            if (defaults == null || defaults.isEmpty())
            {
                result.getErrors().add("页面 " + page.name() + " 没有任何默认列，打开就是空表");
            }
        }

        // 软告警：启用的指标卡一个页面都没挂上——多半是加了卡忘了挂页面
        for (MetricCardConfig card : byCode.values())
        {
            if (card.isEnabled() && card.isShowAsCard() && !referenced.contains(card.getMetricCode()))
            {
                result.getWarnings().add("指标 " + card.getMetricCode()
                    + "（" + card.getMetricName() + "）没有出现在任何页面上");
            }
        }

        // 软告警：两个页面的核心集分叉。不强制一致——它们本就是各自的决策，
        // 但分叉了要有人知道，别让运营以为还对得上
        Set<String> wallCore = coreByPage.getOrDefault(DashboardPage.OVERVIEW.name(), Set.of());
        Set<String> sumDefault = defaultsByPage.getOrDefault(DashboardPage.SUMMARY.name(), Set.of());
        if (!wallCore.isEmpty() && !sumDefault.isEmpty() && !wallCore.equals(sumDefault))
        {
            Set<String> onlyWall = new LinkedHashSet<>(wallCore);
            onlyWall.removeAll(sumDefault);
            Set<String> onlySum = new LinkedHashSet<>(sumDefault);
            onlySum.removeAll(wallCore);
            result.getWarnings().add("指标墙的大号卡与汇总表的默认列已分叉："
                + "仅墙上=" + onlyWall + "，仅汇总=" + onlySum);
        }
    }

    private static boolean equalsIgnoreCase(String a, String b)
    {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
