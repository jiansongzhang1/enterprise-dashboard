package com.fivetech.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 用户历史密码（只存被替换掉的旧密码 BCrypt 值）
 *
 * @author fivetech
 */
public interface SysUserPwdHistoryMapper
{
    /**
     * 最近 limit 条旧密码（新到旧）
     */
    public List<String> selectRecentPasswords(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 记录一条被替换掉的旧密码
     */
    public int insertPassword(@Param("userId") Long userId, @Param("password") String password);

    /**
     * 只保留最近 keep 条，删除更早的
     */
    public int deleteOlderThan(@Param("userId") Long userId, @Param("keep") int keep);
}
