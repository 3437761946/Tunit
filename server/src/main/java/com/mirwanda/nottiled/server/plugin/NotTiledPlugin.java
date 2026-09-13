package com.mirwanda.nottiled.server.plugin;

/**
 * Re-NotTiled-Server 插件接口。
 *
 * <p>把实现本接口的类打成一个独立 jar，放进服务器的 {@code plugins} 目录，
 * 服务器启动时会自动加载并对每个插件调用 {@link #onEnable(PluginContext)}。</p>
 *
 * <p>交互规则：</p>
 * <ul>
 *   <li>聊天消息会先交给所有插件按加载顺序调用 {@link #onChat}；</li>
 *   <li>若任意插件返回 {@code true}，表示该消息已被消费，服务器不再转发给同房间其他人；</li>
 *   <li>以 {@code /} 开头的文本视为“命令”，即使没有插件消费也不会广播，而是由服务器回一句“未知命令”。</li>
 * </ul>
 *
 * <p>一个 jar 里可包含多个插件类；插件必须提供无参构造器。</p>
 */
public interface NotTiledPlugin {

    /** 插件名称（用于日志与错误定位，建议简短英文或中文均可） */
    String name();

    /** 插件被启用时调用（服务器扫描到并实例化后） */
    default void onEnable(PluginContext ctx) {
    }

    /** 服务器关闭 / 插件被停用时调用（尽量把占用资源释放干净） */
    default void onDisable() {
    }

    /**
     * 处理一条房间聊天消息或聊天命令。
     *
     * @param who         发送者快照（id / 房间 / 是否房主）
     * @param displayName 发送者昵称（客户端填写，可能为空）
     * @param text        消息正文（以 / 开头的字符串视为命令，例如 "/list"）
     * @return true 表示已消费该消息（不再转发）；false 表示交给服务器继续处理
     */
    default boolean onChat(PlayerInfo who, String displayName, String text) {
        return false;
    }
}
