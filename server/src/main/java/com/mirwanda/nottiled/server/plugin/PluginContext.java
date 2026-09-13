package com.mirwanda.nottiled.server.plugin;

import java.util.List;

/**
 * 插件运行期上下文：插件通过它向服务器查询信息与发送聊天消息。
 *
 * <p>注意：实现由服务器提供，插件只应在本接口上操作，
 * 不要直接引用服务器内部类，避免与服务器版本强耦合。</p>
 */
public interface PluginContext {

    /** 服务器显示名（可在配置里扩展，当前为 Re-NotTiled-Server） */
    String getServerName();

    /** 当前已连接（含未入房）的客户端总数 */
    int getOnlineCount();

    /** 返回某房间全部成员快照；房间不存在时返回空列表 */
    List<PlayerInfo> getRoomPlayers(String room);

    /**
     * 仅给指定玩家回一条聊天私信（发送方显示为“系统”，只有该玩家能看到）。
     *
     * @param playerId 目标玩家注册 ID（onChat 回调里的 who.id）
     * @param text     回复内容
     */
    void replyToPlayer(String playerId, String text);

    /** 向某房间全员广播一条聊天消息（含发送者本人，发送方显示为“系统”） */
    void sendToRoom(String room, String text);

    /** 向全服所有已连接客户端广播一条聊天消息 */
    void sendToAll(String text);

    /** 向服务器控制台输出一行日志（建议中文） */
    void log(String line);
}
