package com.github.sd4324530.fastweixin.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Created by wanglei on 2018/7/23 下午6:08
 **/
public interface CacheUtil {

    long defaultExpiredSeconds = 300;

    //锁有效时间，超过此事件自动回收
    long DEFAULT_LOCK_EXPIRE_TIME = 5;

    Map<String, String> hGetAll(String key);

    Set<Object> hKeys(String key);

    List<Object> hValues(String key);

    boolean lock(final String key, final long timeout);

    boolean lock(final String key, final long timeout, final long expiredSeconds);

    void unlock(final String key);

    Set<String> keys(String pattern);

    void set(String key, String value);

    void set(String key, String value, long expired, TimeUnit timeUnit);

    String get(String key);

    //overtime 过期时间,单位为秒.
    void set(String key, String value, long expiredSeconds);

    void expire(String key, long expiredSeconds);

    void expire(String key, long expired, TimeUnit timeUnit);

    void delete(String key);

    void deletePattern(String key);

    void deleteArr(String... keys);

    Set<String> sMember(String key);

    Long incr(String key);

    Long incr(String key, long value);

    List<String> lRange(String key, long start, long end);

    List<String> hMget(String key, String... fields);

    List<Object> hMget(String key, List<String> fields);

    Map<String, String> hMgetMap(String key, String... fields);

    void sSet(String key, String value, long overtime);

    String hGet(String key, String hashKey);

    /**
     * 对应php中的redis hadd方法
     *
     * @param key
     * @param hashKey
     * @param value
     * @return 如果key不存在，添加成功，返回1；如果key已存在，替换成功，返回0
     */
    byte hAdd(String key, String hashKey, String value);

    void lPush(String key, String value);

    String lPop(String key);

    void rPush(String key, String value);

    void rPushAll(String key, List<String> list);

    String rPop(String key);

    void sRem(String key, String... member);

    void sSet(String key, String value);

    void hAdd(String key, String hashKey, String value, long overtime);

    void hAddAll(String key, Map<String, String> map);

    void hAddAll(String key, Map<String, String> map, long overtime);

    void deleteMapKey(String key, String mapKey);

    Object hGetByMapKey(String key, String mapKey);

    void setObj(final String key, Object value, long overtime);

    <T> T getObj(String key, Class<T> objType);

    void setListObj(final String key, List value, long overtime);

    <T> List<T> getListObj(String key);

}
