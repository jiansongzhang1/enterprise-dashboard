package com.fivetech.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fivetech.apikey.domain.ApiKey;
import com.fivetech.apikey.mapper.ApiKeyMapper;
import com.fivetech.common.core.domain.entity.SysUser;
import com.fivetech.common.core.domain.model.LoginUser;
import com.fivetech.common.enums.UserStatus;
import com.fivetech.common.exception.ServiceException;
import com.fivetech.system.service.ISysUserService;

/**
 * 系统对接用的 API Key：给不方便走「账号 + 密码 + Google 动态码」登录的程序调用指定接口（目前只有发送 Lark 消息）。
 *
 * <ul>
 *   <li>请求头 {@value #HEADER}: ftk_…；只在 {@code security.api-key.paths} 列出的接口上认，其他接口带了也不算登录；</li>
 *   <li>每个 Key 绑定一个后台账号：权限、操作日志都按这个账号走（接口上的 @PreAuthorize 照常生效）；</li>
 *   <li>Key 自己再限定能访问的接口、来源 IP、到期时间；作废后立即失效；</li>
 *   <li>库里只存 SHA-256，明文只在创建时返回一次；Key 是 30 字节随机数，不需要加盐慢哈希。</li>
 * </ul>
 *
 * @author fivetech
 */
@Service
public class ApiKeyService
{
    /** 请求头 */
    public static final String HEADER = "X-Api-Key";

    /** 明文前缀，方便在泄露扫描、日志里认出来 */
    static final String PREFIX = "ftk_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyMapper mapper;

    private final ISysUserService users;

    /** 允许用 API Key 访问的接口（上限）；每个 Key 的 allowed_paths 只能在这里面选 */
    private final Set<String> supportedPaths;

    public ApiKeyService(ApiKeyMapper mapper, ISysUserService users,
        @Value("${security.api-key.paths:/notification/lark/send}") String[] paths)
    {
        this.mapper = mapper;
        this.users = users;
        this.supportedPaths = new LinkedHashSet<>(csv(String.join(",", paths)));
    }

    /** 认证结果：user 不为 null 即通过（按绑定账号登录本次请求）；reason 只写日志，不返回给调用方 */
    public record Result(LoginUser user, ApiKey key, String reason)
    {
        public boolean ok()
        {
            return user != null;
        }
    }

    /**
     * 认证一次调用：校验 Key（{@link #check}），再加载绑定的后台账号（停用、删除的拒绝），权限按账号角色现查。
     *
     * @param permissions 账号 → 菜单权限（框架的 SysPermissionService::getMenuPermission）
     */
    public Result authenticate(String raw, String path, String ip, LocalDateTime now, Function<SysUser, Set<String>> permissions)
    {
        Check c = check(raw, path, ip, now);
        if (!c.ok())
        {
            return new Result(null, null, c.reason());
        }
        SysUser user = users.selectUserById(c.key().getUserId());
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag()) || UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            return new Result(null, c.key(), "绑定的账号不存在或已停用");
        }
        return new Result(new LoginUser(user.getUserId(), user.getDeptId(), user, permissions.apply(user)), c.key(), null);
    }

    /** 校验结果：key 不为 null 即通过；reason 只写日志，不返回给调用方 */
    public record Check(ApiKey key, String reason)
    {
        public boolean ok()
        {
            return key != null;
        }
    }

    /** 这个接口支不支持 API Key（不支持的接口带了 Key 也当没带） */
    public boolean supports(String path)
    {
        return path != null && supportedPaths.contains(path);
    }

    /**
     * 校验一次调用。
     *
     * @param raw  请求头里的明文
     * @param path 请求路径（不含 context path）
     * @param ip   来源 IP
     */
    public Check check(String raw, String path, String ip, LocalDateTime now)
    {
        if (raw == null || !raw.startsWith(PREFIX) || raw.length() < 20)
        {
            return new Check(null, "格式不对");
        }
        if (!supports(path))
        {
            return new Check(null, "接口不支持 API Key");
        }
        ApiKey k = mapper.selectByHash(sha256(raw));
        if (k == null)
        {
            return new Check(null, "Key 不存在");
        }
        if (!"0".equals(k.getStatus()))
        {
            return new Check(null, "Key 已作废");
        }
        if (k.getExpireTime() != null && !now.isBefore(k.getExpireTime()))
        {
            return new Check(null, "Key 已过期");
        }
        if (!csv(k.getAllowedPaths()).contains(path))
        {
            return new Check(null, "Key 不能访问该接口");
        }
        List<String> ips = csv(k.getAllowedIps());
        if (!ips.isEmpty() && (ip == null || !ips.contains(ip)))
        {
            return new Check(null, "来源 IP " + ip + " 不在白名单");
        }
        return new Check(k, null);
    }

    /** 记最近使用时间与 IP（同一分钟、同一 IP 不重复写库） */
    public void touch(ApiKey key, String ip)
    {
        mapper.touch(key.getKeyId(), ip);
    }

    public List<ApiKey> list()
    {
        return mapper.selectList();
    }

    /**
     * 新建：返回的 Key 带明文（只此一次），之后列表里只有前缀。
     * userId 是否存在、是否启用由调用方（管理接口）先查。
     */
    public Created create(ApiKey req, String operator)
    {
        String name = req.getKeyName() == null ? "" : req.getKeyName().trim();
        if (name.isEmpty() || name.length() > 64)
        {
            throw new ServiceException("名稱必填，最多 64 個字", 4001);
        }
        if (req.getUserId() == null)
        {
            throw new ServiceException("請選擇綁定的後台賬號", 4001);
        }
        List<String> paths = csv(req.getAllowedPaths());
        if (paths.isEmpty())
        {
            paths = new ArrayList<>(supportedPaths);
        }
        for (String p : paths)
        {
            if (!supportedPaths.contains(p))
            {
                throw new ServiceException("接口 " + p + " 不支持 API Key（可選：" + String.join("、", supportedPaths) + "）", 4001);
            }
        }
        List<String> ips = csv(req.getAllowedIps());
        for (String ip : ips)
        {
            if (!ip.matches("[0-9a-fA-F:.]{2,45}"))
            {
                throw new ServiceException("IP 格式不對：" + ip, 4001);
            }
        }
        if (req.getExpireTime() != null && !req.getExpireTime().isAfter(LocalDateTime.now()))
        {
            throw new ServiceException("到期時間須晚於現在（不填 = 永不過期）", 4001);
        }
        String raw = generate();
        ApiKey k = new ApiKey();
        k.setKeyName(name);
        k.setKeyPrefix(raw.substring(0, PREFIX.length() + 8));
        k.setKeyHash(sha256(raw));
        k.setUserId(req.getUserId());
        k.setAllowedPaths(String.join(",", paths));
        k.setAllowedIps(ips.isEmpty() ? null : String.join(",", ips));
        k.setExpireTime(req.getExpireTime());
        k.setRemark(req.getRemark());
        k.setCreateBy(operator);
        mapper.insert(k);
        return new Created(mapper.selectById(k.getKeyId()), raw);
    }

    /** 新建结果：key 为库里的记录，secret 为明文（只返回这一次） */
    public record Created(ApiKey key, String secret)
    {
    }

    /** 作废：立即失效，不能恢复（要用再新建） */
    public void revoke(Long keyId, String operator)
    {
        ApiKey k = mapper.selectById(keyId);
        if (k == null)
        {
            throw new ServiceException("API Key 不存在", 404);
        }
        mapper.revoke(keyId, operator);
    }

    static String generate()
    {
        byte[] b = new byte[30];
        RANDOM.nextBytes(b);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String raw)
    {
        try
        {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d)
            {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    static List<String> csv(String s)
    {
        List<String> out = new ArrayList<>();
        if (s != null)
        {
            for (String x : s.split(","))
            {
                String t = x.trim();
                if (!t.isEmpty() && !out.contains(t))
                {
                    out.add(t);
                }
            }
        }
        return out;
    }
}
