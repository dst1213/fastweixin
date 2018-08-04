package com.github.sd4324530.fastweixin.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.util.Assert;

/**
 * Created by wanglei on 2018/8/4 下午10:44
 **/
public class JSONUtil2 {

    public static <T> T toBean(JSONObject text, Class<T> clazz) {
        Assert.notNull(text, "text is null");
        Assert.notNull(clazz, "class is null");
        return JSON.parseObject(JSONObject.toJSONString(text), clazz);
    }
}
