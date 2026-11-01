package com.bytedance.douyinclouddemo.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按主播（房间）暂存已转换成游戏格式的互动事件，供网页游戏通过 /poll 拉取。
 * 数据只在内存里，实例重启后会清空；平台本身也不保证评论、点赞可靠送达。
 */
@Component
public class LiveEventStore {

    public static final String DEBUG_KEY = "debug";
    private static final int MAX_EVENTS = 2000;
    private static final int MAX_SEEN = 20000;
    private static final long ROOM_TTL_MS = 6L * 3600 * 1000;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Room debugRoom = new Room();
    private volatile String lastOpened;

    private static class Room {
        private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
        private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_SEEN;
            }
        };
        private long seq = 0;
        private long touched = System.currentTimeMillis();

        synchronized boolean add(String dedupeKey, JSONObject event) {
            touched = System.currentTimeMillis();
            if (dedupeKey != null) {
                if (seen.containsKey(dedupeKey)) {
                    return false;
                }
                seen.put(dedupeKey, Boolean.TRUE);
            }
            JSONObject e = new JSONObject(event);
            e.put("seq", ++seq);
            events.addLast(e);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
            return true;
        }

        synchronized JSONObject poll(long after, int limit) {
            touched = System.currentTimeMillis();
            // 游标比服务端序号还大，说明实例重启过，从头开始
            long from = after > seq ? 0 : after;
            JSONArray list = new JSONArray();
            for (JSONObject e : events) {
                if (e.getLongValue("seq") > from) {
                    list.add(e);
                    if (list.size() >= limit) {
                        break;
                    }
                }
            }
            long last = list.isEmpty() ? from : ((JSONObject) list.get(list.size() - 1)).getLongValue("seq");
            JSONObject res = new JSONObject();
            res.put("seq", last);
            res.put("events", list);
            return res;
        }
    }

    public boolean add(String key, String dedupeKey, JSONObject event) {
        boolean added = false;
        if (key != null && !key.isEmpty()) {
            added = rooms.computeIfAbsent(key, k -> new Room()).add(dedupeKey, event);
        }
        debugRoom.add(dedupeKey == null ? null : "d:" + dedupeKey, event);
        return added;
    }

    public void open(String key) {
        if (key != null && !key.isEmpty()) {
            rooms.computeIfAbsent(key, k -> new Room());
            lastOpened = key;
        }
    }

    public boolean has(String key) {
        return key != null && rooms.containsKey(key);
    }

    /** 最近一次开启的房间，用于把开放平台自测工具的模拟房间数据转给它。 */
    public String lastOpened() {
        String k = lastOpened;
        return k != null && rooms.containsKey(k) ? k : null;
    }

    public void close(String key) {
        if (key != null) {
            rooms.remove(key);
        }
    }

    public JSONObject poll(String key, long after, int limit, boolean debugEnabled) {
        int lim = Math.max(1, Math.min(limit, 500));
        if (DEBUG_KEY.equals(key)) {
            return debugEnabled ? debugRoom.poll(after, lim) : null;
        }
        Room r = rooms.get(key);
        return r == null ? null : r.poll(after, lim);
    }

    @Scheduled(fixedDelay = 600000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Room>> it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().touched > ROOM_TTL_MS) {
                it.remove();
            }
        }
    }
}
