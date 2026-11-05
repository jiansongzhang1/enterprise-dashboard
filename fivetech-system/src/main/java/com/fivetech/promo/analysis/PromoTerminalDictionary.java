package com.fivetech.promo.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.fivetech.configcenter.ConfigCenterService;
import com.fivetech.configcenter.ConfigCodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 推广分析 · 终端字典与获客漏斗步骤，来自配置中心 {@link ConfigCodes#PROMO_TERMINALS}（站点自己的优先，没有再用全站通用）。
 *
 * <p>「未識別」（unidentified）是系统保留项，不在字典里：筛选不能选它，归因按终端拆、用户明细筛选时加在最后。
 * 没配置或配置读不了时终端字典为空（终端筛选只剩 all）。</p>
 *
 * @author fivetech
 */
@Component
public class PromoTerminalDictionary
{
    private static final Logger log = LoggerFactory.getLogger(PromoTerminalDictionary.class);

    /** 未识别终端的编码与显示名 */
    public static final String UNIDENTIFIED = "unidentified";

    public static final String UNIDENTIFIED_NAME = "未識別";

    /** 落地页 UV 占入口的比例低于它（%）时视为不经过落地页；未配置时的默认值 */
    public static final BigDecimal DEFAULT_LANDING_MIN_PCT = new BigDecimal("20");

    private final ConfigCenterService configCenter;

    private final ObjectMapper json;

    public PromoTerminalDictionary(ConfigCenterService configCenter, ObjectMapper json)
    {
        this.configCenter = configCenter;
        this.json = json;
    }

    /** 某站点的终端配置；没配置返回空字典 */
    public Config of(String siteCode)
    {
        String value;
        try
        {
            value = configCenter.value(ConfigCodes.PROMO_TERMINALS, siteCode);
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 读取配置中心 {} 失败：{}", ConfigCodes.PROMO_TERMINALS, e.getMessage());
            return Config.EMPTY;
        }
        return value == null ? Config.EMPTY : parse(value);
    }

    Config parse(String value)
    {
        try
        {
            JsonNode root = json.readTree(value);
            String err = ConfigCodes.validatePromoTerminals(root);
            if (err != null)
            {
                log.warn("[promo] 配置中心 {} 的配置值不符合结构（{}），按空字典处理", ConfigCodes.PROMO_TERMINALS, err);
                return Config.EMPTY;
            }
            List<Terminal> terminals = new ArrayList<>();
            for (JsonNode t : root.get("terminals"))
            {
                List<Step> steps = new ArrayList<>();
                JsonNode ss = t.get("steps");
                if (ss != null && ss.isArray())
                {
                    for (JsonNode s : ss)
                    {
                        JsonNode role = s.get("role");
                        JsonNode strict = s.get("strict");
                        steps.add(new Step(s.get("code").asString().trim(),
                            role == null || role.isNull() ? null : role.asString().trim().toUpperCase(Locale.ROOT),
                            strict == null || strict.isNull() || strict.asBoolean()));
                    }
                }
                terminals.add(new Terminal(t.get("code").asString().trim().toLowerCase(Locale.ROOT), t.get("name").asString().trim(), steps));
            }
            JsonNode pct = root.get("landingMinPct");
            BigDecimal min = pct == null || !pct.isNumber() ? DEFAULT_LANDING_MIN_PCT : new BigDecimal(pct.numberValue().toString());
            return new Config(terminals, min);
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 配置中心 {} 的配置值无法解析：{}", ConfigCodes.PROMO_TERMINALS, e.getMessage());
            return Config.EMPTY;
        }
    }

    /** 终端配置 */
    public static final class Config
    {
        static final Config EMPTY = new Config(List.of(), DEFAULT_LANDING_MIN_PCT);

        private final List<Terminal> terminals;

        private final BigDecimal landingMinPct;

        public Config(List<Terminal> terminals, BigDecimal landingMinPct)
        {
            this.terminals = List.copyOf(terminals);
            this.landingMinPct = landingMinPct;
        }

        public List<Terminal> getTerminals() { return terminals; }

        public BigDecimal getLandingMinPct() { return landingMinPct; }

        /** 按编码找终端（不区分大小写）；找不到返回 null */
        public Terminal find(String code)
        {
            if (code == null)
            {
                return null;
            }
            String k = code.trim().toLowerCase(Locale.ROOT);
            return terminals.stream().filter(t -> t.getCode().equals(k)).findFirst().orElse(null);
        }
    }

    /** 终端 */
    public static final class Terminal
    {
        private final String code;

        private final String name;

        private final List<Step> steps;

        public Terminal(String code, String name, List<Step> steps)
        {
            this.code = code;
            this.name = name;
            this.steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public String getCode() { return code; }

        public String getName() { return name; }

        public List<Step> getSteps() { return steps; }
    }

    /** 漏斗步骤 */
    public static final class Step
    {
        private final String code;

        /** ENTRY 入口步；其余为 null */
        private final String role;

        /** false = 非严格转化（不是同一批人，不画转化率） */
        private final boolean strict;

        public Step(String code, String role, boolean strict)
        {
            this.code = code;
            this.role = role;
            this.strict = strict;
        }

        public String getCode() { return code; }

        public String getRole() { return role; }

        public boolean isStrict() { return strict; }
    }
}
