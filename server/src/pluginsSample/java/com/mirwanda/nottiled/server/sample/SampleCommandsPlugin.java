package com.mirwanda.nottiled.server.sample;

import com.mirwanda.nottiled.server.plugin.NotTiledPlugin;
import com.mirwanda.nottiled.server.plugin.PluginContext;
import com.mirwanda.nottiled.server.plugin.PlayerInfo;

import java.util.List;

/**
 * 示例插件：为服务器提供三个简单的聊天指令。
 *
 * <p>编译为独立 jar 后放进服务器 plugins 目录即可被自动加载（见 server 模块
 * build.gradle.kts 的 samplePluginJar 任务）。本插件演示：</p>
 * <ul>
 *   <li>/help     —— 查看服务器可用指令；</li>
 *   <li>/list     —— 查看自己所在房间的成员名单（未入房则返回全服在线人数）；</li>
 *   <li>/notice 公告 —— 仅房主可用，向整个房间广播系统公告。</li>
 * </ul>
 *
 * <p>说明：本插件只依赖服务器 jar 中已包含的协议类与插件 API，
 * 因此打包时无需内嵌任何第三方库，jar 体积很小。</p>
 */
public class SampleCommandsPlugin implements NotTiledPlugin {

    private PluginContext ctx;

    @Override
    public String name() {
        return "示例指令插件(SampleCommands)";
    }

    @Override
    public void onEnable(PluginContext context) {
        this.ctx = context;
        ctx.log("已启用！试试在房间聊天框输入 /help、/list，或由房主输入 /notice 内容 广播公告。");
    }

    @Override
    public void onDisable() {
        this.ctx = null;
    }

    @Override
    public boolean onChat(PlayerInfo who, String displayName, String text) {
        if (ctx == null || text == null) return false;
        String cmd = text.trim();
        if (cmd.equals("/help")) {
            ctx.replyToPlayer(who.getId(),
                    "可用指令：/help（本帮助）、/list（查看房间成员）、/notice 内容（房主公告到本房间）。");
            return true;
        }
        if (cmd.equals("/list")) {
            if (who.getRoom() == null || who.getRoom().isEmpty()) {
                ctx.replyToPlayer(who.getId(), "当前在线玩家数：" + ctx.getOnlineCount() + "。加入房间后可用 /list 查看房间成员。");
                return true;
            }
            List<PlayerInfo> members = ctx.getRoomPlayers(who.getRoom());
            StringBuilder sb = new StringBuilder("房间[" + who.getRoom() + "] 成员(" + members.size() + "人)：");
            for (PlayerInfo m : members) {
                sb.append(m.getRoom().equalsIgnoreCase(who.getRoom()) ? " " : "");
                sb.append(m.getId());
                if (m.isCreator()) sb.append("(房主)");
                sb.append("；");
            }
            ctx.replyToPlayer(who.getId(), sb.toString());
            return true;
        }
        if (cmd.startsWith("/notice ")) {
            String msg = cmd.substring("/notice ".length()).trim();
            if (msg.isEmpty()) {
                ctx.replyToPlayer(who.getId(), "用法：/notice 公告内容");
                return true;
            }
            if (!who.isCreator()) {
                ctx.replyToPlayer(who.getId(), "只有房主才能广播公告。");
                return true;
            }
            if (who.getRoom() == null || who.getRoom().isEmpty()) {
                ctx.replyToPlayer(who.getId(), "请先创建/加入一个房间再广播公告。");
                return true;
            }
            ctx.sendToRoom(who.getRoom(), "[公告] " + displayName + "：" + msg);
            ctx.log("房主 " + who.getId() + " 在房间[" + who.getRoom() + "] 发布了公告：" + msg);
            return true;
        }
        // 返回 false：不是本插件能处理的命令，交给服务器继续
        return false;
    }
}
