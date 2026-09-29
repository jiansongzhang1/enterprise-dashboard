package com.fivetech.system.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fivetech.common.utils.StringUtils;
import com.fivetech.system.domain.SysAccountLink;
import com.fivetech.system.mapper.SysAccountLinkMapper;
import com.fivetech.system.service.ISysAccountLinkService;

/**
 * 账号邮件一次性链接 服务实现
 *
 * @author fivetech
 */
@Service
public class SysAccountLinkServiceImpl implements ISysAccountLinkService
{
    /** 凭证随机字节数：256 位，高于设计要求的 128 位 */
    private static final int TOKEN_BYTES = 32;

    /** Base64URL 编码 32 字节 = 43 字符；用于快速拒绝明显非法的输入 */
    private static final int TOKEN_LENGTH = 43;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private SysAccountLinkMapper linkMapper;

    @Override
    @Transactional
    public IssuedLink issue(Long userId, String linkType, int expireHours, String createBy)
    {
        if (userId == null || StringUtils.isEmpty(linkType) || expireHours <= 0)
        {
            throw new IllegalArgumentException("userId, linkType and positive expireHours are required");
        }
        Date now = new Date();
        linkMapper.revokeUnusedByUserId(userId, now);

        String token = newToken();
        SysAccountLink link = new SysAccountLink();
        link.setUserId(userId);
        link.setLinkType(linkType);
        link.setTokenHash(hash(token));
        link.setExpireTime(new Date(now.getTime() + expireHours * 3600_000L));
        link.setCreateBy(createBy == null ? "" : createBy);
        link.setCreateTime(now);
        linkMapper.insertLink(link);
        return new IssuedLink(token, link.getExpireTime());
    }

    @Override
    public SysAccountLink findUsable(String rawToken, String linkType)
    {
        if (!looksLikeToken(rawToken))
        {
            return null;
        }
        SysAccountLink link = linkMapper.selectByTokenHash(hash(rawToken.trim()));
        if (link == null || !link.isUsable(new Date()))
        {
            return null;
        }
        if (linkType != null && !linkType.equals(link.getLinkType()))
        {
            return null;
        }
        return link;
    }

    @Override
    public boolean markUsed(Long linkId)
    {
        return linkMapper.markUsed(linkId, new Date()) == 1;
    }

    @Override
    public int revokeAll(Long userId)
    {
        return linkMapper.revokeUnusedByUserId(userId, new Date());
    }

    static String newToken()
    {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean looksLikeToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String t = token.trim();
        return t.length() == TOKEN_LENGTH && t.matches("[A-Za-z0-9_-]+");
    }

    static String hash(String token)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
