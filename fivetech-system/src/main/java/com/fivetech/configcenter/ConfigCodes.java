package com.fivetech.configcenter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 代码里用到的配置编码：页面、含义、示例，以及保存时对配置值的结构校验。
 *
 * <p>配置中心可以存任意编码（配置值只要求是合法 JSON）；这里登记的编码另外按结构校验，并在新增时给出默认页面、含义和示例。
 * 新增一个由代码读取的配置时，在这里加一项。</p>
 *
 * @author fivetech
 */
public final class ConfigCodes
{
    /** 推广分析：推广商与推广渠道字典（FY-56 导入、FY-57 投放日报、筛选项） */
    public static final String PROMO_CHANNELS = "PROMO_CHANNELS";

    /** 推广分析：终端字典与各终端的获客漏斗步骤 */
    public static final String PROMO_TERMINALS = "PROMO_TERMINALS";

    /** 推广渠道状态 */
    static final Set<String> CHANNEL_STATUS = Set.of("ACTIVE", "DISABLED");

    /** 推广渠道入口类型：落地页 / 主页（决定注册转化率的分母） */
    static final Set<String> ENTRY_TYPES = Set.of("LANDING", "MAIN");

    /** 漏斗步骤可用的指标编码 */
    static final Set<String> FUNNEL_STEPS = Set.of("lpUV", "hUV", "dl", "act", "reg", "ftd");

    public static final class Def
    {
        private final String code;
        private final String pageName;
        private final String description;
        private final String example;
        final Function<JsonNode, String> validator;

        Def(String code, String pageName, String description, String example, Function<JsonNode, String> validator)
        {
            this.code = code;
            this.pageName = pageName;
            this.description = description;
            this.example = example;
            this.validator = validator;
        }

        public String getCode() { return code; }
        public String getPageName() { return pageName; }
        public String getDescription() { return description; }
        public String getExample() { return example; }
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static
    {
        add(new Def(PROMO_CHANNELS, "推廣分析",
            "推廣商與推廣渠道字典：id 為編碼（匯入模板的推廣商 ID / 推廣渠道 ID，篩選與下鑽傳的 code），name 為顯示名；"
                + "media 投放媒體（投放日報用），status ACTIVE / DISABLED（停用的照常列出、標「已停用」），"
                + "entryType LANDING / MAIN（入口類型，決定註冊轉化率的分母取 UV-落地頁還是 UV-主頁）；順序即模板、表格與篩選項的順序",
            "[\n"
                + "  { \"id\": 1001, \"name\": \"hada\", \"channels\": [\n"
                + "    { \"id\": 2001, \"name\": \"DSP-hada\", \"media\": \"DSP\", \"status\": \"ACTIVE\", \"entryType\": \"LANDING\" },\n"
                + "    { \"id\": 2002, \"name\": \"meta-hada\", \"media\": \"meta\", \"status\": \"ACTIVE\", \"entryType\": \"LANDING\" }\n"
                + "  ] },\n"
                + "  { \"id\": 1002, \"name\": \"jason\", \"channels\": [\n"
                + "    { \"id\": 2003, \"name\": \"TG-jason\", \"media\": \"TG\", \"status\": \"ACTIVE\", \"entryType\": \"MAIN\" }\n"
                + "  ] }\n"
                + "]",
            ConfigCodes::validatePromoChannels));
        add(new Def(PROMO_TERMINALS, "推廣分析",
            "終端字典與獲客漏斗：terminals 按順序為篩選項與漏斗卡片的順序；steps 為該終端的漏斗步驟（指標編碼 lpUV / hUV / dl / act / reg / ftd），"
                + "role = ENTRY 表示入口步，strict = false 表示非嚴格轉化（不畫轉化率）；landingMinPct：落地頁 UV 佔入口的比例低於它時視為不經過落地頁（去掉第一步）",
            "{\n"
                + "  \"landingMinPct\": 20,\n"
                + "  \"terminals\": [\n"
                + "    { \"code\": \"android\", \"name\": \"Android\", \"steps\": [\n"
                + "      { \"code\": \"lpUV\", \"role\": \"ENTRY\" }, { \"code\": \"dl\", \"strict\": false },\n"
                + "      { \"code\": \"act\", \"strict\": false }, { \"code\": \"reg\" }, { \"code\": \"ftd\" } ] },\n"
                + "    { \"code\": \"ios\", \"name\": \"iOS\", \"steps\": [\n"
                + "      { \"code\": \"lpUV\", \"role\": \"ENTRY\" }, { \"code\": \"hUV\", \"strict\": false }, { \"code\": \"reg\" }, { \"code\": \"ftd\" } ] },\n"
                + "    { \"code\": \"h5\", \"name\": \"H5\", \"steps\": [\n"
                + "      { \"code\": \"lpUV\", \"role\": \"ENTRY\" }, { \"code\": \"hUV\", \"strict\": false }, { \"code\": \"reg\" }, { \"code\": \"ftd\" } ] }\n"
                + "  ]\n"
                + "}",
            ConfigCodes::validatePromoTerminals));
    }

    private ConfigCodes()
    {
    }

    private static void add(Def d)
    {
        DEFS.put(d.code, d);
    }

    public static Def get(String code)
    {
        return code == null ? null : DEFS.get(code);
    }

    public static List<Def> all()
    {
        return List.copyOf(DEFS.values());
    }

    /**
     * 编码的比较写法：数字按数值（1001、1001.0、"1001" 相同），其余去首尾空格、不区分大小写。空返回空串。
     */
    public static String idKey(Object raw)
    {
        if (raw == null)
        {
            return "";
        }
        String s = (raw instanceof JsonNode n ? (n.isString() ? n.asString() : n.isNumber() ? n.numberValue().toString() : "") : String.valueOf(raw)).trim();
        if (s.isEmpty())
        {
            return "";
        }
        if (s.matches("^-?\\d+(?:\\.\\d+)?$"))
        {
            BigDecimal d = new BigDecimal(s).stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** id 只能是字符串或数字；返回去首尾空格的写法（数字按数值写，不带 .0），不合法返回 null */
    static String idText(JsonNode id)
    {
        if (id == null || !(id.isString() || id.isNumber()))
        {
            return null;
        }
        String t = id.isNumber() ? idKey(id) : id.asString().trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * PROMO_CHANNELS：数组，每项 { id, name, channels: [{ id, name, media, status, entryType }] }。
     * 推广商 ID、名称不能重复；推广渠道 ID、名称在整份字典里不能重复（同一个推广渠道只能属于一个推广商）；
     * ID 按 {@link #idKey(Object)} 比较，名称去首尾空格、不区分大小写；ID 与名称最多 64 个字。
     */
    public static String validatePromoChannels(JsonNode root)
    {
        if (root == null || !root.isArray())
        {
            return "應為數組：[{ \"id\": 推廣商 ID, \"name\": 推廣商名稱, \"channels\": [{ \"id\", \"name\", \"media\", \"status\", \"entryType\" }] }]";
        }
        Set<String> promoterIds = new HashSet<>();
        Set<String> promoters = new HashSet<>();
        Set<String> channelIds = new HashSet<>();
        Set<String> channels = new HashSet<>();
        int i = 0;
        for (JsonNode p : root)
        {
            i++;
            if (p == null || !p.isObject())
            {
                return "第 " + i + " 項應為對象 { id, name, channels }";
            }
            String pid = idText(p.get("id"));
            if (pid == null)
            {
                return "第 " + i + " 項缺少推廣商 ID（id，字符串或數字）";
            }
            JsonNode name = p.get("name");
            if (name == null || !name.isString() || name.asString().trim().isEmpty())
            {
                return "第 " + i + " 項缺少推廣商名稱 name";
            }
            String n = name.asString().trim();
            if (n.length() > 64 || pid.length() > 64)
            {
                return "推廣商 ID 與名稱不能超過 64 個字：" + n;
            }
            if (!promoterIds.add(idKey(pid)))
            {
                return "推廣商 ID 重複：" + pid;
            }
            if (!promoters.add(n.toLowerCase(Locale.ROOT)))
            {
                return "推廣商重複：" + n;
            }
            JsonNode chs = p.get("channels");
            if (chs == null || !chs.isArray())
            {
                return "推廣商「" + n + "」的 channels 應為數組";
            }
            for (JsonNode ch : chs)
            {
                if (ch == null || !ch.isObject())
                {
                    return "推廣商「" + n + "」的推廣渠道應為對象 { id, name, media, status, entryType }";
                }
                String cid = idText(ch.get("id"));
                JsonNode cn = ch.get("name");
                if (cid == null || cn == null || !cn.isString() || cn.asString().trim().isEmpty())
                {
                    return "推廣商「" + n + "」有推廣渠道缺少 id 或 name";
                }
                String c = cn.asString().trim();
                if (c.length() > 64 || cid.length() > 64)
                {
                    return "推廣渠道 ID 與名稱不能超過 64 個字：" + c;
                }
                if (!channelIds.add(idKey(cid)))
                {
                    return "推廣渠道 ID 重複：" + cid + "（同一個推廣渠道只能屬於一個推廣商）";
                }
                if (!channels.add(c.toLowerCase(Locale.ROOT)))
                {
                    return "推廣渠道重複：" + c + "（同一個推廣渠道只能屬於一個推廣商）";
                }
                String err = optionalEnum(ch, "status", CHANNEL_STATUS, c);
                if (err == null)
                {
                    err = optionalEnum(ch, "entryType", ENTRY_TYPES, c);
                }
                if (err != null)
                {
                    return err;
                }
                JsonNode media = ch.get("media");
                if (media != null && !media.isNull() && (!media.isString() || media.asString().trim().length() > 32))
                {
                    return "推廣渠道「" + c + "」的 media 應為不超過 32 個字的字符串";
                }
            }
        }
        return null;
    }

    private static String optionalEnum(JsonNode node, String field, Set<String> allowed, String owner)
    {
        JsonNode v = node.get(field);
        if (v == null || v.isNull())
        {
            return null;
        }
        if (!v.isString() || !allowed.contains(v.asString().trim().toUpperCase(Locale.ROOT)))
        {
            return "推廣渠道「" + owner + "」的 " + field + " 只能是 " + String.join(" / ", new java.util.TreeSet<>(allowed));
        }
        return null;
    }

    /**
     * PROMO_TERMINALS：{ landingMinPct, terminals: [{ code, name, steps: [{ code, role, strict }] }] }。
     * 终端编码只能是字母、数字、下划线，不能是 all / unidentified，不能重复；步骤编码见 {@link #FUNNEL_STEPS}，同一终端内不能重复。
     */
    public static String validatePromoTerminals(JsonNode root)
    {
        if (root == null || !root.isObject())
        {
            return "應為對象：{ \"landingMinPct\": 20, \"terminals\": [{ \"code\", \"name\", \"steps\": [...] }] }";
        }
        JsonNode pct = root.get("landingMinPct");
        if (pct != null && !pct.isNull() && (!pct.isNumber() || pct.asDouble() < 0 || pct.asDouble() > 100))
        {
            return "landingMinPct 應為 0–100 的數字";
        }
        JsonNode list = root.get("terminals");
        if (list == null || !list.isArray())
        {
            return "terminals 應為數組";
        }
        Set<String> codes = new HashSet<>();
        for (JsonNode t : list)
        {
            JsonNode code = t == null ? null : t.get("code");
            if (code == null || !code.isString() || !code.asString().trim().matches("^[A-Za-z0-9_]{1,32}$"))
            {
                return "終端 code 只能用字母、數字、下劃線，1–32 個字";
            }
            String c = code.asString().trim().toLowerCase(Locale.ROOT);
            if ("all".equals(c) || "unidentified".equals(c))
            {
                return "終端 code 不能是 all 或 unidentified（系統保留）";
            }
            if (!codes.add(c))
            {
                return "終端重複：" + code.asString().trim();
            }
            JsonNode name = t.get("name");
            if (name == null || !name.isString() || name.asString().trim().isEmpty() || name.asString().trim().length() > 32)
            {
                return "終端「" + c + "」缺少名稱 name（最多 32 個字）";
            }
            JsonNode steps = t.get("steps");
            if (steps == null || steps.isNull())
            {
                continue;
            }
            if (!steps.isArray())
            {
                return "終端「" + c + "」的 steps 應為數組";
            }
            Set<String> seen = new HashSet<>();
            for (JsonNode s : steps)
            {
                JsonNode sc = s == null ? null : s.get("code");
                if (sc == null || !sc.isString() || !FUNNEL_STEPS.contains(sc.asString().trim()))
                {
                    return "終端「" + c + "」的步驟 code 只能是 " + String.join(" / ", new java.util.TreeSet<>(FUNNEL_STEPS));
                }
                if (!seen.add(sc.asString().trim()))
                {
                    return "終端「" + c + "」的步驟重複：" + sc.asString().trim();
                }
                JsonNode role = s.get("role");
                if (role != null && !role.isNull() && !(role.isString() && "ENTRY".equalsIgnoreCase(role.asString().trim())))
                {
                    return "終端「" + c + "」的步驟 role 只能是 ENTRY 或不填";
                }
                JsonNode strict = s.get("strict");
                if (strict != null && !strict.isNull() && !strict.isBoolean())
                {
                    return "終端「" + c + "」的步驟 strict 應為 true / false";
                }
            }
        }
        return null;
    }
}
