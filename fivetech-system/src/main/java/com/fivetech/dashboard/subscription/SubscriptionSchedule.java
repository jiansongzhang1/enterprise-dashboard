package com.fivetech.dashboard.subscription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 订阅的发送频率与排程（V0.4 原型 · 订阅管理，2026-11-12 第三、四版）。纯函数，不查库，便于单测。
 *
 * <ul>
 *   <li>{@link #DAY} 每日：每天 hour:00；</li>
 *   <li>{@link #WEEK} 每週：每週 dow（1=週一 … 7=週日）的 hour:00；</li>
 *   <li>{@link #HOURS} 每 N 小時：从 hour（「從」）起每 every 小时一次，最后一次不晚于 to（「到」，含）；從＝到 时每天一次。
 *       every 为 1–12 的整数（数据按小时落库，1 小时是下限；超过 12 小时与「每日」重复）。</li>
 * </ul>
 * 发送时刻都是站点时区的整点。定时任务只发「最近一个已到期、未过补发期、且晚于 last_slot」的时刻，不补发更早的。
 *
 * @author fivetech
 */
public final class SubscriptionSchedule
{
    public static final String DAY = "DAY";

    public static final String WEEK = "WEEK";

    public static final String HOURS = "HOURS";

    static final String[] DOW = { "", "週一", "週二", "週三", "週四", "週五", "週六", "週日" };

    public static final int EVERY_MIN = 1;

    public static final int EVERY_MAX = 12;

    private SubscriptionSchedule()
    {
    }

    /** 频率类型：为空按每日（兼容只有 send_hour 的旧数据）；不认识返回 null */
    public static String type(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return DAY;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        return DAY.equals(t) || WEEK.equals(t) || HOURS.equals(t) ? t : null;
    }

    /** 某一天的发送整点（升序）；配置不合法时为空 */
    public static List<Integer> hoursOn(DashboardSubscription s, LocalDate d)
    {
        List<Integer> out = new ArrayList<>();
        String t = type(s.getFreqType());
        Integer h = s.getSendHour();
        if (t == null || h == null || h < 0 || h > 23)
        {
            return out;
        }
        if (DAY.equals(t))
        {
            out.add(h);
        }
        else if (WEEK.equals(t))
        {
            if (s.getFreqDow() != null && s.getFreqDow() == d.getDayOfWeek().getValue())
            {
                out.add(h);
            }
        }
        else
        {
            Integer every = s.getFreqEvery();
            Integer to = s.getFreqTo() == null ? h : s.getFreqTo();
            if (every == null || every < EVERY_MIN || every > EVERY_MAX || to < h || to > 23)
            {
                return out;
            }
            for (int x = h; x <= to; x += every)
            {
                out.add(x);
            }
        }
        return out;
    }

    /**
     * 到期的发送时刻：今天、昨天（跨零点补发）的发送时刻里，取最近一个已过等待分钟的；它要未过补发期、且晚于 lastSlot。没有返回 null。
     */
    public static LocalDateTime due(DashboardSubscription s, LocalDateTime now, int delayMinutes, int graceHours, String lastSlot)
    {
        LocalDate today = now.toLocalDate();
        for (LocalDate d : new LocalDate[] { today, today.minusDays(1) })
        {
            List<Integer> hs = hoursOn(s, d);
            for (int i = hs.size() - 1; i >= 0; i--)
            {
                LocalDateTime c = d.atTime(hs.get(i), 0);
                if (now.isBefore(c.plusMinutes(Math.max(0, delayMinutes))))
                {
                    continue;
                }
                if (!now.isBefore(c.plusHours(Math.max(1, graceHours))))
                {
                    return null;
                }
                return lastSlot != null && c.format(SubscriptionSender.SLOT).compareTo(lastSlot) <= 0 ? null : c;
            }
        }
        return null;
    }

    /**
     * 下一次发送时刻：最早一个晚于 lastSlot、且还在补发期内（未发出）或还没到的时刻；最多往后看 8 天。
     */
    public static LocalDateTime next(DashboardSubscription s, LocalDateTime now, int graceHours, String lastSlot)
    {
        LocalDate today = now.toLocalDate();
        for (int i = -1; i <= 8; i++)
        {
            LocalDate d = today.plusDays(i);
            for (int h : hoursOn(s, d))
            {
                LocalDateTime c = d.atTime(h, 0);
                if (lastSlot != null && c.format(SubscriptionSender.SLOT).compareTo(lastSlot) <= 0)
                {
                    continue;
                }
                if (now.isBefore(c.plusHours(Math.max(1, graceHours))))
                {
                    return c;
                }
            }
        }
        return null;
    }

    /** 频率文字：每日 09:00 / 每週一 10:00 / 每 4 小時（10:00–22:00） */
    public static String text(DashboardSubscription s)
    {
        String t = type(s.getFreqType());
        Integer h = s.getSendHour();
        if (t == null || h == null)
        {
            return null;
        }
        if (DAY.equals(t))
        {
            return String.format("每日 %02d:00", h);
        }
        if (WEEK.equals(t))
        {
            Integer dow = s.getFreqDow();
            return "每" + (dow == null || dow < 1 || dow > 7 ? "週?" : DOW[dow]) + String.format(" %02d:00", h);
        }
        return String.format("每 %d 小時（%02d:00–%02d:00）", s.getFreqEvery() == null ? 0 : s.getFreqEvery(), h,
            s.getFreqTo() == null ? h : s.getFreqTo());
    }

    /**
     * 校验频率字段，错误写进 errors（字段名同请求体：freqType、sendHour、freqDow、freqEvery、freqTo）。
     *
     * @param range 统计范围；「今日」不能在 00:00 发送（还没有资料）
     */
    public static void validate(DashboardSubscription s, SubscriptionRange range, Map<String, String> errors)
    {
        String t = type(s.getFreqType());
        if (t == null)
        {
            errors.put("freqType", "發送頻率只能是每日、每週或每 N 小時");
            return;
        }
        s.setFreqType(t);
        Integer h = s.getSendHour();
        String hourField = "sendHour";
        if (h == null || h < 0 || h > 23)
        {
            errors.put(hourField, HOURS.equals(t) ? "「從」請選 00:00–23:00 的整點" : "發送時間請選 00:00–23:00 的整點");
        }
        else if (range == SubscriptionRange.TODAY && h == 0)
        {
            errors.put(hourField, HOURS.equals(t) ? "統計「今日」時不能從 00:00 開始（還沒有資料）"
                : "統計「今日」時不能在 00:00 發送（還沒有資料）");
        }
        if (WEEK.equals(t))
        {
            if (s.getFreqDow() == null || s.getFreqDow() < 1 || s.getFreqDow() > 7)
            {
                errors.put("freqDow", "請選擇週幾發送");
            }
        }
        else
        {
            s.setFreqDow(null);
        }
        if (HOURS.equals(t))
        {
            if (s.getFreqEvery() == null || s.getFreqEvery() < EVERY_MIN || s.getFreqEvery() > EVERY_MAX)
            {
                errors.put("freqEvery", "請輸入 " + EVERY_MIN + "–" + EVERY_MAX + " 的整數");
            }
            Integer to = s.getFreqTo();
            if (to == null || to < 0 || to > 23)
            {
                errors.put("freqTo", "「到」請選 00:00–23:00 的整點");
            }
            else if (h != null && to < h)
            {
                errors.put("freqTo", "「到」不能早於「從」");
            }
        }
        else
        {
            s.setFreqEvery(null);
            s.setFreqTo(null);
        }
    }

    /**
     * 频率与统计范围的组合提示（不禁止）。
     */
    public static List<String> warnings(DashboardSubscription s, SubscriptionRange range, LocalDate sample)
    {
        List<String> out = new ArrayList<>();
        String t = type(s.getFreqType());
        Integer h = s.getSendHour();
        if (t == null || h == null || range == null)
        {
            return out;
        }
        boolean fixed = range == SubscriptionRange.CUSTOM;
        if (HOURS.equals(t))
        {
            List<Integer> hs = hoursOn(s, sample);
            if (hs.isEmpty())
            {
                return out;
            }
            if (hs.size() == 1)
            {
                out.add("每天只會發送 1 次，建議改為每日。");
            }
            if (range != SubscriptionRange.TODAY && !fixed)
            {
                out.add("每 " + s.getFreqEvery() + " 小時發送一次，但統計的是「" + range.getLabel() + "」，相鄰兩次的資料大部分相同。");
            }
        }
        if (WEEK.equals(t) && (range == SubscriptionRange.TODAY || range == SubscriptionRange.YESTERDAY))
        {
            out.add("每週只發一次，但只統計" + (range == SubscriptionRange.YESTERDAY ? "發送前一天" : "發送當天") + "。");
        }
        if (DAY.equals(t) && range == SubscriptionRange.TODAY && h < 12)
        {
            out.add(h == 0 ? "每天 00:00 發送時，當天還沒有資料。" : String.format("每天 %02d:00 發送時，當天只有 %d 小時的資料。", h, h));
        }
        if (fixed && !WEEK.equals(t))
        {
            out.add("固定日期的資料不會再變，建議改為每週發送。");
        }
        return out;
    }
}
