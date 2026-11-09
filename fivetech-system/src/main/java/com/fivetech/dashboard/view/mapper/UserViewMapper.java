package com.fivetech.dashboard.view.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.dashboard.view.domain.UserView;
import com.fivetech.dashboard.view.domain.ViewPreset;
import com.fivetech.dashboard.view.domain.ViewSlot;
import com.fivetech.dashboard.view.domain.ViewSlotItem;

/**
 * 个人视图：定义表（配置位、可选项、预置组合）与个人视图表。
 *
 * @author fivetech
 */
public interface UserViewMapper
{
    /** 全部配置位（含停用） */
    List<ViewSlot> selectSlots();

    /** 全部可选项（含停用） */
    List<ViewSlotItem> selectSlotItems();

    List<ViewPreset> selectPresets();

    /** 定义表的版本（行数 + 最后修改时间），热加载用 */
    String selectDefinitionVersion();

    /** 某人在某站点的全部个人视图 */
    List<UserView> selectUserViews(@Param("userId") Long userId, @Param("siteCode") String siteCode);

    UserView selectUserView(@Param("userId") Long userId, @Param("siteCode") String siteCode, @Param("slotCode") String slotCode);

    /** 插入或按 用户 + 站点 + 配置位 覆盖 */
    int upsertUserView(UserView view);

    int deleteUserView(@Param("userId") Long userId, @Param("siteCode") String siteCode, @Param("slotCode") String slotCode);
}
