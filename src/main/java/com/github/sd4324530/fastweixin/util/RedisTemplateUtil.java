package com.github.sd4324530.fastweixin.util;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by wanglei on 2018/7/23 下午6:07
 **/
@Slf4j
public class RedisTemplateUtil implements CacheUtil{

    private StringRedisTemplate stringRedisTemplate;

    public StringRedisTemplate getStringRedisTemplate() {
        return stringRedisTemplate;
    }

    public void setStringRedisTemplate(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 此方法,当存在大量数据时,慎用
     *
     * @param key
     * @return
     */
    @Override
    public Map<String, String> hGetAll(String key) {
        return (Map) stringRedisTemplate.opsForHash().entries(key);
    }

    /**
     * 此方法不建议在生产上使用
     *
     * @param key
     * @return
     */
    @Override
    public Set<Object> hKeys(String key) {
        return stringRedisTemplate.opsForHash().keys(key);
    }

    @Override
    public List<Object> hValues(String key) {
        return stringRedisTemplate.opsForHash().values(key);
    }


    /**
     * 获取锁(锁默认5秒过期)
     *
     * @param key     key
     * @param timeout 获取锁超时时间(单位秒)
     * @return
     */
    @Override
    public boolean lock(final String key, final long timeout) {
        long expiredSeconds = CacheUtil.DEFAULT_LOCK_EXPIRE_TIME; // 默认5秒
        return lock(key, timeout, expiredSeconds);
    }

    /**
     * 获取锁
     *
     * @param key            key
     * @param timeout        获取锁超时时间(单位秒)
     * @param expiredSeconds 锁过期时间(单位秒)
     * @return
     */
    @Override
    public boolean lock(final String key, final long timeout, final long expiredSeconds) {
        // 判断存不存在锁
        String expiredTimeMillis = get(key);
        // 临时判断锁是否过期
        if (expiredTimeMillis != null) {
            if (System.currentTimeMillis() > Long.valueOf(expiredTimeMillis)) {
                unlock(key);
                if (setnx(key, expiredSeconds)) {
                    return true;
                }
            }
        }

        try {
            long currentTimeMillis = System.currentTimeMillis();
            do {
                if (setnx(key, expiredSeconds)) {
                    return true;
                }
                Thread.sleep(300);
            } while ((System.currentTimeMillis() - currentTimeMillis) < timeout * 1000);
        } catch (Exception e) {
            log.error("获取锁出现异常,key:" + key, e);
        }
        return false;
    }

    private boolean setnx(String key, long expiredSeconds) {
        long currentTimeMillis = System.currentTimeMillis();
        long expiredTimeMillis = currentTimeMillis + expiredSeconds;
        String value = String.valueOf(expiredTimeMillis);
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, value);
        if (result.booleanValue() == true) {
            // 设置过期时间
            set(key, value, expiredSeconds);
            return true;
        }
        return false;
    }

    /**
     * 解锁
     *
     * @param key
     */
    @Override
    public void unlock(final String key) {
        delete(key);
    }

    @Override
    public Set<String> keys(String pattern) {
        return stringRedisTemplate.keys(pattern);
    }

    @Override
    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    @Override
    public void set(String key, String value, long expiredSeconds) {
        stringRedisTemplate.opsForValue().set(key, value,
                expiredSeconds <= 0 ? defaultExpiredSeconds : expiredSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void set(String key, String value, long expired, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, value,
                expired <= 0 ? defaultExpiredSeconds : expired, timeUnit);
    }

    @Override
    public void expire(String key, long expiredSeconds) {
        stringRedisTemplate.expire(key, expiredSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void expire(String key, long expired, TimeUnit timeUnit) {
        stringRedisTemplate.expire(key, expired, timeUnit);
    }

    @Override
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public void deletePattern(String key) {
        Set<String> keys = keys(key);
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Override
    public void deleteArr(String... keys) {
        stringRedisTemplate.delete(Arrays.asList(keys));
    }

    @Override
    public Long incr(String key) {
        return stringRedisTemplate.opsForValue().increment(key, NumberUtils.LONG_ONE);
    }

    @Override
    public Long incr(String key, long value) {
        return stringRedisTemplate.opsForValue().increment(key, value);
    }

    @Override
    public List<String> lRange(String key, long start, long end) {
        return stringRedisTemplate.opsForList().range(key, start, end);
    }

    @Override
    public List<String> hMget(final String key, final String... fields) {
        if (fields == null || fields.length == 0) {
            return Lists.newArrayList();
        }

        return _hMget(key, fields);
    }

    private List<String> _hMget(final String key, final String... fields) {
        return stringRedisTemplate.execute((RedisCallback<List<String>>) redisConnection -> {
            RedisSerializer<String> serializer = stringRedisTemplate.getStringSerializer();
            byte[] btKey = serializer.serialize(key);
            List<String> resultList = new ArrayList<>();
            if (redisConnection.exists(btKey)) {
                List<byte[]> list = new ArrayList<>(fields.length);
                for (String field : fields) {
                    list.add(serializer.serialize(field));
                }
                List<byte[]> btList = redisConnection.hMGet(btKey, list.toArray(new byte[list.size()][]));
                resultList.addAll(btList.stream().map(serializer::deserialize).collect(Collectors.toList()));
            }
            return resultList;
        });
    }

    @Override
    public List<Object> hMget(String key, List<String> fields) {
        if (CollectionUtils.isEmpty(fields)) {
            return Lists.newArrayList();
        }

        List<Object> objFields = Lists.transform(fields, s -> s);

        return stringRedisTemplate.opsForHash().multiGet(key, objFields);
    }

    @Override
    public Map<String, String> hMgetMap(final String key, final String... fields) {
        if (fields.length == 0) {
            return Maps.newHashMap();
        }

        return _hMgetMap(key, fields);
    }

    private Map<String, String> _hMgetMap(final String key, final String... fields) {
        return stringRedisTemplate.execute((RedisCallback<Map<String, String>>) redisConnection -> {
            RedisSerializer<String> serializer = stringRedisTemplate.getStringSerializer();
            byte[] btKey = serializer.serialize(key);
            Map<String, String> resultMap = null;
            if (redisConnection.exists(btKey)) {
                List<byte[]> list = new ArrayList<>(fields.length);
                for (String field : fields) {
                    list.add(serializer.serialize(field));
                }
                List<byte[]> btList = redisConnection.hMGet(btKey, list.toArray(new byte[list.size()][]));
                resultMap = new HashMap<>();
                for (int i = 0, len = btList.size(); i < len; i++) {
                    resultMap.put(fields[i], serializer.deserialize(btList.get(i)));
                }
            }
            return resultMap;
        });
    }

    @Override
    public void sSet(String key, String value, long expiredSeconds) {
        stringRedisTemplate.opsForSet().add(key, value);
        stringRedisTemplate.expire(key, expiredSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String hGet(String key, String hashKey) {
        return (String) stringRedisTemplate.opsForHash().get(key, hashKey);
    }

    @Override
    public void lPush(String key, String value) {
        if (value == null) {
            return;
        }
        stringRedisTemplate.opsForList().leftPush(key, value);
    }

    @Override
    public String lPop(String key) {
        if (StringUtils.isEmpty(key)) {
            return StringUtils.EMPTY;
        }
        return stringRedisTemplate.opsForList().leftPop(key);
    }


    @Override
    public void rPush(String key, String value) {
        if (value == null) {
            return;
        }
        stringRedisTemplate.opsForList().rightPush(key, value);
    }

    @Override
    public void rPushAll(String key, List<String> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        stringRedisTemplate.opsForList().rightPushAll(key, list);
    }

    @Override
    public String rPop(String key) {
        if (StringUtils.isEmpty(key)) {
            return StringUtils.EMPTY;
        }
        return stringRedisTemplate.opsForList().rightPop(key);
    }

    @Override
    public void sRem(String key, String... member) {
        Object[] obj = member;
        stringRedisTemplate.opsForSet().remove(key, obj);
    }

    @Override
    public void sSet(String key, String value) {
        if (value == null) {
            return;
        }
        stringRedisTemplate.opsForSet().add(key, value);
    }

    @Override
    public byte hAdd(String key, String hashKey, String value) {
        byte t = stringRedisTemplate.opsForHash().hasKey(key, hashKey) ? (byte) 0 : (byte) 1;
        stringRedisTemplate.opsForHash().put(key, hashKey, value);
        return t;
    }

    @Override
    public void hAdd(String key, String hashKey, String value, long overtime) {
        stringRedisTemplate.opsForHash().put(key, hashKey, value);
        stringRedisTemplate.expire(key, overtime, TimeUnit.SECONDS);
    }

    @Override
    public void hAddAll(String key, Map<String, String> map) {
        stringRedisTemplate.opsForHash().putAll(key, map);
    }

    @Override
    public void hAddAll(String key, Map<String, String> map, long overtime) {
        stringRedisTemplate.opsForHash().putAll(key, map);
        stringRedisTemplate.expire(key, overtime, TimeUnit.SECONDS);
    }

    @Override
    public void deleteMapKey(String key, String mapKey) {
        stringRedisTemplate.boundHashOps(key).delete(mapKey);
    }

    @Override
    public Object hGetByMapKey(String key, String mapKey) {
        return stringRedisTemplate.boundHashOps(key).get(mapKey);
    }

    @Override
    public Set<String> sMember(String key) {
        return stringRedisTemplate.opsForSet().members(key);
    }


    @Override
    public void setObj(final String key, Object value, long overtime) {
        final byte[] valueBytes = SerializeUtil.serialize(value);
        stringRedisTemplate.execute(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection)
                    throws DataAccessException {
                connection.set(stringRedisTemplate.getStringSerializer().serialize(key), valueBytes);
                stringRedisTemplate.expire(key, overtime, TimeUnit.SECONDS);
                return null;
            }
        });
    }

    @Override
    public <T> T getObj(String key, Class<T> objType) {
        return stringRedisTemplate.execute(new RedisCallback<T>() {
            @Override
            public T doInRedis(RedisConnection connection)
                    throws DataAccessException {
                byte[] keyBytes = stringRedisTemplate.getStringSerializer().serialize(key);
                if (connection.exists(keyBytes)) {
                    byte[] valueBytes = connection.get(keyBytes);
                    @SuppressWarnings("unchecked")
                    T value = (T) SerializeUtil.unserialize(valueBytes);
                    return value;
                }
                return null;
            }
        });
    }


    @Override
    public void setListObj(final String key, List value, long overtime) {
        final byte[] valueBytes = SerializeUtil.serialize(value);
        stringRedisTemplate.execute(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection)
                    throws DataAccessException {
                connection.set(stringRedisTemplate.getStringSerializer().serialize(key), valueBytes);
                stringRedisTemplate.expire(key, overtime, TimeUnit.SECONDS);
                return null;
            }
        });
    }

    @Override
    public <T> List<T> getListObj(String key) {
        return stringRedisTemplate.execute(new RedisCallback<List<T>>() {
            @Override
            public List<T> doInRedis(RedisConnection connection)
                    throws DataAccessException {
                byte[] keyBytes = stringRedisTemplate.getStringSerializer().serialize(key);
                if (connection.exists(keyBytes)) {
                    byte[] valueBytes = connection.get(keyBytes);
                    @SuppressWarnings("unchecked")
                    List<T> value = SerializeUtil.unserializeForList(valueBytes);
                    return value;
                }
                return null;
            }
        });
    }
}
