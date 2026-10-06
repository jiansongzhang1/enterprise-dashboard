package com.fivetech.dashboard.gateway.uds;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.fivetech.common.utils.StringUtils;

/**
 * UDS 双向 TLS（mTLS）支持：用 PEM 格式的 CA 证书、客户端证书和私钥构造只给 UDS 用的 {@link SSLContext}。
 * <p>
 * 为什么不用 {@code -Djavax.net.ssl.keyStore / trustStore}：那是 JVM 全局设置，会让本服务调用
 * Lark、Telegram、邮件等公网 HTTPS 时用错信任库而全部握手失败。这里只影响 UDS 的 HttpClient。
 * <p>
 * 只依赖 JDK，不引入 BouncyCastle。支持的私钥格式：
 * <ul>
 *   <li>{@code -----BEGIN PRIVATE KEY-----}（PKCS#8，RSA / EC / Ed25519）；</li>
 *   <li>{@code -----BEGIN RSA PRIVATE KEY-----}（PKCS#1）；</li>
 *   <li>{@code -----BEGIN EC PRIVATE KEY-----}（SEC1）。</li>
 * </ul>
 * 加密的私钥（{@code ENCRYPTED PRIVATE KEY} 或带 Proc-Type 头）不支持，请先用
 * {@code openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pk8.key} 转成不加密的 PKCS#8。
 *
 * @author fivetech
 */
final class UdsTlsSupport
{
    private static final Pattern PEM_BLOCK = Pattern.compile(
        "-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    /** rsaEncryption 1.2.840.113549.1.1.1 */
    private static final byte[] OID_RSA = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};

    /** id-ecPublicKey 1.2.840.10045.2.1 */
    private static final byte[] OID_EC = {0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01};

    private UdsTlsSupport()
    {
    }

    /**
     * 构造 SSLContext。三个路径都可选：
     * <ul>
     *   <li>只配 CA：校验服务端证书，不出示客户端证书（单向 TLS + 私有 CA）；</li>
     *   <li>配客户端证书 + 私钥：双向 TLS；</li>
     *   <li>都不配：返回 null，调用方用 JDK 默认（公网 CA）。</li>
     * </ul>
     */
    static SSLContext build(UdsProperties.Tls tls) throws Exception
    {
        if (tls == null || !tls.isConfigured())
        {
            return null;
        }
        boolean hasCert = StringUtils.isNotEmpty(tls.getClientCert());
        boolean hasKey = StringUtils.isNotEmpty(tls.getClientKey());
        if (hasCert != hasKey)
        {
            throw new IllegalStateException("dashboard.gateway.uds.tls 的 client-cert 与 client-key 必须同时配置");
        }

        javax.net.ssl.TrustManager[] trustManagers = null;
        if (StringUtils.isNotEmpty(tls.getCaCert()))
        {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            int i = 0;
            for (X509Certificate ca : readCertificates(tls.getCaCert()))
            {
                trust.setCertificateEntry("uds-ca-" + (i++), ca);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            trustManagers = tmf.getTrustManagers();
        }

        javax.net.ssl.KeyManager[] keyManagers = null;
        if (hasCert)
        {
            List<X509Certificate> chain = readCertificates(tls.getClientCert());
            PrivateKey key = readPrivateKey(tls.getClientKey());
            char[] password = new char[0];
            KeyStore identity = KeyStore.getInstance("PKCS12");
            identity.load(null, null);
            identity.setKeyEntry("uds-client", key, password, chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(identity, password);
            keyManagers = kmf.getKeyManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, null);
        return context;
    }

    /** 证书摘要，启动日志用：只打主题和到期日，不打任何密钥材料 */
    static String describe(String certPath)
    {
        try
        {
            X509Certificate c = readCertificates(certPath).get(0);
            return c.getSubjectX500Principal().getName() + "（有效期至 " + c.getNotAfter() + "）";
        }
        catch (Exception e)
        {
            return certPath;
        }
    }

    // ===================== PEM 解析 =====================

    private static List<X509Certificate> readCertificates(String path) throws Exception
    {
        byte[] bytes = Files.readAllBytes(Path.of(path));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(bytes));
        List<X509Certificate> list = new ArrayList<>();
        for (Certificate c : certs)
        {
            list.add((X509Certificate) c);
        }
        if (list.isEmpty())
        {
            throw new IllegalStateException("证书文件里没有找到证书：" + path);
        }
        return list;
    }

    private static PrivateKey readPrivateKey(String path) throws Exception
    {
        String text = new String(Files.readAllBytes(Path.of(path)), StandardCharsets.US_ASCII);
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find())
        {
            String type = m.group(1).trim();
            String body = m.group(2);
            if (body.contains("Proc-Type") || type.startsWith("ENCRYPTED"))
            {
                throw new IllegalStateException("私钥是加密的，暂不支持：" + path
                    + "。请执行 openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pk8.key 转换后再配置");
            }
            byte[] der = Base64.getMimeDecoder().decode(body.trim());
            switch (type)
            {
                case "PRIVATE KEY":
                    return pkcs8(der);
                case "RSA PRIVATE KEY":
                    return pkcs8(wrapPkcs8(seq(OID_RSA, new byte[] {0x05, 0x00}), der));
                case "EC PRIVATE KEY":
                    return pkcs8(wrapPkcs8(seq(OID_EC, ecCurveOid(der)), der));
                default:
                    // EC PARAMETERS 等块直接跳过，继续找真正的私钥
                    break;
            }
        }
        throw new IllegalStateException("私钥文件里没有找到受支持的 PEM 私钥：" + path);
    }

    private static PrivateKey pkcs8(byte[] der) throws Exception
    {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        Exception last = null;
        for (String alg : new String[] {"RSA", "EC", "Ed25519", "RSASSA-PSS"})
        {
            try
            {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            }
            catch (Exception e)
            {
                last = e;
            }
        }
        throw new IllegalStateException("无法识别的私钥算法", last);
    }

    /** PrivateKeyInfo ::= SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING privateKey } */
    private static byte[] wrapPkcs8(byte[] algorithmIdentifier, byte[] rawKey) throws IOException
    {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(new byte[] {0x02, 0x01, 0x00});
        content.write(algorithmIdentifier);
        content.write(tlv(0x04, rawKey));
        return tlv(0x30, content.toByteArray());
    }

    private static byte[] seq(byte[] a, byte[] b) throws IOException
    {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(a);
        content.write(b);
        return tlv(0x30, content.toByteArray());
    }

    /**
     * 从 SEC1 ECPrivateKey 里取曲线 OID：
     * SEQUENCE { INTEGER 1, OCTET STRING key, [0] OID parameters, [1] BIT STRING publicKey }
     */
    private static byte[] ecCurveOid(byte[] sec1)
    {
        int[] pos = {0};
        expect(sec1, pos, 0x30);
        readLength(sec1, pos);
        skip(sec1, pos);                   // version
        skip(sec1, pos);                   // privateKey
        while (pos[0] < sec1.length)
        {
            int tag = sec1[pos[0]] & 0xff;
            if (tag == 0xa0)
            {
                pos[0]++;
                int len = readLength(sec1, pos);
                byte[] oid = new byte[len];
                System.arraycopy(sec1, pos[0], oid, 0, len);
                return oid;                // 内容就是一个完整的 OID TLV
            }
            skip(sec1, pos);
        }
        throw new IllegalStateException("EC 私钥里没有曲线参数，请先转成 PKCS#8：openssl pkcs8 -topk8 -nocrypt");
    }

    private static void expect(byte[] der, int[] pos, int tag)
    {
        if ((der[pos[0]] & 0xff) != tag)
        {
            throw new IllegalStateException("私钥 DER 结构不符合预期");
        }
        pos[0]++;
    }

    private static void skip(byte[] der, int[] pos)
    {
        pos[0]++;
        int len = readLength(der, pos);
        pos[0] += len;
    }

    private static int readLength(byte[] der, int[] pos)
    {
        int b = der[pos[0]++] & 0xff;
        if (b < 0x80)
        {
            return b;
        }
        int n = b & 0x7f;
        int len = 0;
        for (int i = 0; i < n; i++)
        {
            len = (len << 8) | (der[pos[0]++] & 0xff);
        }
        return len;
    }

    private static byte[] tlv(int tag, byte[] value) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80)
        {
            out.write(len);
        }
        else if (len < 0x100)
        {
            out.write(0x81);
            out.write(len);
        }
        else if (len < 0x10000)
        {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        else
        {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        out.write(value);
        return out.toByteArray();
    }
}
