package com.fivetech.monitor.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fivetech.monitor.domain.MonitorMetricOption;
import com.fivetech.monitor.domain.dto.MonitorRuleBody;
import com.fivetech.monitor.domain.dto.Threshold;

/**
 * 规则参数校验（新增、修改共用），对应设计文档「校验规则」第 2–10 条。
 *
 * <p>不碰数据库：指标是否存在 / 已被占用（第 1 条）、通知对象（第 11 条）、负责人（第 12 条）
 * 由 Service 查库后把错误追加到同一个 errors 里，全部校验完再一次性返回 400。</p>
 *
 * <p>错误文案用繁体，前端直接显示。字段 key：metricCode、warn、crit、warn.low、warn.high、crit.low、
 * crit.high、periodHours、durationCount、escalateHours、muteHours、minSampleValue、notifyChatId、ownerUserId。</p>
 *
 * @author fivetech
 */
@Component
public class MonitorRuleValidator
{
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** NUMERIC(18,4) 的整数部分上限 */
    private static final BigDecimal MAX_ABS = new BigDecimal("99999999999999");

    /**
     * 校验并返回按判定类型清理过的阈值（用不到的一侧置空）。
     *
     * @param body      请求体
     * @param metric    指标监控属性（单位、是否允许负数）
     * @param judgeType 判定类型：新增取指标配置，修改取规则里的快照
     * @param errors    字段错误，按出现顺序追加
     */
    public Result validate(MonitorRuleBody body, MonitorMetricOption metric, String judgeType, Map<String, String> errors)
    {
        Threshold warn = side(body.getWarn(), judgeType);
        Threshold crit = side(body.getCrit(), judgeType);

        boolean critOk = checkCrit(crit, judgeType, errors);
        boolean warnOk = checkWarn(warn, judgeType, errors);
        critOk = checkValues("crit", crit, metric, judgeType, errors) && critOk;
        warnOk = checkValues("warn", warn, metric, judgeType, errors) && warnOk;
        critOk = checkBandOrder("crit", crit, judgeType, errors) && critOk;
        warnOk = checkBandOrder("warn", warn, judgeType, errors) && warnOk;
        if (critOk && warnOk && warn != null && !errors.containsKey("crit"))
        {
            checkStricter(warn, crit, judgeType, errors);
        }

        checkPeriodRelated(body, warn != null, errors);

        BigDecimal minSample = body.getMinSampleValue();
        if (minSample == null)
        {
            minSample = metric.getMinSampleDefault() == null ? BigDecimal.ZERO : metric.getMinSampleDefault();
        }
        else if (minSample.signum() < 0)
        {
            errors.putIfAbsent("minSampleValue", "最小樣本量不能小於 0");
        }
        else if (minSample.abs().compareTo(MAX_ABS) > 0)
        {
            errors.putIfAbsent("minSampleValue", "數值超出範圍");
        }
        return new Result(warn, crit == null ? new Threshold() : crit, minSample);
    }

    /** 按判定类型只保留用得到的一侧；两侧都没填返回 null（表示没有这一级） */
    private Threshold side(Threshold t, String judgeType)
    {
        if (t == null)
        {
            return null;
        }
        Threshold r;
        switch (judgeType)
        {
            case "hi":
                r = new Threshold(null, t.getHigh());
                break;
            case "band":
                r = new Threshold(t.getLow(), t.getHigh());
                break;
            default: // lo / drop
                r = new Threshold(t.getLow(), null);
                break;
        }
        return r.isBlank() ? null : r;
    }

    /** 第 2、4 条：严重阈值必填；band 上下限都要 */
    private boolean checkCrit(Threshold crit, String judgeType, Map<String, String> errors)
    {
        if (crit == null || ("band".equals(judgeType) && (crit.getLow() == null || crit.getHigh() == null)))
        {
            errors.putIfAbsent("crit", crit == null ? "嚴重閾值不能為空" : "請填寫嚴重閾值的上下限");
            return false;
        }
        return true;
    }

    /** 注意阈值选填；band 填了就要上下限都填 */
    private boolean checkWarn(Threshold warn, String judgeType, Map<String, String> errors)
    {
        if (warn != null && "band".equals(judgeType) && (warn.getLow() == null || warn.getHigh() == null))
        {
            errors.putIfAbsent("warn", "請填寫注意閾值的上下限");
            return false;
        }
        return true;
    }

    /** 第 3 条：非负（仅存提差允许负数）；跌幅大于 0 且不超过 100；百分比类指标在 0–100 之间 */
    private boolean checkValues(String name, Threshold t, MonitorMetricOption metric, String judgeType,
            Map<String, String> errors)
    {
        if (t == null)
        {
            return true;
        }
        boolean ok = checkValue(name + ".low", t.getLow(), metric, judgeType, errors);
        return checkValue(name + ".high", t.getHigh(), metric, judgeType, errors) && ok;
    }

    private boolean checkValue(String field, BigDecimal v, MonitorMetricOption metric, String judgeType,
            Map<String, String> errors)
    {
        if (v == null)
        {
            return true;
        }
        if ("drop".equals(judgeType))
        {
            // 跌幅 0% 没有意义（任何波动都算越线），与原型一致要求大于 0
            if (v.signum() <= 0 || v.compareTo(HUNDRED) > 0)
            {
                errors.putIfAbsent(field, "跌幅需大於 0 且不超過 100");
                return false;
            }
            return true;
        }
        if (metric.isPercent())
        {
            if (v.signum() < 0 || v.compareTo(HUNDRED) > 0)
            {
                errors.putIfAbsent(field, "請輸入 0–100 的數字");
                return false;
            }
            return true;
        }
        if (v.signum() < 0 && !metric.isAllowNegative())
        {
            errors.putIfAbsent(field, "不能為負數");
            return false;
        }
        if (v.abs().compareTo(MAX_ABS) > 0)
        {
            errors.putIfAbsent(field, "數值超出範圍");
            return false;
        }
        return true;
    }

    /** 第 4 条：band 下限 &lt; 上限 */
    private boolean checkBandOrder(String name, Threshold t, String judgeType, Map<String, String> errors)
    {
        if (t == null || !"band".equals(judgeType) || t.getLow() == null || t.getHigh() == null)
        {
            return true;
        }
        if (t.getLow().compareTo(t.getHigh()) >= 0)
        {
            errors.putIfAbsent(name, "下限需小於上限");
            return false;
        }
        return true;
    }

    /**
     * 第 5 条：严重比注意更严。lo 严重 &lt; 注意；hi、drop 严重 &gt; 注意；
     * band 严重区间完全包住注意区间且不能完全相同。
     */
    private void checkStricter(Threshold warn, Threshold crit, String judgeType, Map<String, String> errors)
    {
        boolean stricter;
        switch (judgeType)
        {
            case "lo":
                stricter = crit.getLow().compareTo(warn.getLow()) < 0;
                break;
            case "hi":
                stricter = crit.getHigh().compareTo(warn.getHigh()) > 0;
                break;
            case "drop":
                stricter = crit.getLow().compareTo(warn.getLow()) > 0;
                break;
            case "band":
                int lowCmp = crit.getLow().compareTo(warn.getLow());
                int highCmp = crit.getHigh().compareTo(warn.getHigh());
                stricter = lowCmp <= 0 && highCmp >= 0 && !(lowCmp == 0 && highCmp == 0);
                break;
            default:
                stricter = true;
                break;
        }
        if (!stricter)
        {
            errors.putIfAbsent("crit", "嚴重閾值需比注意閾值更嚴");
        }
    }

    /** 第 6–9 条：检测周期、持续次数、注意升级、静默期 */
    private void checkPeriodRelated(MonitorRuleBody body, boolean hasWarn, Map<String, String> errors)
    {
        Integer period = body.getPeriodHours();
        if (!MonitorRuleOptions.isValidPeriod(period))
        {
            errors.putIfAbsent("periodHours", "檢測週期只能是 1、4、6、12、24 小時");
            // 后面三项的可选值取决于周期，周期不对就不再逐项报错，免得一屏都是红字
            return;
        }

        Integer duration = body.getDurationCount();
        int maxDuration = MonitorRuleOptions.maxDuration(period);
        if (duration == null)
        {
            errors.putIfAbsent("durationCount", "請填寫持續次數");
        }
        else if (duration < 1)
        {
            errors.putIfAbsent("durationCount", "持續次數至少 1 次");
        }
        else if (duration > maxDuration)
        {
            errors.putIfAbsent("durationCount", "持續次數最多 " + maxDuration + " 次");
        }

        Integer escalate = body.getEscalateHours();
        if (escalate != null)
        {
            List<Integer> options = MonitorRuleOptions.escalateOptions(period);
            if (!hasWarn)
            {
                errors.putIfAbsent("escalateHours", "未設注意閾值時不能設定升級");
            }
            else if (!options.contains(escalate))
            {
                errors.putIfAbsent("escalateHours", "注意升級只能選 " + MonitorRuleOptions.join(options) + " 小時");
            }
        }

        Integer mute = body.getMuteHours();
        List<Integer> muteOptions = MonitorRuleOptions.muteOptions(period);
        if (mute == null)
        {
            errors.putIfAbsent("muteHours", "請選擇靜默期");
        }
        else if (!muteOptions.contains(mute))
        {
            errors.putIfAbsent("muteHours", "靜默期只能選 " + MonitorRuleOptions.join(muteOptions) + " 小時");
        }
    }

    /** 校验后的阈值与样本量 */
    public static final class Result
    {
        private final Threshold warn;

        private final Threshold crit;

        private final BigDecimal minSampleValue;

        Result(Threshold warn, Threshold crit, BigDecimal minSampleValue)
        {
            this.warn = warn;
            this.crit = crit;
            this.minSampleValue = minSampleValue;
        }

        /** 没填注意线时为 null */
        public Threshold getWarn()
        {
            return warn;
        }

        public Threshold getCrit()
        {
            return crit;
        }

        public BigDecimal getMinSampleValue()
        {
            return minSampleValue;
        }
    }
}
