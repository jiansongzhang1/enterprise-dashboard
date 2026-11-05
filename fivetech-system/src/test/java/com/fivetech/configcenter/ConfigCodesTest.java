package com.fivetech.configcenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 配置中心：PROMO_CHANNELS / PROMO_TERMINALS 的结构校验与 ID 比较 */
class ConfigCodesTest
{
    private final ObjectMapper json = JsonMapper.builder().build();

    private String check(String value)
    {
        return ConfigCodes.validatePromoChannels(json.readTree(value));
    }

    private String checkTerminals(String value)
    {
        return ConfigCodes.validatePromoTerminals(json.readTree(value));
    }

    @Test
    void promoChannelsValid()
    {
        assertNull(check(ConfigCodes.get(ConfigCodes.PROMO_CHANNELS).getExample()));
        assertNull(check("[]"));
        assertNull(check("[{\"id\":\"A1\",\"name\":\"hada\",\"channels\":[{\"id\":\"c1\",\"name\":\"x\"}]}]"));
    }

    @Test
    void promoChannelsInvalid()
    {
        assertTrue(check("{}").startsWith("應為數組"));
        assertTrue(check("[{\"name\":\"hada\",\"channels\":[]}]").contains("推廣商 ID"));
        assertTrue(check("[{\"id\":1,\"channels\":[]}]").contains("name"));
        assertEquals("推廣商 ID 重複：1", check("[{\"id\":1,\"name\":\"a\",\"channels\":[]},{\"id\":1.0,\"name\":\"b\",\"channels\":[]}]"));
        assertEquals("推廣商重複：Hada", check("[{\"id\":1,\"name\":\"hada\",\"channels\":[]},{\"id\":2,\"name\":\"Hada\",\"channels\":[]}]"));
        assertTrue(check("[{\"id\":1,\"name\":\"hada\",\"channels\":\"x\"}]").contains("應為數組"));
        // 旧格式：渠道只有名称
        assertTrue(check("[{\"id\":1,\"name\":\"hada\",\"channels\":[\"DSP\"]}]").contains("應為對象"));
        assertTrue(check("[{\"id\":1,\"name\":\"a\",\"channels\":[{\"id\":9,\"name\":\"X\"}]},{\"id\":2,\"name\":\"b\",\"channels\":[{\"id\":\"9\",\"name\":\"y\"}]}]")
            .startsWith("推廣渠道 ID 重複：9"));
        assertTrue(check("[{\"id\":1,\"name\":\"a\",\"channels\":[{\"id\":8,\"name\":\"X\"}]},{\"id\":2,\"name\":\"b\",\"channels\":[{\"id\":9,\"name\":\"x\"}]}]")
            .startsWith("推廣渠道重複：x"));
        assertTrue(check("[{\"id\":1,\"name\":\"a\",\"channels\":[{\"id\":8,\"name\":\"X\",\"status\":\"OFF\"}]}]").contains("status"));
        assertTrue(check("[{\"id\":1,\"name\":\"a\",\"channels\":[{\"id\":8,\"name\":\"X\",\"entryType\":\"APP\"}]}]").contains("entryType"));
    }

    @Test
    void idKeyComparesNumbersByValue()
    {
        assertEquals("1001", ConfigCodes.idKey("1001.0"));
        assertEquals("1001", ConfigCodes.idKey(" 1001 "));
        assertEquals("1001", ConfigCodes.idKey(json.readTree("1001.00")));
        assertEquals("1.5", ConfigCodes.idKey("1.50"));
        assertEquals("abc", ConfigCodes.idKey(" ABC "));
        assertEquals("", ConfigCodes.idKey(null));
    }

    @Test
    void promoTerminals()
    {
        assertNull(checkTerminals(ConfigCodes.get(ConfigCodes.PROMO_TERMINALS).getExample()));
        assertTrue(checkTerminals("[]").startsWith("應為對象"));
        assertTrue(checkTerminals("{\"terminals\":[{\"code\":\"all\",\"name\":\"x\"}]}").contains("保留"));
        assertTrue(checkTerminals("{\"terminals\":[{\"code\":\"a\",\"name\":\"x\"},{\"code\":\"A\",\"name\":\"y\"}]}").startsWith("終端重複"));
        assertTrue(checkTerminals("{\"terminals\":[{\"code\":\"a\",\"name\":\"x\",\"steps\":[{\"code\":\"pv\"}]}]}").contains("步驟 code"));
        assertTrue(checkTerminals("{\"landingMinPct\":120,\"terminals\":[]}").contains("landingMinPct"));
    }
}
