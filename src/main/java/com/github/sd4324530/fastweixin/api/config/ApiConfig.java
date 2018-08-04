package com.github.sd4324530.fastweixin.api.config;

import com.alibaba.fastjson.JSONObject;
import com.github.sd4324530.fastweixin.api.response.GetJsApiTicketResponse;
import com.github.sd4324530.fastweixin.api.response.GetTokenResponse;
import com.github.sd4324530.fastweixin.exception.WeixinException;
import com.github.sd4324530.fastweixin.handle.ApiConfigChangeHandle;
import com.github.sd4324530.fastweixin.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.io.Serializable;
import java.util.Map;
import java.util.Observable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API配置类，项目中请保证其为单例
 * 实现观察者模式，用于监控token变化
 *
 * @author peiyu
 * @since 1.2
 */
@Slf4j
public final class ApiConfig extends Observable implements Serializable {

    private static final Logger        LOG             = LoggerFactory.getLogger(ApiConfig.class);
    /**
     * 微信刷新token的锁
     */
    private static final String WEIXIN_REFRESH_TOKEN_LOCK_PREFIX     = "fastweixin:token:refresh:lock";

    /**
     * 微信token value
     */
    private static final String WEIXIN_TOKEN_VALUE_PREFIX     = "duckchat:fastweixin:token:value";

    /**
     * 微信刷新jsTicket的锁
     */
    private static final String WEIXIN_REFRESH_JS_TICKET_LOCK_PREFIX = "fastweixin:jsTicket:refresh:lock";

    /**
     * 微信jsTicket value
     */
    private static final String WEIXIN_JS_TICKET_VALUE_PREFIX = "fastweixin:jsTicket:value";

    /**
     * 这里定义token正在刷新的标识，想要达到的目标是当有一个请求来获取token，发现token已经过期（我这里的过期逻辑是比官方提供的早100秒），然后开始刷新token
     * 在刷新的过程里，如果又继续来获取token，会先把旧的token返回，直到刷新结束，之后再来的请求，将获取到新的token
     * 利用redis 分布式锁 实现原理：
     * 当请求来的时候，检查token是否已经过期（7100秒）过期则获取分布式锁 获取到则开始刷新token
     */
    private final        AtomicBoolean tokenRefreshing = new AtomicBoolean(false);

    private final String            appid;
    private final String            secret;
    private       String            accessToken;
    private       String            jsApiTicket;
    private       boolean           enableJsApi;
    private       long              jsTokenStartTime;
    private       long              weixinTokenStartTime;
    private       RedisTemplateUtil redisTemplateUtil;

    /**

    /**
     * 实现同时获取access_token，启用jsApi
     *
     * @param appid       公众号appid
     * @param secret      公众号secret
     * @param enableJsApi 是否启动js api
     */
    public ApiConfig(String appid, String secret, boolean enableJsApi,RedisTemplateUtil redisTemplateUtil) {
        this.appid = appid;
        this.secret = secret;
        this.enableJsApi = enableJsApi;
        this.redisTemplateUtil = redisTemplateUtil;
        long now = System.currentTimeMillis();
        initToken(now);
        if (enableJsApi) initJSToken(now);
    }

    public String getAppid() {
        return appid;
    }

    public String getSecret() {
        return secret;
    }

    public String getAccessToken() {

        Optional<String> token = this.getTokenFromRedis();
        if (token.isPresent()) {
            return token.get();
        }

        //timeout设置为0秒，即只获取一次锁（可以获取则无锁，未获取到则说明有服务正在刷新）
        if (redisTemplateUtil.lock(WEIXIN_REFRESH_TOKEN_LOCK_PREFIX, 0L,3L)) {
            initToken(7100L);
        }

        long currentTimeMills = System.currentTimeMillis();
        do {
            token = getTokenFromRedis();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } while ((System.currentTimeMillis() - currentTimeMills) < 4 * 1000 && !token.isPresent());

        if (token.isPresent()) {
            return token.get();
        }

        if (redisTemplateUtil.lock(WEIXIN_REFRESH_TOKEN_LOCK_PREFIX, 0L, 3L)) {
            initToken(7100L);
        }

        token = getTokenFromRedis();
        if (token.isPresent()) {
            return token.get();
        }

        throw new RuntimeException("do not get token from redis");

    }

    private Optional<String> getTokenFromRedis() {
        String token = this.redisTemplateUtil.get(WEIXIN_TOKEN_VALUE_PREFIX);
        return StringUtils.isEmpty(token) ? Optional.empty() : Optional.of(token);
    }

    public String getJsApiTicket() {
        if (!enableJsApi) {
            return null;
        }
        Optional<String> jsApiTicket = getJsApiTicketFromRedis();
        if (jsApiTicket.isPresent()) {
            return jsApiTicket.get();
        }

        if (redisTemplateUtil.lock(WEIXIN_REFRESH_JS_TICKET_LOCK_PREFIX, 0, 3)) {
            this.initJSToken(7100L);
        }
        try {

            long currentTimeMillis = System.currentTimeMillis();
            do {
                jsApiTicket = getJsApiTicketFromRedis();
                Thread.sleep(300);

            } while ((System.currentTimeMillis() - currentTimeMillis) < 4 * 1000 && !jsApiTicket.isPresent());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (jsApiTicket.isPresent()) {
            return jsApiTicket.get();
        }
        //循环未获取到则再次尝试获取锁拿一次
        if (redisTemplateUtil.lock(WEIXIN_REFRESH_JS_TICKET_LOCK_PREFIX, 0)) {
            this.initJSToken(7100L);
        }
        jsApiTicket = getJsApiTicketFromRedis();
        if (jsApiTicket.isPresent()) {
            return jsApiTicket.get();
        }
        throw new RuntimeException("微信公众号token获取出错，错误信息:从redis拿不到jsApiTicket");
    }

    public boolean isEnableJsApi() {
        return enableJsApi;
    }

    public void setEnableJsApi(boolean enableJsApi) {
        this.enableJsApi = enableJsApi;
        if (!enableJsApi)
            this.jsApiTicket = null;
    }

    /**
     * 添加配置变化监听器
     *
     * @param handle 监听器
     */
    public void addHandle(final ApiConfigChangeHandle handle) {
        super.addObserver(handle);
    }

    /**
     * 移除配置变化监听器
     *
     * @param handle 监听器
     */
    public void removeHandle(final ApiConfigChangeHandle handle) {
        super.deleteObserver(handle);
    }

    /**
     * 移除所有配置变化监听器
     */
    public void removeAllHandle() {
        super.deleteObservers();
    }

    /**
     * 初始化微信配置，即第一次获取access_token
     *
     * @param refreshTime 刷新时间
     */
    private void initToken(final long refreshTime) {
        final long oldTime = this.weixinTokenStartTime;

        this.weixinTokenStartTime = refreshTime;

        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + this.appid + "&secret=" + this.secret;

        RestTemplate template = new RestTemplate();

        JSONObject response = template.getForObject(url, JSONObject.class);

        GetTokenResponse tokenResponse = JSONUtil2.toBean(response, GetTokenResponse.class);

        if (StringUtils.isEmpty(tokenResponse.getAccessToken())) {

            this.weixinTokenStartTime = oldTime;

            throw new RuntimeException("获取微信access_token错误：" + tokenResponse.getErrcode() + "," + tokenResponse.getErrmsg());
        }

        redisTemplateUtil.set(WEIXIN_TOKEN_VALUE_PREFIX, tokenResponse.getAccessToken(), refreshTime);

        log.info("获取access_token:" + tokenResponse);

    }

    /**
     * 初始化微信JS-SDK，获取JS-SDK token
     *
     * @param refreshTime 刷新时间
     */
    private void initJSToken(long refreshTime) {
        LOG.debug("初始化 jsapi_ticket........");
        String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=" + this.getAccessToken() + "&type=jsapi";
        NetWorkCenter.get(url, (Map) null, new NetWorkCenter.ResponseCallback() {
            @Override
            public void onResponse(int resultCode, String resultJson) {
                if (200 == resultCode) {
                    GetJsApiTicketResponse response = (GetJsApiTicketResponse) JSONUtil.toBean(resultJson, GetJsApiTicketResponse.class);
                    LOG.debug("获取jsapi_ticket:{}", response.getTicket());
                    if (StrUtil.isBlank(response.getTicket())) {
                        throw new WeixinException("微信公众号jsToken获取出错，错误信息:" + response.getErrcode() + "," + response.getErrmsg());
                    }

                    redisTemplateUtil.set(WEIXIN_JS_TICKET_VALUE_PREFIX, response.getTicket(), refreshTime);
                    log.info("获取access_token:" + response);

                    setChanged();
                    notifyObservers(new ConfigChangeNotice(ApiConfig.this.appid, ChangeType.JS_TOKEN, response.getTicket()));
                }
            }
        });
    }


    private Optional<String> getJsApiTicketFromRedis() {
        String jsApiTicket = redisTemplateUtil.get(WEIXIN_JS_TICKET_VALUE_PREFIX);

        if (!StringUtils.isBlank(jsApiTicket)) {
            return Optional.of(jsApiTicket);
        }
        return Optional.empty();
    }
}
