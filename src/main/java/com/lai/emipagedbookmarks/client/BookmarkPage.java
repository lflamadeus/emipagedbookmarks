package com.lai.emipagedbookmarks.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lai.emipagedbookmarks.client.group.FavoriteGroup;

public final class BookmarkPage {
    private final UUID id;
    private final List<String> bookmarkKeys = new ArrayList<>();
    private final List<FavoriteGroup> groups = new ArrayList<>();
    private volatile String name;

    public BookmarkPage(String name) {
        this(UUID.randomUUID(), name);
    }

    public BookmarkPage(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    /**
     * 该分页内的分组，索引以 {@link #bookmarkKeys()} 为准。
     */
    public List<FavoriteGroup> groups() {
        return groups;
    }

    public String name() {
        return name;
    }

    public void rename(String name) {
        this.name = name.isBlank() ? "?" : name.trim();
    }

    /**
     * 该分页收藏的条目键值，顺序即显示顺序。同一个键值可以同时存在于多个分页中，
     * 分页之间互不影响。
     */
    public List<String> bookmarkKeys() {
        return bookmarkKeys;
    }

    public boolean hasKey(String key) {
        return key != null && bookmarkKeys.contains(key);
    }

    public boolean addKey(String key) {
        if (key == null || bookmarkKeys.contains(key)) {
            return false;
        }
        bookmarkKeys.add(key);
        return true;
    }

    /**
     * @return 被移除的位置，不存在则返回 -1
     */
    public int removeKey(String key) {
        int index = key == null ? -1 : bookmarkKeys.indexOf(key);
        if (index >= 0) {
            bookmarkKeys.remove(index);
        }
        return index;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("name", name);
        JsonArray keys = new JsonArray();
        bookmarkKeys.forEach(keys::add);
        json.add("bookmarks", keys);
        JsonArray savedGroups = new JsonArray();
        for (FavoriteGroup group : groups) {
            savedGroups.add(group.toJson());
        }
        json.add("groups", savedGroups);
        return json;
    }

    public static BookmarkPage fromJson(JsonObject json) {
        UUID id = parseUuid(json);
        BookmarkPage page = new BookmarkPage(id == null ? UUID.randomUUID() : id,
                json.has("name") ? json.get("name").getAsString() : "?");
        JsonArray keys = json.getAsJsonArray("bookmarks");
        if (keys != null) {
            keys.forEach(key -> page.addKey(key.getAsString()));
        }
        JsonArray savedGroups = json.getAsJsonArray("groups");
        if (savedGroups != null) {
            savedGroups.forEach(group -> {
                if (group.isJsonObject()) {
                    page.groups().add(FavoriteGroup.fromJson(group.getAsJsonObject()));
                }
            });
        }
        return page;
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
