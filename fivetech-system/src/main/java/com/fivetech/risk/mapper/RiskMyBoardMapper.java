package com.fivetech.risk.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.risk.my.RiskMyBoard;

/**
 * 我的榜单。所有查询都带 user_id + site_code，只能看、改自己的。
 *
 * @author fivetech
 */
public interface RiskMyBoardMapper
{
    List<RiskMyBoard> selectMine(@Param("userId") Long userId, @Param("siteCode") String siteCode);

    RiskMyBoard selectMineById(@Param("userId") Long userId, @Param("siteCode") String siteCode, @Param("boardId") Long boardId);

    int countMine(@Param("userId") Long userId, @Param("siteCode") String siteCode);

    int countName(@Param("userId") Long userId, @Param("siteCode") String siteCode, @Param("name") String name,
        @Param("excludeId") Long excludeId);

    int insert(RiskMyBoard board);

    int update(RiskMyBoard board);

    int softDelete(@Param("userId") Long userId, @Param("siteCode") String siteCode, @Param("boardId") Long boardId,
        @Param("updateBy") String updateBy);

    int updateOrder(@Param("userId") Long userId, @Param("siteCode") String siteCode, @Param("boardId") Long boardId,
        @Param("orderNum") int orderNum);
}
