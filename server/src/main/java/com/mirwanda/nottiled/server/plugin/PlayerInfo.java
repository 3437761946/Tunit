package com.mirwanda.nottiled.server.plugin;

/**
 * 玩家在服务器上的只读快照信息，供插件回调使用。
 */
public final class PlayerInfo {

    /** 客户端注册 ID，形如 "Steve_1234567" */
    public final String id;

    /** 当前所在房间名；未入房时为空字符串 */
    public final String room;

    /** 是否为该房间创建者（房主） */
    public final boolean creator;

    public PlayerInfo(String id, String room, boolean creator) {
        this.id = (id == null) ? "" : id;
        this.room = (room == null) ? "" : room;
        this.creator = creator;
    }

    public String getId() {
        return id;
    }

    public String getRoom() {
        return room;
    }

    public boolean isCreator() {
        return creator;
    }

    @Override
    public String toString() {
        return "PlayerInfo{id='" + id + "', room='" + room + "', creator=" + creator + "}";
    }
}
