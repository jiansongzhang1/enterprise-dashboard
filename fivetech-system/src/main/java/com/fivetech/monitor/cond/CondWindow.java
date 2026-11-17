package com.fivetech.monitor.cond;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 条件规则的统计窗口。检测每小时一次（整点后），窗口按自然时段对齐：
 * <ul>
 *   <li>{@code Nh}：在 (小时 + 1) 能被 N 整除的整点检测，统计刚结束的 N 小时，如 4h 在 04:00、08:00 … 检测；24h 在 00:00 统计前一天全天；</li>
 *   <li>{@code TODAY}：每小时检测，统计站点时区今日 00:00 到检测时。</li>
 * </ul>
 * 连续次数按检测点计。时间都是站点时区。
 *
 * @author fivetech
 */
public enum CondWindow
{
    H1("1h", 1, "每 1 小時"),
    H4("4h", 4, "每 4 小時"),
    H6("6h", 6, "每 6 小時"),
    H12("12h", 12, "每 12 小時"),
    H24("24h", 24, "每 24 小時"),
    TODAY("TODAY", 0, "今日累計");

    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private final String code;

    private final int hours;

    private final String text;

    CondWindow(String code, int hours, String text)
    {
        this.code = code;
        this.hours = hours;
        this.text = text;
    }

    public static CondWindow of(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (CondWindow w : values())
        {
            if (w.code.equalsIgnoreCase(code.trim()))
            {
                return w;
            }
        }
        return null;
    }

    public static List<String> codes()
    {
        return List.of("1h", "4h", "6h", "12h", "24h", "TODAY");
    }

    /** end（整点，检测时刻 = 刚结束的小时的下一个整点）是不是这个窗口的检测点 */
    public boolean isCheckpoint(LocalDateTime end)
    {
        if (this == TODAY || hours == 1)
        {
            return true;
        }
        return end.getHour() % hours == 0;
    }

    /** 检测点 end 的统计起点；TODAY 在 00:00 检测时统计前一天全天 */
    public LocalDateTime start(LocalDateTime end)
    {
        if (this == TODAY)
        {
            LocalDateTime day = end.toLocalDate().atStartOfDay();
            return day.equals(end) ? day.minusDays(1) : day;
        }
        return end.minusHours(hours);
    }

    /** 检测点 end 之前的上一个检测点 */
    public LocalDateTime previous(LocalDateTime end)
    {
        return end.minusHours(this == TODAY ? 1 : hours);
    }

    /** 时段说明：每小时「11-13 09:00」、多小时「11-13 08:00 – 12:00」、全天「11-13 全天」、今日累计「11-13 00:00 – 10:00」 */
    public String label(LocalDateTime end)
    {
        LocalDateTime start = start(end);
        if (this == H1)
        {
            return start.format(MD_HM);
        }
        if (this == H24 || (this == TODAY && end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)))
        {
            return start.format(MD) + " 全天";
        }
        return start.format(MD_HM) + " – " + (end.getHour() == 0 ? "24:00" : end.format(HM));
    }

    /** 走势图副标题 */
    public String subtitle(boolean snapshot)
    {
        if (snapshot)
        {
            return "每個整點的值";
        }
        return this == TODAY ? "今日累計值" : "每個時段（" + hours + " 小時）的值";
    }

    public String getCode() { return code; }
    public int getHours() { return hours; }
    public String getText() { return text; }
}
