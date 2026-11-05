package com.fivetech.copilot.mapper;

import org.apache.ibatis.annotations.Param;
import com.fivetech.copilot.domain.CopilotUsage;

/**
 * AI 调用记录（copilot_usage_log）。
 *
 * @author fivetech
 */
public interface CopilotUsageMapper
{
    int insert(CopilotUsage usage);

    /** 某功能今天（数据库日期）已调用模型的次数（成功、失败、被拒都算，因为都消耗了调用） */
    int countToday(@Param("feature") String feature);
}
