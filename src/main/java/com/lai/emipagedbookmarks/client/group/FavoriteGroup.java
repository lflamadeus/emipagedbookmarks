package com.lai.emipagedbookmarks.client.group;

import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * 收藏栏中的一个分组，用首尾索引标记它在当前分页中的位置。
 */
public final class FavoriteGroup {
    private final UUID id;
    private int startIndex;
    private int endIndex;
    private boolean folded;
    private boolean lineBreak;
    /**
     * 边框散点（抖动层）的随机种子。
     *
     * <p>散点掩码不是每帧现算的：分组创建时随机出一个种子存进配置文件，绘制期只拿它去查一张
     * 全局随机表。同一个分组永远得到同一片散点，改配置、重开游戏都不变；想换一片形状就改这个值。</p>
     */
    private int seed = new java.util.Random().nextInt();

    public FavoriteGroup(UUID id, int startIndex, int endIndex) {
        this.id = id;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    public UUID id() {
        return id;
    }

    public int seed() {
        return seed;
    }

    public void seed(int seed) {
        this.seed = seed;
    }

    public int startIndex() {
        return startIndex;
    }

    public void startIndex(int startIndex) {
        this.startIndex = startIndex;
    }

    public int endIndex() {
        return endIndex;
    }

    public void endIndex(int endIndex) {
        this.endIndex = endIndex;
    }

    public boolean folded() {
        return folded;
    }

    public void folded(boolean folded) {
        this.folded = folded;
    }

    public boolean lineBreak() {
        return lineBreak;
    }

    public void lineBreak(boolean lineBreak) {
        this.lineBreak = lineBreak;
    }

    public int size() {
        return endIndex - startIndex + 1;
    }

    public boolean contains(int index) {
        return index >= startIndex && index <= endIndex;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("start", startIndex);
        json.addProperty("end", endIndex);
        json.addProperty("folded", folded);
        json.addProperty("lineBreak", lineBreak);
        json.addProperty("seed", seed);
        return json;
    }

    public static FavoriteGroup fromJson(JsonObject json) {
        UUID id = parseUuid(json);
        FavoriteGroup group = new FavoriteGroup(id == null ? UUID.randomUUID() : id,
                json.has("start") ? json.get("start").getAsInt() : 0,
                json.has("end") ? json.get("end").getAsInt() : 0);
        group.folded(json.has("folded") && json.get("folded").getAsBoolean());
        group.lineBreak(json.has("lineBreak") && json.get("lineBreak").getAsBoolean());
        // 旧文件没有 seed 字段：构造函数已经随机了一个，直接用即可。
        if (json.has("seed")) {
            group.seed(json.get("seed").getAsInt());
        }
        return group;
    }
}
