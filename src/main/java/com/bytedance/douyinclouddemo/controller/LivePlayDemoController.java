package com.bytedance.douyinclouddemo.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bytedance.douyinclouddemo.service.LiveEventConverter;
import com.bytedance.douyinclouddemo.service.LiveEventStore;
import org.springframework.beans.factory.annotation.Autowired;
import com.bytedance.douyinclouddemo.model.JsonResponse;
import com.bytedance.douyinclouddemo.model.LiveDataModel;
import com.bytedance.douyinclouddemo.model.LivePlayAPIResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;

/**
 * 抖音云x弹幕玩法的服务端demo展示
 */
@RestController
@CrossOrigin
@Slf4j
public class LivePlayDemoController {

    /** 本地调试开关：允许不带平台请求头调用 /start_game，并开放 key=debug 的全量事件流。线上不要设置。 */
    private static final boolean DEBUG = "1".equals(System.getenv("LIVEPLAY_DEBUG"));
    /** 设为 0 可关闭向抖音云 WebSocket 网关的转发（本地测试时用）。 */
    private static final boolean WS_PUSH = !"0".equals(System.getenv("LIVEPLAY_WS_PUSH"));
    /** 本地测试时指向模拟的开放平台地址，线上保持默认。 */
    private static final String OPENAPI_BASE = System.getenv("LIVEPLAY_OPENAPI_BASE") != null
            ? System.getenv("LIVEPLAY_OPENAPI_BASE")
            : "http://webcast-bytedance-com.openapi.dyc.ivolces.com";

    /** 开放平台"自测工具"的模拟房间号（1 开头共 19 位）。仅测试时设置，线上不要设置。 */
    private static final String MOCK_ROOM = System.getenv("LIVEPLAY_MOCK_ROOM");

    @Autowired
    private LiveEventStore store;

    /**
     * 开始玩法对局，玩法开始前调用
     */
    @PostMapping(path = "/start_game")
    public JsonResponse callContainerExample(HttpServletRequest httpRequest,
                                             @RequestParam(value = "key", required = false) String debugKey) {
        // 开发者可以直接通过请求头获取直播间信息,无需自行通过token置换

        // 应用id
        String appID = httpRequest.getHeader("X-TT-AppID");
        // 直播间id
        String roomID = httpRequest.getHeader("X-Room-ID");
        // 主播id
        String anchorOpenID = httpRequest.getHeader("X-Anchor-OpenID");
        // 主播头像url
        String avatarUrl = httpRequest.getHeader("X-Avatar-Url");
        // 主播昵称
        String nickName = httpRequest.getHeader("X-Nick-Name");

        log.info("appID: {}, roomID: {}, anchorOpenID: {}, avatarUrl: {}, nickName: {}", appID,
                roomID, anchorOpenID, avatarUrl, nickName);

        JsonResponse response = new JsonResponse();
        boolean hasRoom = notEmpty(appID) && notEmpty(roomID) && notEmpty(anchorOpenID);
        if (!hasRoom && !DEBUG) {
            response.failure("缺少直播间信息");
            return response;
        }
        // 游戏拉取消息用的标识：线上是主播 openid，本地调试可用 key 参数
        String key = hasRoom ? anchorOpenID : (notEmpty(debugKey) ? debugKey : LiveEventStore.DEBUG_KEY);
        store.open(key);

        if (hasRoom) {
            // 调用弹幕玩法服务端API，开启直播间推送任务，开启后，开发者服务器会通过/live_data_callback接口 收到直播间玩法指令
            List<String> msgTypeList = new ArrayList<>();
            msgTypeList.add("live_like");
            msgTypeList.add("live_comment");
            msgTypeList.add("live_gift");
            msgTypeList.add("live_fansclub");
            // 关注互动能力：平台要求接入，游戏里对应 30 秒攻击加成
            msgTypeList.add("live_follow");

            for (String msgType : msgTypeList) {
                boolean result = startLiveDataTask(appID, roomID, msgType);
                if (result) {
                    log.info("{} 推送开启成功", msgType);
                } else {
                    log.error("{} 推送开启失败", msgType);
                }
            }
        }

        JSONObject data = new JSONObject();
        data.put("key", key);
        response.success(data.toJSONString());
        return response;
    }

    /**
     * exe 客户端启动：直播伴侣以启动参数传来 token，客户端交给这里换取直播间信息并开启推送。
     * 走抖音云内网专线，无需自己维护 access_token。请求体 {"token":"...","appid":"ttxxxx"}，
     * appid 优先取环境变量 LIVEPLAY_APPID。
     */
    @PostMapping(path = "/start_by_token", produces = "application/json;charset=UTF-8")
    public String startByToken(@RequestBody String reqBody) {
        JSONObject out = new JSONObject();
        JSONObject req;
        try {
            req = JSON.parseObject(reqBody);
        } catch (Exception e) {
            req = null;
        }
        String token = req == null ? null : req.getString("token");
        String appID = notEmpty(System.getenv("LIVEPLAY_APPID")) ? System.getenv("LIVEPLAY_APPID")
                : (req == null ? null : req.getString("appid"));
        if (!notEmpty(token) || !notEmpty(appID)) {
            out.put("err_no", 1);
            out.put("err_msg", "缺少 token 或 appid");
            return out.toJSONString();
        }

        String raw;
        try {
            Request request = new Request.Builder()
                    .url(OPENAPI_BASE + "/api/webcastmate/info")
                    .addHeader("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(MediaType.get("application/json; charset=utf-8"),
                            new JSONObject().fluentPut("token", token).toString()))
                    .build();
            Response r = new OkHttpClient().newCall(request).execute();
            raw = r.body() == null ? "" : r.body().string();
            log.info("[换取直播信息] http={} 返回={}", r.code(), raw);
        } catch (IOException e) {
            log.error("[换取直播信息] 异常 {}", e.getMessage(), e);
            out.put("err_no", 2);
            out.put("err_msg", "换取直播信息失败");
            return out.toJSONString();
        }

        JSONObject info = null;
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONObject data = root.getJSONObject("data");
            if (data != null) {
                info = data.getJSONObject("info") != null ? data.getJSONObject("info") : data;
            }
        } catch (Exception ignore) {
            // 返回格式异常，下面统一报错，原文已记入日志
        }
        String roomID = info == null ? null : info.getString("room_id");
        String anchorOpenID = info == null ? null : info.getString("anchor_open_id");
        if (!notEmpty(roomID) || !notEmpty(anchorOpenID)) {
            out.put("err_no", 3);
            out.put("err_msg", "未取到直播间信息，请查看服务日志 [换取直播信息]");
            return out.toJSONString();
        }

        store.open(anchorOpenID);
        for (String msgType : Arrays.asList("live_like", "live_comment", "live_gift", "live_fansclub", "live_follow")) {
            if (startLiveDataTask(appID, roomID, msgType)) {
                log.info("{} 推送开启成功", msgType);
            } else {
                log.error("{} 推送开启失败", msgType);
            }
            if (notEmpty(MOCK_ROOM)) {
                log.info("[模拟房间] {} 开启{}", msgType, startLiveDataTask(appID, MOCK_ROOM, msgType) ? "成功" : "失败");
            }
        }
        out.put("err_no", 0);
        out.put("key", anchorOpenID);
        return out.toJSONString();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * 网页游戏拉取新的互动事件。after 为上次返回的 seq，首次传 0。
     */
    @GetMapping(path = "/poll", produces = "application/json;charset=UTF-8")
    public String poll(@RequestParam("key") String key,
                       @RequestParam(value = "after", defaultValue = "0") long after,
                       @RequestParam(value = "limit", defaultValue = "200") int limit) {
        JSONObject res = store.poll(key, after, limit, DEBUG);
        if (res == null) {
            JSONObject err = new JSONObject();
            err.put("err_no", 1);
            err.put("err_msg", "房间不存在，请先调用 /start_game");
            return err.toJSONString();
        }
        res.put("err_no", 0);
        return res.toJSONString();
    }

    /**
     * startLiveDataTask: 开启推送任务：<a href="https://developer.open-douyin.com/docs/resource/zh-CN/interaction/develop/server/live/danmu#%E5%90%AF%E5%8A%A8%E4%BB%BB%E5%8A%A1">...</a>
     *
     * @param appID   小玩法appID
     * @param roomID  直播间ID
     * @param msgType 评论/点赞/礼物/粉丝团
     */
    private boolean startLiveDataTask(String appID, String roomID, String msgType) {
        // example: 通过java OkHttp库发起http请求,开发者可使用其余http访问形式
        OkHttpClient client = new OkHttpClient();
        String body = new JSONObject()
                .fluentPut("roomid", roomID)
                .fluentPut("appid", appID)
                .fluentPut("msg_type", msgType)
                .toString();
        Request request = new Request.Builder()
                .url(OPENAPI_BASE + "/api/live_data/task/start") // 使用webcast.bytedance.com的抖音云内网专线域名访问小玩法openAPI,无需https协议
                .addHeader("Content-Type", "application/json") // 无需维护access_token
                .post(
                        okhttp3.RequestBody.create(
                                MediaType.get("application/json; charset=utf-8"),
                                body
                        )
                )
                .build();

        try {
            Response httpResponse = client.newCall(request).execute();
            if (httpResponse.code() != 200) {
                log.error("开启推送任务失败,http访问非200");
                return false;
            }
            LivePlayAPIResponse livePlayAPIResponse
                    = JSON.parseObject(httpResponse.body().string(), LivePlayAPIResponse.class);
            if (livePlayAPIResponse.getErrNo() != 0) {
                log.error("开启推送任务失败，错误信息: {}", livePlayAPIResponse.getErrorMsg());
                return false;
            }
        } catch (IOException e) {
            log.error("开启推送任务异常,e: {}", e.getMessage(), e);
            return false;
        }
        return true;
    }

    /**
     * 结束玩法
     */
    @PostMapping(path = "/finish_game")
    public JsonResponse finishGameExample(HttpServletRequest httpRequest) {
        String anchorOpenID = httpRequest.getHeader("X-Anchor-OpenID");
        store.close(anchorOpenID);
        JsonResponse response = new JsonResponse();
        response.success("结束玩法成功");
        return response;
    }


    /**
     * 通过抖音云服务接受直播间数据，内网专线加速+免域名备案
     * 通过内网专线会自动携带X-Anchor-OpenID字段
     * ref: <a href="https://developer.open-douyin.com/docs/resource/zh-CN/developer/tools/cloud/develop-guide/danmu-callback">...</a>
     */
    @PostMapping(path = "/live_data_callback")
    public JsonResponse liveDataCallbackExample(
            @RequestHeader("X-Anchor-OpenID") String anchorOpenID,
            @RequestHeader("x-msg-type") String msgType,
            @RequestBody String body) {
        log.info("[回调] 类型={} 房间={} 原文={}", msgType, anchorOpenID,
                body != null && body.length() > 1500 ? body.substring(0, 1500) + "...(截断)" : body);
        JSONArray arr = JSON.parseArray(body);
        // 自测工具的模拟房间带的主播标识和真实房间不同，测试时转给最近开启的房间
        if (notEmpty(MOCK_ROOM) && !store.has(anchorOpenID) && store.lastOpened() != null) {
            log.info("[模拟房间] 回调主播标识 {} 未开房，转给 {}", anchorOpenID, store.lastOpened());
            anchorOpenID = store.lastOpened();
        }
        // 平台可能重复推送，按 msg_id 去重后再放进房间队列，供 /poll 拉取
        for (LiveEventConverter.Item item : LiveEventConverter.convert(msgType, arr)) {
            String dedupe = item.msgId == null ? null : msgType + ":" + item.msgId;
            store.add(anchorOpenID, dedupe, item.event);
        }
        if (WS_PUSH && arr != null && !arr.isEmpty()) {
            List<LiveDataModel> liveDataModelList = JSON.parseArray(body, LiveDataModel.class);
            pushDataToClientByDouyinCloudWebsocket(anchorOpenID, liveDataModelList.get(0).getMsgID(), msgType, body);
        }
        JsonResponse response = new JsonResponse();
        response.success("success");
        return response;
    }


    //---------------- 抖音云websocket相关demo ---------------------

    /**
     * 抖音云websocket监听的回调函数,客户端建连/上行发消息都会走到该HTTP回调函数中
     * ref: <a href="https://developer.open-douyin.com/docs/resource/zh-CN/developer/tools/cloud/develop-guide/websocket-guide/websocket#%E5%BB%BA%E8%BF%9E%E8%AF%B7%E6%B1%82">...</a>
     */
    @RequestMapping(path = "/websocket_callback", method = {RequestMethod.POST, RequestMethod.GET})
    public JsonResponse websocketCallback(HttpServletRequest request) {
        String eventType = request.getHeader("x-tt-event-type");
        switch (eventType) {
            case "connect":
                // 客户端建连
            case "disconnect": {
                // 客户端断连
            }
            case "uplink": {
                // 客户端上行发消息
            }
            default:
                break;
        }
        JsonResponse response = new JsonResponse();
        response.success("success");
        return response;
    }

    /**
     * 使用抖音云websocket网关,将数据推送到主播端
     * ref: <a href="https://developer.open-douyin.com/docs/resource/zh-CN/developer/tools/cloud/develop-guide/websocket-guide/websocket#%E4%B8%8B%E8%A1%8C%E6%B6%88%E6%81%AF%E6%8E%A8%E9%80%81">...</a>
     */
    private void pushDataToClientByDouyinCloudWebsocket(String anchorOpenId, String msgID, String msgType, String data) {
        // 这里通过HTTP POST请求将数据推送给抖音云网关,进而抖音云网关推送给主播端
        OkHttpClient client = new OkHttpClient();

        Map<String, String> bodyMap = new HashMap<>();
        bodyMap.put("msg_id", msgID);
        bodyMap.put("msg_type", msgType);
        bodyMap.put("data", data);
//        bodyMap.put("extra_data", "");
        String bodyStr = JSON.toJSONString(bodyMap);

        Request request = new Request.Builder()
                .url("http://ws-push.dyc.ivolces.com/ws/live_interaction/push_data")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-TT-WS-OPENIDS", JSON.toJSONString(Arrays.asList(anchorOpenId)))
                .post(
                        okhttp3.RequestBody.create(
                                MediaType.parse("application/json; charset=utf-8"),
                                bodyStr
                        )
                )
                .build();

        try {
            Response httpResponse = client.newCall(request).execute();
            log.info("websocket http call done, response: {}", JSON.toJSONString(httpResponse));
        } catch (IOException e) {
            log.error("websocket http call exception, e: ", e);
        }
    }
}
