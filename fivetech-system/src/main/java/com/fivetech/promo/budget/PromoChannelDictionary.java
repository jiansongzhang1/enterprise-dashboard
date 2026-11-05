package com.fivetech.promo.budget;

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
 * 推广分析 · 渠道字典：推广商及其推广渠道，来自配置中心 {@link ConfigCodes#PROMO_CHANNELS}
 * （「系统管理 › 配置中心」维护，站点自己的优先，没有再用全站通用）。
 *
 * <p>每个推广商、推广渠道有 ID（编码）与名称。FY-56 预算与消耗导入用它做模板预填与行级校验 V7–V13；
 * 推广分析的筛选项、投放日报、用户明细筛选也读它。顺序即字典顺序。没配置或配置读不了时字典为空。</p>
 *
 * @author fivetech
 */
@Component
public class PromoChannelDictionary
{
    private static final Logger log = LoggerFactory.getLogger(PromoChannelDictionary.class);

    /** 投放媒体未配置时的显示 */
    public static final String MEDIA_UNKNOWN = "未分類";

    private final ConfigCenterService configCenter;

    private final ObjectMapper json;

    public PromoChannelDictionary(ConfigCenterService configCenter, ObjectMapper json)
    {
        this.configCenter = configCenter;
        this.json = json;
    }

    /** 某站点的推广商（按配置顺序）；没配置返回空列表 */
    public List<Promoter> promotersOf(String siteCode)
    {
        String value;
        try
        {
            value = configCenter.value(ConfigCodes.PROMO_CHANNELS, siteCode);
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 读取配置中心 {} 失败（是否已执行 sql/release_next_postgresql.sql ④）：{}", ConfigCodes.PROMO_CHANNELS, e.getMessage());
            return List.of();
        }
        return value == null ? List.of() : parse(value);
    }

    /**
     * 解析配置值。结构不对（如还是只有名称的旧格式）时记警告、返回空字典——
     * 配置中心保存时已按 {@link ConfigCodes} 校验，这里只防手改数据库。
     */
    List<Promoter> parse(String value)
    {
        try
        {
            JsonNode root = json.readTree(value);
            String err = ConfigCodes.validatePromoChannels(root);
            if (err != null)
            {
                log.warn("[promo] 配置中心 {} 的配置值不符合结构（{}），按空字典处理；请在「系統管理 › 配置中心」按示例改為帶 id 的格式",
                    ConfigCodes.PROMO_CHANNELS, err);
                return List.of();
            }
            List<Promoter> out = new ArrayList<>();
            for (JsonNode p : root)
            {
                List<Channel> channels = new ArrayList<>();
                for (JsonNode c : p.get("channels"))
                {
                    channels.add(new Channel(text(c.get("id")), c.get("name").asString().trim(), optional(c, "media"),
                        upper(optional(c, "status"), "ACTIVE"), upper(optional(c, "entryType"), "LANDING")));
                }
                out.add(new Promoter(text(p.get("id")), p.get("name").asString().trim(), channels));
            }
            return out;
        }
        catch (RuntimeException e)
        {
            log.warn("[promo] 配置中心 {} 的配置值无法解析：{}", ConfigCodes.PROMO_CHANNELS, e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode id)
    {
        return id.isNumber() ? ConfigCodes.idKey(id) : id.asString().trim();
    }

    private static String optional(JsonNode node, String field)
    {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString().trim();
    }

    private static String upper(String v, String def)
    {
        return v == null || v.isEmpty() ? def : v.toUpperCase(Locale.ROOT);
    }

    /** 名称比较用的写法：去掉首尾空格、连续空白合成一个、不区分大小写 */
    static String key(String name)
    {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 推广商及其推广渠道 */
    public static class Promoter
    {
        private final String id;

        private final String name;

        private final List<Channel> channels;

        public Promoter(String id, String name, List<Channel> channels)
        {
            this.id = id;
            this.name = name;
            this.channels = channels == null ? List.of() : List.copyOf(channels);
        }

        public String getId() { return id; }

        public String getName() { return name; }

        public List<Channel> getChannels() { return channels; }
    }

    /** 推广渠道 */
    public static class Channel
    {
        private final String id;

        private final String name;

        private final String media;

        private final String status;

        private final String entryType;

        public Channel(String id, String name, String media, String status, String entryType)
        {
            this.id = id;
            this.name = name;
            this.media = media;
            this.status = status == null ? "ACTIVE" : status;
            this.entryType = entryType == null ? "LANDING" : entryType;
        }

        /** 测试与简单场景：只有 ID 和名称 */
        public Channel(String id, String name)
        {
            this(id, name, null, null, null);
        }

        public String getId() { return id; }

        public String getName() { return name; }

        /** 投放媒体；未配置为 null（显示时用 {@link #MEDIA_UNKNOWN}） */
        public String getMedia() { return media; }

        public String getStatus() { return status; }

        public boolean isDisabled() { return "DISABLED".equals(status); }

        public String getEntryType() { return entryType; }
    }
}
