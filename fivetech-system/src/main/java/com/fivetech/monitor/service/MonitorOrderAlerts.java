package com.fivetech.monitor.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fivetech.monitor.exception.MonitorErrorCode;
import com.fivetech.monitor.exception.MonitorException;

/**
 * 按规则发告警（POST /monitor/rules/{ruleId}/alert）里订单信息的解析与校验（纯函数，不连库）。
 *
 * <p>订单规则命中时，外部判定服务在 body 里带订单号、本笔金额、当日累计笔数：</p>
 * <pre>
 * 单笔：{ "message": "…", "orderNo": "W1", "amount": 52000, "cumCount": 3, "cumAmount": 180000 }
 * 多笔（同一分钟合并成一条消息）：{ "message": "…", "orders": [ { "orderNo": "W1", "amount": 52000, "cumCount": 3 }, … ] }
 * 只记录不发送（判定服务按每日上限不发）：{ "send": false, "orderNo": "W1", "amount": 52000 }
 * </pre>
 * <ul>
 *   <li>orderNo、amount 必填；当日累计规则（amountMode = DAILY_TOTAL）cumCount 必填，单笔规则不存 cumCount / cumAmount；</li>
 *   <li>orderNo 与 orders 只能二选一，orders 最多 {@value #MAX_ORDERS} 笔，订单号不能重复；</li>
 *   <li>指标规则、条件规则不能带订单信息，也不能 send = false。</li>
 * </ul>
 *
 * @author fivetech
 */
public final class MonitorOrderAlerts
{
    /** 一次最多带的订单数 */
    public static final int MAX_ORDERS = 100;

    public static final int ORDER_NO_MAX = 64;

    /** 金额上限（1 万亿），防止误传 */
    static final BigDecimal AMOUNT_MAX = new BigDecimal("1000000000000");

    static final int CUM_COUNT_MAX = 1_000_000;

    /** notify_status */
    public static final String PENDING = "PENDING";

    public static final String SENT = "SENT";

    /** 有的群送达、有的失败（规则选了多个群） */
    public static final String PARTIAL = "PARTIAL";

    public static final String FAILED = "FAILED";

    public static final String LOG_ONLY = "LOG_ONLY";

    /** notify_reason */
    public static final String CAPPED = "CAPPED";

    public static final String NOTIFY_OFF = "NOTIFY_OFF";

    /** 只出现在返回里（不入库）：这批订单之前都已上报过、没有要发的，这次不发 */
    public static final String SKIPPED = "SKIPPED";

    public static final String DUPLICATE = "DUPLICATE";

    private static final List<String> SINGLE_KEYS = List.of("orderNo", "amount", "cumCount", "cumAmount");

    private MonitorOrderAlerts()
    {
    }

    /** 一笔命中的订单 */
    public record Hit(String orderNo, BigDecimal amount, Integer cumCount, BigDecimal cumAmount)
    {
    }

    /** 解析结果：send = false 表示只记录不发送；orders 为空表示没带订单信息 */
    public record Input(boolean send, List<Hit> orders)
    {
        public boolean hasOrders()
        {
            return !orders.isEmpty();
        }
    }

    /** 解析 body 里的 send 与订单信息（message 由发送服务自己校验） */
    public static Input parse(Map<String, Object> body)
    {
        if (body == null)
        {
            return new Input(true, List.of());
        }
        boolean send = bool(body.get("send"));
        boolean single = SINGLE_KEYS.stream().anyMatch(k -> body.get(k) != null);
        Object list = body.get("orders");
        if (single && list != null)
        {
            throw bad("orderNo 與 orders 只能二選一：單筆用 orderNo、amount、cumCount，多筆用 orders");
        }
        List<Hit> hits = new ArrayList<>();
        if (single)
        {
            hits.add(hit(body, ""));
        }
        else if (list != null)
        {
            if (!(list instanceof List<?> items))
            {
                throw bad("orders 須為陣列");
            }
            if (items.isEmpty())
            {
                throw bad("orders 不能為空");
            }
            if (items.size() > MAX_ORDERS)
            {
                throw bad("orders 一次最多 " + MAX_ORDERS + " 筆（目前 " + items.size() + " 筆）");
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < items.size(); i++)
            {
                if (!(items.get(i) instanceof Map<?, ?> m))
                {
                    throw bad("orders 第 " + (i + 1) + " 筆須為物件");
                }
                @SuppressWarnings("unchecked")
                Hit h = hit((Map<String, Object>) m, "orders 第 " + (i + 1) + " 筆：");
                if (!seen.add(h.orderNo()))
                {
                    throw bad("orders 裡的訂單號重複：" + h.orderNo());
                }
                hits.add(h);
            }
        }
        return new Input(send, hits);
    }

    /**
     * 按规则的金额口径检查并整理：当日累计规则 cumCount 必填、cumAmount 不能小于本笔；单笔规则去掉累计字段（不存）。
     */
    public static List<Hit> forRule(List<Hit> hits, String amountMode)
    {
        boolean daily = MonitorOrderRules.DAILY_TOTAL.equals(amountMode);
        List<Hit> out = new ArrayList<>(hits.size());
        for (Hit h : hits)
        {
            if (!daily)
            {
                out.add(new Hit(h.orderNo(), h.amount(), null, null));
                continue;
            }
            if (h.cumCount() == null)
            {
                throw bad("訂單 " + h.orderNo() + "：帳號當日累計規則須帶 cumCount（當日累計筆數，含本筆）");
            }
            if (h.cumAmount() != null && h.cumAmount().compareTo(h.amount()) < 0)
            {
                throw bad("訂單 " + h.orderNo() + "：cumAmount（當日累計金額）不能小於本筆金額 amount");
            }
            out.add(h);
        }
        return out;
    }

    static Hit hit(Map<String, Object> m, String at)
    {
        Object no = m.get("orderNo");
        String orderNo = no == null ? "" : String.valueOf(no).strip();
        if (orderNo.isEmpty())
        {
            throw bad(at + "orderNo（訂單號）必填");
        }
        if (orderNo.length() > ORDER_NO_MAX || orderNo.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
        {
            throw bad(at + "orderNo 最多 " + ORDER_NO_MAX + " 個字，不能有空白");
        }
        BigDecimal amount = money(m.get("amount"), at + "amount（訂單金額）");
        if (amount == null)
        {
            throw bad(at + "amount（訂單金額）必填");
        }
        Integer cumCount = count(m.get("cumCount"), at + "cumCount（當日累計筆數）");
        BigDecimal cumAmount = money(m.get("cumAmount"), at + "cumAmount（當日累計金額）");
        return new Hit(orderNo, amount, cumCount, cumAmount);
    }

    /** 金额：大于 0、最多 2 位小数、不超过上限；null 返回 null */
    static BigDecimal money(Object v, String name)
    {
        BigDecimal d = number(v, name);
        if (d == null)
        {
            return null;
        }
        if (d.signum() <= 0 || d.compareTo(AMOUNT_MAX) > 0)
        {
            throw bad(name + " 須大於 0、不超過 1,000,000,000,000");
        }
        d = d.stripTrailingZeros();
        if (d.scale() > 2)
        {
            throw bad(name + " 最多 2 位小數");
        }
        return d.scale() < 0 ? d.setScale(0) : d;
    }

    /** 笔数：1 ～ 1,000,000 的整数；null 返回 null */
    static Integer count(Object v, String name)
    {
        BigDecimal d = number(v, name);
        if (d == null)
        {
            return null;
        }
        if (d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.ONE) < 0 || d.compareTo(BigDecimal.valueOf(CUM_COUNT_MAX)) > 0)
        {
            throw bad(name + " 須為 1 ～ " + CUM_COUNT_MAX + " 的整數");
        }
        return d.intValue();
    }

    private static BigDecimal number(Object v, String name)
    {
        if (v == null || (v instanceof String s && s.isBlank()))
        {
            return null;
        }
        if (!(v instanceof Number) && !(v instanceof String))
        {
            throw bad(name + " 須為數字");
        }
        try
        {
            return new BigDecimal(String.valueOf(v).strip());
        }
        catch (NumberFormatException e)
        {
            throw bad(name + " 須為數字");
        }
    }

    /** send 缺省 true；接受 true / false 与字符串 "true" / "false" */
    static boolean bool(Object v)
    {
        if (v == null)
        {
            return true;
        }
        if (v instanceof Boolean b)
        {
            return b;
        }
        String s = String.valueOf(v).strip();
        if ("true".equalsIgnoreCase(s))
        {
            return true;
        }
        if ("false".equalsIgnoreCase(s))
        {
            return false;
        }
        throw bad("send 須為 true 或 false");
    }

    private static MonitorException bad(String message)
    {
        return new MonitorException(MonitorErrorCode.BAD_REQUEST, message);
    }
}
