package com.fivetech.apikey.domain;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 系统对接用的 API Key（sys_api_key）。库里只存 SHA-256 哈希，明文只在创建时返回一次。
 *
 * @author fivetech
 */
public class ApiKey
{
    private Long keyId;

    /** 对接方名称，如「数据平台 · 数据更新通知」 */
    private String keyName;

    /** 明文前 12 位（ftk_ + 8 位），用来在列表、日志里认出是哪个 Key；不能用来调用 */
    private String keyPrefix;

    /** 明文的 SHA-256（十六进制） */
    @JsonIgnore
    private String keyHash;

    /** 绑定的后台账号：调用时按这个账号的角色权限判断能不能访问接口，操作日志也记在这个账号下 */
    private Long userId;

    /** 绑定账号的登录名（列表显示用，联表查出） */
    private String userName;

    /** 能访问的接口路径，逗号分隔，如 /notification/lark/send；必须在 security.api-key.paths 允许的范围内 */
    private String allowedPaths;

    /** 允许的来源 IP，逗号分隔；为空不限 */
    private String allowedIps;

    /** 到期时间；为空永不过期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    /** 0 启用 / 1 已作废 */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUsedTime;

    private String lastUsedIp;

    private String createBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String updateBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    private String remark;

    public Long getKeyId() { return keyId; }
    public void setKeyId(Long keyId) { this.keyId = keyId; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getAllowedPaths() { return allowedPaths; }
    public void setAllowedPaths(String allowedPaths) { this.allowedPaths = allowedPaths; }
    public String getAllowedIps() { return allowedIps; }
    public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastUsedTime() { return lastUsedTime; }
    public void setLastUsedTime(LocalDateTime lastUsedTime) { this.lastUsedTime = lastUsedTime; }
    public String getLastUsedIp() { return lastUsedIp; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
