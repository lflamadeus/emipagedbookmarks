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

    public FavoriteGroup(UUID id, int startIndex, int endIndex) {
        this.id = id;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    public UUID id() {
        return id;
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
        return json;
    }

    public static FavoriteGroup fromJson(JsonObject json) {
        UUID id = parseUuid(json);
        FavoriteGroup group = new FavoriteGroup(id == null ? UUID.randomUUID() : id,
                json.has("start") ? json.get("start").getAsInt() : 0,
                json.has("end") ? json.get("end").getAsInt() : 0);
        group.folded(json.has("folded") && json.get("folded").getAsBoolean());
        group.lineBreak(json.has("lineBreak") && json.get("lineBreak").getAsBoolean());
        return group;
    }

    /** 读 {@code id}；缺字段或格式不合法都返回 null，由调用方决定怎么兜底。 */
    private static UUID parseUuid(JsonObject json) {
        if (!json.has("id")) {
            return null;
        }
        try {
            return UUID.fromString(json.get("id").getAsString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
