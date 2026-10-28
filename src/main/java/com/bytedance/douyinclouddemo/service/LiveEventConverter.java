package com.bytedance.douyinclouddemo.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 抖音推送的原始数据 -> 游戏使用的消息：
 * {"type":"chat","user","content"} / {"type":"gift","user","gift","count"}
 * / {"type":"like","user","count"} / {"type":"follow","user","action"}
 */
public final class LiveEventConverter {

    private static final String[] GIFT_NAMES = {"仙女棒", "能力药丸", "魔法镜", "甜甜圈", "能量电池", "爱的爆炸", "派对话筒", "神秘空投"};
    private static final int[] GIFT_COINS = {1, 10, 19, 52, 99, 199, 299, 520};

    /** 1 抖币对应多少分，礼物推送里的 gift_value 以分为单位。 */
    private static final int CENTS_PER_COIN = readCents();

    private LiveEventConverter() {
    }

    private static int readCents() {
        try {
            String v = System.getenv("LIVEPLAY_CENTS_PER_COIN");
            return v == null || v.isEmpty() ? 10 : Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public static final class Item {
        public final String msgId;
        public final JSONObject event;

        Item(String msgId, JSONObject event) {
            this.msgId = msgId;
            this.event = event;
        }
    }

    public static List<Item> convert(String msgType, JSONArray arr) {
        List<Item> out = new ArrayList<>();
        if (arr == null || msgType == null) {
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o == null) {
                continue;
            }
            JSONObject ev = new JSONObject();
            String user = o.getString("nickname");
            if (user == null || user.isEmpty()) {
                user = "观众";
            }
            ev.put("user", user);
            ev.put("openid", o.getString("sec_openid"));
            switch (msgType) {
                case "live_comment":
                    ev.put("type", "chat");
                    ev.put("content", o.getString("content"));
                    break;
                case "live_like":
                    ev.put("type", "like");
                    ev.put("count", Math.max(1, o.getIntValue("like_num")));
                    break;
                case "live_gift": {
                    int num = Math.max(1, o.getIntValue("gift_num"));
                    ev.put("type", "gift");
                    ev.put("gift", giftByUnitValue(o.getLongValue("gift_value"), num));
                    ev.put("count", num);
                    if (o.getBooleanValue("test")) {
                        ev.put("test", true);
                    }
                    break;
                }
                case "live_follow":
                    ev.put("type", "follow");
                    ev.put("action", followAction(o));
                    break;
                default:
                    continue;
            }
            out.add(new Item(o.getString("msg_id"), ev));
        }
        return out;
    }

    /** 按单个礼物的价格取不超过它的最高档，不足一抖币按最低档。 */
    static String giftByUnitValue(long totalCents, int num) {
        long unitCoins = totalCents / Math.max(1, num) / CENTS_PER_COIN;
        int idx = 0;
        for (int i = 0; i < GIFT_COINS.length; i++) {
            if (unitCoins >= GIFT_COINS[i]) {
                idx = i;
            }
        }
        return GIFT_NAMES[idx];
    }

    /** 关注推送的字段名文档里没有写明，这里兼容几种可能的写法：1 关注，2 取消关注，3 重新关注。 */
    static int followAction(JSONObject o) {
        String[] keys = {"action", "follow_action", "user_follow_action", "follow_type"};
        for (String k : keys) {
            if (o.containsKey(k)) {
                int v = o.getIntValue(k);
                if (v >= 1 && v <= 3) {
                    return v;
                }
            }
        }
        return 1;
    }
}
