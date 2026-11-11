package com.fivetech.apikey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fivetech.apikey.domain.ApiKey;
import com.fivetech.apikey.mapper.ApiKeyMapper;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.system.service.ISysUserService;

/** 系统对接 API Key：生成、哈希、新建校验、调用校验、按绑定账号认证（不连库） */
class ApiKeyServiceTest
{
    private static final String LARK = "/notification/lark/send";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);

    /** 内存版 sys_api_key */
    static final class MemMapper implements ApiKeyMapper
    {
        final Map<Long, ApiKey> rows = new HashMap<>();
        final List<String> touched = new ArrayList<>();
        long seq;

        @Override
        public List<ApiKey> selectList()
        {
            return new ArrayList<>(rows.values());
        }

        @Override
        public ApiKey selectById(Long keyId)
        {
            return rows.get(keyId);
        }

        @Override
        public ApiKey selectByHash(String keyHash)
        {
            return rows.values().stream().filter(k -> keyHash.equals(k.getKeyHash())).findFirst().orElse(null);
        }

        @Override
        public int insert(ApiKey key)
        {
            key.setKeyId(++seq);
            key.setStatus("0");
            rows.put(key.getKeyId(), key);
            return 1;
        }

        @Override
        public int revoke(Long keyId, String operator)
        {
            ApiKey k = rows.get(keyId);
            if (k == null || !"0".equals(k.getStatus()))
            {
                return 0;
            }
            k.setStatus("1");
            k.setUpdateBy(operator);
            return 1;
        }

        @Override
        public int touch(Long keyId, String ip)
        {
            touched.add(keyId + "@" + ip);
            return 1;
        }
    }

    /** 只实现 selectUserById：1 = 启用的 lark_bot，2 = 停用 */
    private static ISysUserService users()
    {
        return (ISysUserService) Proxy.newProxyInstance(ISysUserService.class.getClassLoader(), new Class<?>[] { ISysUserService.class },
            (proxy, method, args) -> {
                if (!"selectUserById".equals(method.getName()))
                {
                    throw new UnsupportedOperationException(method.getName());
                }
                Long id = (Long) args[0];
                if (id != 1L && id != 2L)
                {
                    return null;
                }
                SysUser u = new SysUser();
                u.setUserId(id);
                u.setUserName(id == 1L ? "lark_bot" : "disabled_bot");
                u.setDeptId(100L);
                u.setStatus(id == 1L ? "0" : "1");
                u.setDelFlag("0");
                return u;
            });
    }

    private final MemMapper mapper = new MemMapper();

    private final ApiKeyService service = new ApiKeyService(mapper, users(), new String[] { LARK });

    private static ApiKey req(String name, Long userId, String paths, String ips, LocalDateTime expire)
    {
        ApiKey k = new ApiKey();
        k.setKeyName(name);
        k.setUserId(userId);
        k.setAllowedPaths(paths);
        k.setAllowedIps(ips);
        k.setExpireTime(expire);
        return k;
    }

    private static int code(Runnable r)
    {
        try
        {
            r.run();
        }
        catch (ServiceException e)
        {
            return e.getCode();
        }
        return 0;
    }

    @Test
    void generatedKeysAreRandomAndOnlyHashIsStored()
    {
        String a = ApiKeyService.generate();
        String b = ApiKeyService.generate();
        assertTrue(a.startsWith("ftk_"));
        assertEquals(44, a.length());                    // ftk_ + 30 字节 base64url
        assertFalse(a.equals(b));
        assertEquals(64, ApiKeyService.sha256(a).length());
        assertEquals(ApiKeyService.sha256(a), ApiKeyService.sha256(a));

        ApiKeyService.Created c = service.create(req("數據平台", 1L, null, null, null), "admin");
        assertTrue(c.secret().startsWith("ftk_"));
        ApiKey saved = c.key();
        assertEquals(ApiKeyService.sha256(c.secret()), saved.getKeyHash());
        assertEquals(c.secret().substring(0, 12), saved.getKeyPrefix());
        assertEquals(LARK, saved.getAllowedPaths(), "不填接口 = 全部支持的接口");
        assertNull(saved.getExpireTime(), "不填到期 = 永不过期");
        assertEquals("admin", saved.getCreateBy());
    }

    @Test
    void createValidatesInput()
    {
        assertEquals(4001, code(() -> service.create(req(" ", 1L, null, null, null), "admin")));
        assertEquals(4001, code(() -> service.create(req("x", null, null, null, null), "admin")));
        assertEquals(4001, code(() -> service.create(req("x", 1L, "/system/user", null, null), "admin")), "不支持的接口");
        assertEquals(4001, code(() -> service.create(req("x", 1L, null, "1.2.3.4,abc;rm", null), "admin")), "IP 格式");
        assertEquals(4001, code(() -> service.create(req("x", 1L, null, null, LocalDateTime.now().minusDays(1)), "admin")), "到期在过去");
        assertEquals(0, mapper.rows.size());
    }

    @Test
    void checkCoversEveryRejection()
    {
        String ok = service.create(req("ok", 1L, LARK, null, null), "admin").secret();
        assertTrue(service.check(ok, LARK, "9.9.9.9", NOW).ok());
        assertEquals("格式不对", service.check("Bearer xyz", LARK, null, NOW).reason());
        assertEquals("接口不支持 API Key", service.check(ok, "/system/user/list", null, NOW).reason());
        assertEquals("Key 不存在", service.check(ApiKeyService.generate(), LARK, null, NOW).reason());

        ApiKeyService.Created ipOnly = service.create(req("ip", 1L, null, "10.0.0.1, 10.0.0.2", null), "admin");
        assertTrue(service.check(ipOnly.secret(), LARK, "10.0.0.2", NOW).ok());
        assertTrue(service.check(ipOnly.secret(), LARK, "10.0.0.3", NOW).reason().contains("不在白名单"));
        assertFalse(service.check(ipOnly.secret(), LARK, null, NOW).ok());

        ApiKeyService.Created expiring = service.create(req("exp", 1L, null, null, LocalDateTime.now().plusDays(1)), "admin");
        assertTrue(service.check(expiring.secret(), LARK, null, LocalDateTime.now()).ok());
        assertEquals("Key 已过期", service.check(expiring.secret(), LARK, null, LocalDateTime.now().plusDays(2)).reason());

        service.revoke(ipOnly.key().getKeyId(), "admin");
        assertEquals("Key 已作废", service.check(ipOnly.secret(), LARK, "10.0.0.2", NOW).reason());
        assertEquals(404, code(() -> service.revoke(999L, "admin")));

        // Key 自己的接口范围：系统多支持一个接口，但这个 Key 只开了 Lark
        ApiKeyService wider = new ApiKeyService(mapper, users(), new String[] { LARK + ",/other/send" });
        String larkOnly = wider.create(req("lark", 1L, LARK, null, null), "admin").secret();
        assertEquals("Key 不能访问该接口", wider.check(larkOnly, "/other/send", null, NOW).reason());
        assertTrue(wider.supports("/other/send"));
        assertFalse(service.supports("/other/send"));
    }

    @Test
    void authenticateLogsInAsBoundUserWithItsPermissions()
    {
        String raw = service.create(req("bot", 1L, null, null, null), "admin").secret();
        ApiKeyService.Result r = service.authenticate(raw, LARK, "1.1.1.1", NOW, u -> Set.of("notification:lark:send"));
        assertTrue(r.ok());
        assertEquals("lark_bot", r.user().getUsername());
        assertEquals(Set.of("notification:lark:send"), r.user().getPermissions());
        assertEquals("bot", r.key().getKeyName());
        service.touch(r.key(), "1.1.1.1");
        assertEquals(List.of(r.key().getKeyId() + "@1.1.1.1"), mapper.touched);

        String disabled = service.create(req("disabled", 2L, null, null, null), "admin").secret();
        ApiKeyService.Result d = service.authenticate(disabled, LARK, null, NOW, u -> Set.of());
        assertFalse(d.ok());
        assertEquals("绑定的账号不存在或已停用", d.reason());

        ApiKeyService.Result bad = service.authenticate("ftk_nope_nope_nope_nope", LARK, null, NOW, u -> Set.of());
        assertFalse(bad.ok());
        assertNull(bad.key());
    }
}
