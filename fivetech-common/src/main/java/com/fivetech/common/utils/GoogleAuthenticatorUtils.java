package com.fivetech.common.utils;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Google Authenticator TOTP 工具类
 *
 * <p>使用 Base32 密钥和 30 秒时间步长生成/校验 6 位动态验证码。</p>
 *
 * @author codex
 */
public final class GoogleAuthenticatorUtils
{
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private static final int DEFAULT_CODE_DIGITS = 6;

    private static final int DEFAULT_TIME_STEP_SECONDS = 30;

    private static final int DEFAULT_WINDOW_SIZE = 1;

    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private GoogleAuthenticatorUtils()
    {
    }

    /**
     * 校验 Google Authenticator 6 位验证码
     *
     * @param secret Base32 密钥
     * @param code 用户输入验证码
     * @return 是否匹配
     */
    public static boolean verifyCode(String secret, String code)
    {
        return verifyCode(secret, code, DEFAULT_WINDOW_SIZE, DEFAULT_TIME_STEP_SECONDS, DEFAULT_CODE_DIGITS);
    }

    /**
     * 校验 Google Authenticator 验证码
     *
     * @param secret Base32 密钥
     * @param code 用户输入验证码
     * @param window 允许的时间步长窗口，0 表示仅当前窗口
     * @param timeStepSeconds 时间步长秒数
     * @param digits 验证码位数
     * @return 是否匹配
     */
    public static boolean verifyCode(String secret, String code, int window, int timeStepSeconds, int digits)
    {
        if (StringUtils.isEmpty(secret) || StringUtils.isEmpty(code))
        {
            return false;
        }

        secret = secret.trim();
        code = code.trim();

        if (secret.isEmpty() || code.isEmpty())
        {
            return false;
        }

        if (!code.matches("\\d{" + digits + "}"))
        {
            return false;
        }

        long currentCounter = System.currentTimeMillis() / 1000L / timeStepSeconds;
        for (int offset = -window; offset <= window; offset++)
        {
            String generated = generateCode(secret, currentCounter + offset, digits);
            if (code.equals(generated))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成指定时间片的验证码
     *
     * @param secret Base32 密钥
     * @param counter 时间片计数器
     * @param digits 验证码位数
     * @return 验证码
     */
    public static String generateCode(String secret, long counter, int digits)
    {
        byte[] key = decodeBase32(secret);
        byte[] data = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
        byte[] hash = hmacSha1(key, data);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int mod = (int) Math.pow(10, digits);
        int otp = binary % mod;
        return String.format("%0" + digits + "d", otp);
    }

    /**
     * 生成 Google Authenticator 密钥
     *
     * @param length 密钥长度
     * @return Base32 密钥
     */
    public static String generateSecret(int length)
    {
        if (length <= 0)
        {
            throw new IllegalArgumentException("Secret length must be positive");
        }

        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++)
        {
            builder.append(BASE32_CHARS.charAt(SECURE_RANDOM.nextInt(BASE32_CHARS.length())));
        }
        return builder.toString();
    }

    /**
     * 构建 otpauth 绑定地址
     *
     * @param issuer 发行方名称
     * @param account 账号名
     * @param secret Base32 密钥
     * @return otpauth:// URI
     */
    public static String buildOtpAuthUrl(String issuer, String account, String secret)
    {
        if (StringUtils.isEmpty(issuer) || StringUtils.isEmpty(account) || StringUtils.isEmpty(secret))
        {
            throw new IllegalArgumentException("Issuer, account and secret are required");
        }

        String encodedIssuer = urlEncode(issuer);
        String encodedAccount = urlEncode(account);
        String encodedSecret = urlEncode(secret);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount + "?secret=" + encodedSecret + "&issuer=" + encodedIssuer;
    }

    private static byte[] hmacSha1(byte[] key, byte[] data)
    {
        try
        {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("Unable to calculate Google Authenticator code", e);
        }
    }

    private static byte[] decodeBase32(String secret)
    {
        String normalized = secret.replace(" ", "").replace("-", "").replace("=", "").toUpperCase();
        if (normalized.isEmpty())
        {
            return new byte[0];
        }

        int buffer = 0;
        int bitsLeft = 0;
        byte[] output = new byte[normalized.length() * 5 / 8 + 1];
        int index = 0;

        for (int i = 0; i < normalized.length(); i++)
        {
            char c = normalized.charAt(i);
            int val = base32Value(c);
            if (val < 0)
            {
                throw new IllegalArgumentException("Invalid Base32 secret");
            }

            buffer <<= 5;
            buffer |= val;
            bitsLeft += 5;

            if (bitsLeft >= 8)
            {
                output[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }

        byte[] decoded = new byte[index];
        System.arraycopy(output, 0, decoded, 0, index);
        return decoded;
    }

    private static int base32Value(char c)
    {
        if (c >= 'A' && c <= 'Z')
        {
            return c - 'A';
        }
        if (c >= '2' && c <= '7')
        {
            return 26 + (c - '2');
        }
        return -1;
    }

    private static String urlEncode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
