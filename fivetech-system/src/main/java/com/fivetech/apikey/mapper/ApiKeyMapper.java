package com.fivetech.apikey.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.fivetech.apikey.domain.ApiKey;

/** sys_api_key */
public interface ApiKeyMapper
{
    List<ApiKey> selectList();

    ApiKey selectById(@Param("keyId") Long keyId);

    ApiKey selectByHash(@Param("keyHash") String keyHash);

    int insert(ApiKey key);

    int revoke(@Param("keyId") Long keyId, @Param("operator") String operator);

    int touch(@Param("keyId") Long keyId, @Param("ip") String ip);
}
