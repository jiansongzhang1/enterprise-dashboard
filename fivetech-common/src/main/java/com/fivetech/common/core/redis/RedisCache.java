package com.fivetech.common.core.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis缓存工具类。
 *
 * <p>保留原有方法签名，避免登录Token、密码重试和防重复提交业务改动；
 * 底层存储由原来的JVM内存切换为Redis。</p>
 */
@Component
@SuppressWarnings({ "unchecked", "rawtypes" })
public class RedisCache
{
    private final RedisTemplate<String, Object> redisTemplate;

    public RedisCache(RedisTemplate<String, Object> redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    public <T> void setCacheObject(final String key, final T value)
    {
        redisTemplate.opsForValue().set(key, value);
    }

    public <T> void setCacheObject(final String key, final T value, final Integer timeout, final TimeUnit timeUnit)
    {
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    public boolean expire(final String key, final long timeout)
    {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    public boolean expire(final String key, final long timeout, final TimeUnit unit)
    {
        Boolean result = redisTemplate.expire(key, timeout, unit);
        return Boolean.TRUE.equals(result);
    }

    public long getExpire(final String key)
    {
        Long result = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return result == null ? -2L : result;
    }

    public Boolean hasKey(String key)
    {
        return redisTemplate.hasKey(key);
    }

    public <T> T getCacheObject(final String key)
    {
        return (T) redisTemplate.opsForValue().get(key);
    }

    public boolean deleteObject(final String key)
    {
        Boolean result = redisTemplate.delete(key);
        return Boolean.TRUE.equals(result);
    }

    public boolean deleteObject(final Collection collection)
    {
        if (collection == null || collection.isEmpty())
        {
            return false;
        }
        Long result = redisTemplate.delete((Collection<String>) collection);
        return result != null && result > 0;
    }

    public <T> long setCacheList(final String key, final List<T> dataList)
    {
        List<T> value = dataList == null ? new ArrayList<>() : new ArrayList<>(dataList);
        setCacheObject(key, value);
        return value.size();
    }

    public <T> List<T> getCacheList(final String key)
    {
        List<T> list = getCacheObject(key);
        return list == null ? Collections.emptyList() : list;
    }

    public <T> Set<T> setCacheSet(final String key, final Set<T> dataSet)
    {
        Set<T> value = dataSet == null ? Collections.emptySet() : Set.copyOf(dataSet);
        setCacheObject(key, value);
        return dataSet;
    }

    public <T> Set<T> getCacheSet(final String key)
    {
        Set<T> set = getCacheObject(key);
        return set == null ? Collections.emptySet() : set;
    }

    public <T> void setCacheMap(final String key, final Map<String, T> dataMap)
    {
        if (dataMap != null)
        {
            setCacheObject(key, dataMap);
        }
    }

    public <T> Map<String, T> getCacheMap(final String key)
    {
        Map<String, T> map = getCacheObject(key);
        return map == null ? Collections.emptyMap() : map;
    }

    public <T> void setCacheMapValue(final String key, final String hKey, final T value)
    {
        Map<String, T> map = new HashMap<>(getCacheMap(key));
        map.put(hKey, value);
        setCacheObject(key, map);
    }

    public <T> T getCacheMapValue(final String key, final String hKey)
    {
        Map<String, T> map = getCacheMap(key);
        return map.get(hKey);
    }

    public <T> List<T> getMultiCacheMapValue(final String key, final Collection<Object> hKeys)
    {
        Map<String, T> map = getCacheMap(key);
        if (hKeys == null)
        {
            return Collections.emptyList();
        }
        return hKeys.stream().map(hKey -> map.get(String.valueOf(hKey))).collect(Collectors.toList());
    }

    public boolean deleteCacheMapValue(final String key, final String hKey)
    {
        Map<String, Object> map = new HashMap<>(getCacheMap(key));
        if (map.isEmpty())
        {
            return false;
        }
        Object removed = map.remove(hKey);
        setCacheObject(key, map);
        return removed != null;
    }

    public Collection<String> keys(final String pattern)
    {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys == null ? Collections.emptyList() : keys;
    }
}
