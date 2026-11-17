package com.fivetech.common.enums;

/**
 * 业务操作类型
 * 
 * @author fivetech
 */
public enum BusinessType
{
    /**
     * 其它
     */
    OTHER,

    /**
     * 新增
     */
    INSERT,

    /**
     * 修改
     */
    UPDATE,

    /**
     * 删除
     */
    DELETE,

    /**
     * 授权
     */
    GRANT,

    /**
     * 导出
     */
    EXPORT,

    /**
     * 导入
     */
    IMPORT,

    /**
     * 强退
     */
    FORCE,

    /**
     * 生成代码
     */
    GENCODE,
    
    /**
     * 清空数据
     */
    CLEAN,

    /**
     * 查询（数据查询接口：运营总览、推广分析、明细查询、监控规则与事件等；只记请求参数，不记返回）。
     * 存的是 ordinal，只能追加在最后，字典 sys_oper_type 对应 dict_value = 10
     */
    QUERY,
}
