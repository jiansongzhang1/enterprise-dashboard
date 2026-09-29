package com.fivetech.system.mapper;

import java.util.Date;
import org.apache.ibatis.annotations.Param;
import com.fivetech.system.domain.SysAccountLink;

/**
 * 账号邮件一次性链接 数据层
 *
 * @author fivetech
 */
public interface SysAccountLinkMapper
{
    public int insertLink(SysAccountLink link);

    public SysAccountLink selectByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 作废该账号所有尚未使用的链接。
     */
    public int revokeUnusedByUserId(@Param("userId") Long userId, @Param("now") Date now);

    /**
     * 标记链接已使用。带条件更新，并发重复提交时只有一次能成功。
     *
     * @return 1 成功；0 表示已被使用、已作废或已过期
     */
    public int markUsed(@Param("linkId") Long linkId, @Param("now") Date now);
}
