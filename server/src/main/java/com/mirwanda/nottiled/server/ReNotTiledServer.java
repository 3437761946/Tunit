package com.mirwanda.nottiled.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

import com.mirwanda.nottiled.PlayerState;
import com.mirwanda.nottiled.TextChat;
import com.mirwanda.nottiled.command;
import com.mirwanda.nottiled.layerhistory;
import com.mirwanda.nottiled.server.plugin.NotTiledPlugin;
import com.mirwanda.nottiled.server.plugin.PluginContext;
import com.mirwanda.nottiled.server.plugin.PlayerInfo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Re-NotTiled 无头开服服务器 —— 纯中继 + 多房间管理。
 *
 * <p>与 App 端「内嵌 Server」协议完全一致（同一份协议类源码、同一 Kryo 注册顺序），
 * 因此现有 NotTiled 客户端的 创建房间 / 加入房间 / 编辑同步 均无需改动即可连入。</p>
 *
 * <p><b>架构定位</b>：服务器为纯中继，不落盘、不存地图。地图数据始终由房间创建者
 * （creator 客户端）推送，服务器只负责按房间转发，因此几乎无持久化内存开销。</p>
 *
 * <p><b>本文件相对原版新增的能力</b>：</p>
 * <ul>
 *   <li>插件目录：启动时扫描 {@code pluginsDir}（默认 {@code plugins}）下所有 *.jar，
 *       自动加载实现 {@link NotTiledPlugin} 的类并回调 onEnable；聊天消息先交给插件，
 *       以 / 开头的文本按「命令」处理。插件全部以中文日志反馈。</li>
 *   <li>地图推送上限保护：整图推送按「服务器实际收到字符数」累计，超过
 *       {@code maxMapPushMB}（默认 10，单位 MB×1024×1024 字符）时，停止转发分片、
 *       向房间全员发 {@code mapRejectedTooBig} 提示，并在约 1 秒后由守护循环断开房主连接。</li>
 *   <li>空房 / 空闲房间回收：守护循环每秒执行——closeAt 到期连接踢出；
 *       成员残留而无房主的异常房间立即解散（防御）；房间整体超过
 *       {@code roomIdleTimeoutMin}（默认 30 分钟，0 关闭）没有任何消息活动时自动解散，
 *       释放房主与成员状态，避免长期占用服务器内存与流量。</li>
 * </ul>
 *
 * <p>限制：maxRooms 个房间、每房 maxPlayersPerRoom 人、全服最多 maxConnections 连接。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java -jar Re-NotTiled-Server.jar                        # 读取同目录 server.properties
 *   java -jar Re-NotTiled-Server.jar 11112 54777            # 位置参数直接指定 TCP/UDP 端口
 *   java -jar Re-NotTiled-Server.jar --maxMapPushMB 20 --roomIdleTimeoutMin 15
 * </pre>
 */
public class ReNotTiledServer {

    // ---------------- 常驻房间 / 投票 常量 ----------------
    /** 常驻房间名（无房主，始终在线；不占用也不受 maxRooms 限制）。 */
    static final String[] LOBBY_ROOMS = {"大厅1", "大厅2", "大厅3"};
    /** 投票超时（毫秒）。 */
    static final long VOTE_TIMEOUT_MS = 60_000L;

    // ---------------- 房间成员状态 ----------------
    /**
     * 一个连接（玩家）在服务器侧的全部状态。
     * 成员字段随注册 / 建房 / 入房 / 断线等事件变更，整个访问过程在 {@link #lock} 内完成。
     */
    static class Peer {
        final Connection conn;      // KryoNet 底层连接
        String id = "";             // 客户端 myID，形如 "Steve_1234567"
        String nickname = "";       // 展示昵称（registerID 从 id 尾部「_数字」剥离得到）
        String room = "";           // 当前所在房间名（空 = 大厅/未入房）
        boolean creator;            // 是否为该房间创建者（房主，负责推送整图）
        String pass = "";           // 房主创建房间时设置的密码（仅 creator 有意义，不参与序列化）
        String map = "";            // 房主上报的当前地图文件名（仅 creator 有意义）
        int maxPlayers = -1;        // 房主建房时声明的人数上限（<=0 表示沿用全局上限）
        long pushChars = 0;         // 本轮整图推送已累计收到的字符数（仅 creator 推送时增长）
        boolean pushBlocked = false;// 已达地图推送上限，正在丢弃后续分片（等待被踢）
        long closeAt = 0;           // >0 表示到达该毫秒时刻后守护循环应关闭此连接（0 = 不关闭）
        long lastActive;            // 最后一次收到该客户端任意消息的毫秒时间（空闲房间判定依据）
        boolean registered = false; // 是否已完成 registerID 注册（僵尸连接清理依据）
        long connectedAt;           // 建立连接的时间戳（毫秒）
        long joinedAt = 0;          // 最近一次入房的时间戳（毫秒，用于判定常驻房「第一个进入者」）
        String remoteIp = "";      // 客户端来源 IP（连接频率限速依据）
        int versionCode = 0;        // 客户端版本号（registerID 后由 clientVersion 上报；0=未知）
        String versionName = "";    // 客户端版本名（用于房间列表展示）
        Peer(Connection c) {
            conn = c;
            lastActive = System.currentTimeMillis();
            connectedAt = lastActive;
        }
    }

    /** 房主断线宽限托管记录：房主离开后房间短暂保留，等待其重连恢复（仅在 lock 内访问）。 */
    static class RoomPreserve {
        final String room;        // 房间显示名（保留原大小写）
        final String nick;        // 房主昵称（注册ID去掉随机数字尾缀；重连时据此识别房主）
        final String pass;        // 房间密码（恢复房主身份用）
        final String map;         // 最近上报的地图文件名（恢复房主身份用）
        final int maxPlayers;     // 人数上限（恢复房主身份用）
        final long deadline;      // 托管到期毫秒时间戳（到期仍未恢复则解散房间）
        RoomPreserve(String room, String nick, String pass, String map, int maxPlayers, long deadline) {
            this.room = room;
            this.nick = nick == null ? "" : nick;
            this.pass = pass == null ? "" : pass;
            this.map = map == null ? "" : map;
            this.maxPlayers = maxPlayers;
            this.deadline = deadline;
        }
    }

    /** 一次进行中的投票（推送更新 / 禁编 / 解除禁编）。仅在 lock 内访问。 */
    static class Vote {
        final String room;          // 房间显示名
        final String type;          // PUSH / BAN / UNBAN
        final String targetId;      // BAN/UNBAN 的目标成员ID；PUSH 为空
        final String initiatorId;   // 发起人ID
        final int threshold;        // 通过所需同意票数（= 发起时在座成员数/2 + 1）
        final long deadline;        // 到期毫秒时间戳
        final Set<String> yes = new HashSet<>();
        final Set<String> no = new HashSet<>();
        Vote(String room, String type, String targetId, String initiatorId, int threshold, long deadline) {
            this.room = room == null ? "" : room;
            this.type = type == null ? "" : type;
            this.targetId = targetId == null ? "" : targetId;
            this.initiatorId = initiatorId == null ? "" : initiatorId;
            this.threshold = threshold;
            this.deadline = deadline;
        }
    }

    // ---------------- 服务器配置（不可变） ----------------
    private final int tcpPort;
    private final int udpPort;              // <=0 表示关闭 UDP
    private final int maxRooms;             // 最大同时房间数
    private final int maxPlayersPerRoom;    // 每房全局人数上限（房间级可更小）
    private final int maxConnections;       // 全服最大连接数
    private final long maxMapChars;         // 单次整图推送允许的最大字符数 = maxMapPushMB × 1024 × 1024
    private final int roomIdleTimeoutMin;   // 房间空闲回收阈值（分钟，0 = 关闭该功能）
    private final String pluginsDir;        // 插件目录路径（相对进程工作目录或绝对路径均可）
    private final boolean enablePlugins;    // 是否加载插件（关闭可消除插件带来的代码执行面）
    private final int registerTimeoutSec;   // 连接后未 registerID 的僵尸断开阈值（秒，0 = 关闭）
    private final int newConnLimitPer10s;   // 同一 IP 10 秒内最多新建连接次数（0 = 不限速）
    private final int hostGraceSec;         // 房主断线后房间宽限保留（秒，0 = 关闭）
    private final int httpPort;             // 更新用 HTTP 端口（<=0 表示关闭）
    private final String updateDir;         // 更新文件目录（含 version.json 与 apk）
    // ---- 更新 HTTP 服务防刷限制（构造时确定）----
    private final int updateReqLimitPerMin;    // 每 IP 每分钟通用请求上限（0=不限）
    private final int updateApkLimitPerHour;   // 每 IP 每小时 APK 下载上限（0=不限）
    private final int updateMaxConcurrent;     // 全服同时下载 APK 的连接数上限（0=不限）
    private final int updateMaxKBps;           // 单连接 APK 下载限速 KB/s（0=不限）
    private final boolean updateOverGame;      // 是否允许通过游戏 TCP 连接分发更新（单端口模式；默认开）

    // ---------------- 运行期状态 ----------------
    private final Map<Integer, Peer> peers = new LinkedHashMap<>(); // connectionID -> Peer（按连入顺序保持）
    private final Object lock = new Object();                        // 所有房间/成员状态修改的统一锁
    // IP -> 最近 10 秒内的新连接时间戳队列（连接频率限速；仅在 lock 内访问）
    private final Map<String, Deque<Long>> ipNewConns = new LinkedHashMap<>();
    // 房间名(小写) -> 被房主「禁编」的成员ID集合（禁绘画/对象编辑，不禁聊天；仅在 lock 内访问）
    private final Map<String, Set<String>> roomBanned = new LinkedHashMap<>();
    // 房间名(小写) -> 房主离线宽限托管记录（房主断线后房间暂不销毁，等待其重连恢复；仅在 lock 内访问）
    private final Map<String, RoomPreserve> hostLeaves = new LinkedHashMap<>();
    // 常驻房间(小写) -> 最近一次上报的地图文件名（仅用于房间列表展示；仅在 lock 内访问）
    private final Map<String, String> lobbyMap = new LinkedHashMap<>();
    // 常驻房间(小写) -> 管理员设置的房间密码（空 = 公开；房间变空时清除；仅在 lock 内访问）
    private final Map<String, String> lobbyPass = new LinkedHashMap<>();
    // 房间名(小写) -> 进行中的投票（一次仅一票；仅在 lock 内访问）
    private final Map<String, Vote> roomVotes = new LinkedHashMap<>();
    // 房间名(小写) -> 已获整图推送授权的成员ID集合（常驻房投票通过后授予；仅在 lock 内访问）
    private final Map<String, Set<String>> pushGrants = new LinkedHashMap<>();
    // 房间名(小写) -> 待房主审批的加入申请（申请人ID -> 申请人昵称；仅在 lock 内访问）
    private final Map<String, Map<String, String>> pendingApply = new LinkedHashMap<>();
    private Server server;
    private HttpServer http;                // 内置更新用 HTTP 服务（httpPort>0 时启动）
    private ExecutorService httpPool;       // 更新服务线程池（有界，配合并发下载限制）
    private Semaphore httpApkSlots;         // 并发下载许可（updateMaxConcurrent>0 时创建）
    private final Object httpLock = new Object();                       // 更新服务限流状态的锁
    private final Map<String, Deque<Long>> httpReqWindow = new LinkedHashMap<>(); // IP -> 近期请求时间戳
    private final Map<String, Deque<Long>> httpApkWindow = new LinkedHashMap<>(); // IP -> 近期 APK 下载时间戳
    // ---- 单端口 OTA（走游戏 TCP 连接分发更新）运行时状态 ----
    private volatile Semaphore otaSlots;    // OTA 并发下载许可（updateMaxConcurrent>0 时创建）
    private ExecutorService otaPool;        // 单端口 OTA 下载专用线程池（避免在网络线程上发送整包）
    private final Object otaLock = new Object();
    private final Map<String, Deque<Long>> otaReqWindow = new LinkedHashMap<>(); // IP -> 近期 OTA 请求时间戳
    private final Map<String, Deque<Long>> otaApkWindow = new LinkedHashMap<>(); // IP -> 近期 OTA 下载时间戳
    private volatile boolean running = true;
    private String lastRoomList = "";   // 最近一次广播的房间列表快照（内容无变化时跳过推送，省流量）

    private final List<NotTiledPlugin> plugins = new ArrayList<>();        // 已启用插件（按加载顺序）
    private final List<URLClassLoader> pluginLoaders = new ArrayList<>();  // 保持插件 ClassLoader 引用，防被 GC 卸载
    private final PluginContext pluginContext;                              // 暴露给插件的服务器操作能力

    public ReNotTiledServer(int tcpPort, int udpPort, int maxRooms, int maxPlayersPerRoom,
                            int maxConnections, int maxMapPushMB, int roomIdleTimeoutMin,
                            String pluginsDir, int registerTimeoutSec, int newConnLimitPer10s,
                            int hostGraceSec, int httpPort, String updateDir,
                            int updateReqLimitPerMin, int updateApkLimitPerHour,
                            int updateMaxConcurrent, int updateMaxKBps, boolean updateOverGame,
                            boolean enablePlugins) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.maxRooms = maxRooms;
        this.maxPlayersPerRoom = maxPlayersPerRoom;
        this.maxConnections = maxConnections;
        this.maxMapChars = Math.max(1, maxMapPushMB) * 1024L * 1024L;
        this.roomIdleTimeoutMin = Math.max(0, roomIdleTimeoutMin);
        String pd = (pluginsDir == null) ? "" : pluginsDir.trim();
        this.pluginsDir = pd.isEmpty() ? "plugins" : pd;
        this.registerTimeoutSec = Math.max(0, registerTimeoutSec);
        this.newConnLimitPer10s = Math.max(0, newConnLimitPer10s);
        this.hostGraceSec = Math.max(0, hostGraceSec);
        this.httpPort = Math.max(0, httpPort);
        String ud = (updateDir == null) ? "" : updateDir.trim();
        this.updateDir = ud.isEmpty() ? "update" : ud;
        this.updateReqLimitPerMin = Math.max(0, updateReqLimitPerMin);
        this.updateApkLimitPerHour = Math.max(0, updateApkLimitPerHour);
        this.updateMaxConcurrent = Math.max(0, updateMaxConcurrent);
        this.updateMaxKBps = Math.max(0, updateMaxKBps);
        this.updateOverGame = updateOverGame;
        this.enablePlugins = enablePlugins;
        this.pluginContext = buildPluginContext();
    }

    // ====================================================================
    //  入口 / 配置解析
    // ====================================================================
    public static void main(String[] args) throws Exception {
        // 1) 解析命令行：--key=value / --key value / --help
        Map<String, String> cli = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--help") || a.equalsIgnoreCase("-h")) {
                printHelp();
                return;
            }
            if (a.startsWith("--")) {
                String kv = a.substring(2);
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    cli.put(kv.substring(0, eq).toLowerCase(), kv.substring(eq + 1));
                } else if (i + 1 < args.length) {
                    cli.put(kv.toLowerCase(), args[++i]);
                }
            } else {
                positional.add(a);
            }
        }

        // 2) 内置默认值
        Properties p = new Properties();
        p.setProperty("tcp", "40686");
        p.setProperty("udp", "54777");
        p.setProperty("maxRooms", "2");
        p.setProperty("maxPlayersPerRoom", "6");
        p.setProperty("maxConnections", "10");
        p.setProperty("maxMapPushMB", "10");         // 整图推送上限（MB，按实际收到字符数计）
        p.setProperty("roomIdleTimeoutMin", "30");   // 房间空闲回收（分钟，0 关闭）
        p.setProperty("pluginsDir", "plugins");      // 插件目录
        p.setProperty("enablePlugins", "1");         // 是否加载插件（1=开 0=关；关闭可消除插件带来的代码执行面）
        p.setProperty("registerTimeoutSec", "60");   // 连接后未注册的僵尸断开（秒，0 关闭）
        p.setProperty("newConnLimitPer10s", "5");    // 同一 IP 10 秒内最大新连接数（0 关闭）
        p.setProperty("hostGraceSec", "90");         // 房主断线后房间宽限保留（秒，0 关闭）
        p.setProperty("httpPort", "0");              // 更新用 HTTP 端口（0 = 关闭；建议填游戏端口+1，如 40687）
        p.setProperty("updateDir", "update");        // 更新文件目录（含 version.json 与 app-release.apk）
        p.setProperty("updateReqLimitPerMin", "60");  // 更新服务：每 IP 每分钟请求上限（0=不限；防高频刷）
        p.setProperty("updateApkLimitPerHour", "20"); // 更新服务：每 IP 每小时 APK 下载上限（0=不限；防刷大流量）
        p.setProperty("updateMaxConcurrent", "3");    // 更新服务：全服并发 APK 下载上限（0=不限）
        p.setProperty("updateMaxKBps", "0");          // 更新服务：单连接限速 KB/s（0=不限）
        p.setProperty("updateOverGame", "1");         // 允许通过游戏 TCP 连接分发更新（1=开 0=关；单端口模式）

        // 3) server.properties（可用 --config 指定路径）
        String cfgPath = cli.containsKey("config") ? cli.get("config") : "server.properties";
        File cfgFile = new File(cfgPath);
        if (cfgFile.exists()) {
            try (InputStream in = new FileInputStream(cfgFile)) {
                p.load(in);
                log("[cfg] 已加载配置文件 " + cfgFile.getAbsolutePath());
            }
        } else {
            log("[cfg] 未找到配置文件 " + cfgFile.getAbsolutePath() + "，使用内置默认值");
        }

        // 4) 命令行覆盖配置文件（可同时支持配置文件与命令行互相覆盖）
        // 注意：Properties 键区分大小写，这里显式映射回服务端读取所用的规范键名。
        for (Map.Entry<String, String> e : cli.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            switch (k) {
                case "tcp":
                    p.setProperty("tcp", v);
                    break;
                case "udp":
                    p.setProperty("udp", v);
                    break;
                case "maxrooms":
                    p.setProperty("maxRooms", v);
                    break;
                case "maxplayersperroom":
                    p.setProperty("maxPlayersPerRoom", v);
                    break;
                case "maxconnections":
                    p.setProperty("maxConnections", v);
                    break;
                case "maxmappushmb":
                    p.setProperty("maxMapPushMB", v);
                    break;
                case "roomidletimeoutmin":
                    p.setProperty("roomIdleTimeoutMin", v);
                    break;
                case "pluginsdir":
                    p.setProperty("pluginsDir", v);
                    break;
                case "enableplugins":
                    p.setProperty("enablePlugins", v);
                    break;
                case "registertimeoutsec":
                    p.setProperty("registerTimeoutSec", v);
                    break;
                case "newconnlimitper10s":
                    p.setProperty("newConnLimitPer10s", v);
                    break;
                case "hostgracesec":
                    p.setProperty("hostGraceSec", v);
                    break;
                case "httpport":
                    p.setProperty("httpPort", v);
                    break;
                case "updatedir":
                    p.setProperty("updateDir", v);
                    break;
                case "updatereqlimitpermin":
                    p.setProperty("updateReqLimitPerMin", v);
                    break;
                case "updateapklimitperhour":
                    p.setProperty("updateApkLimitPerHour", v);
                    break;
                case "updatemaxconcurrent":
                    p.setProperty("updateMaxConcurrent", v);
                    break;
                case "updatemaxkbps":
                    p.setProperty("updateMaxKBps", v);
                    break;
                case "updateovergame":
                    p.setProperty("updateOverGame", v);
                    break;
                default:
                    // 其他 -- 参数（如 --config）不参与服务器配置
                    break;
            }
        }
        // 位置参数前两个依次作为 TCP / UDP 端口
        if (!positional.isEmpty()) p.setProperty("tcp", positional.get(0));
        if (positional.size() > 1) p.setProperty("udp", positional.get(1));

        int tcp = getInt(p, "tcp", 11112);
        int udp = getInt(p, "udp", 54777);
        int maxRooms = getInt(p, "maxRooms", 2);
        int maxPlayers = getInt(p, "maxPlayersPerRoom", 6);
        int maxConns = getInt(p, "maxConnections", 10);
        int maxMapPushMB = getInt(p, "maxMapPushMB", 10);
        int idleTimeoutMin = getInt(p, "roomIdleTimeoutMin", 30);
        String pluginsDir = p.getProperty("pluginsDir", "plugins").trim();
        boolean enablePlugins = !"0".equals(p.getProperty("enablePlugins", "1").trim());
        int registerTimeoutSec = getInt(p, "registerTimeoutSec", 60);
        int newConnLimitPer10s = getInt(p, "newConnLimitPer10s", 5);
        int hostGraceSec = getInt(p, "hostGraceSec", 90);
        int httpPort = getInt(p, "httpPort", 0);
        String updateDir = p.getProperty("updateDir", "update").trim();
        int updateReqLimitPerMin = getInt(p, "updateReqLimitPerMin", 60);
        int updateApkLimitPerHour = getInt(p, "updateApkLimitPerHour", 20);
        int updateMaxConcurrent = getInt(p, "updateMaxConcurrent", 3);
        int updateMaxKBps = getInt(p, "updateMaxKBps", 0);
        boolean updateOverGame = !"0".equals(p.getProperty("updateOverGame", "1").trim());

        ReNotTiledServer srv = new ReNotTiledServer(tcp, udp, maxRooms, maxPlayers, maxConns,
                maxMapPushMB, idleTimeoutMin, pluginsDir, registerTimeoutSec, newConnLimitPer10s,
                hostGraceSec, httpPort, updateDir,
                updateReqLimitPerMin, updateApkLimitPerHour, updateMaxConcurrent, updateMaxKBps,
                updateOverGame, enablePlugins);
        srv.start();
    }

    /** 中文启动帮助。列出位置参数、命令行可选参数与 server.properties 支持的键。 */
    private static void printHelp() {
        System.out.println("==================================================");
        System.out.println(" Re-NotTiled-Server  独立开服服务器 使用说明");
        System.out.println("==================================================");
        System.out.println("用法:");
        System.out.println("  java -jar Re-NotTiled-Server.jar                       # 读取同目录 server.properties");
        System.out.println("  java -jar Re-NotTiled-Server.jar 11112 54777           # 位置参数指定 TCP/UDP 端口");
        System.out.println();
        System.out.println("可选参数 (优先级高于 server.properties):");
        System.out.println("  --tcp 11112             TCP 端口");
        System.out.println("  --udp 54777             UDP 端口 (0 表示关闭 UDP)");
        System.out.println("  --maxRooms 2            最大同时房间数");
        System.out.println("  --maxPlayersPerRoom 6   每房间人数上限");
        System.out.println("  --maxConnections 10     全服最大连接数");
        System.out.println("  --maxMapPushMB 10       整图推送上限(MB, 按服务器实际收到字符数计)");
        System.out.println("  --roomIdleTimeoutMin 30 房间空闲回收(分钟, 0=关闭)");
        System.out.println("  --pluginsDir plugins    插件目录(自动加载其中的 .jar)");
        System.out.println("  --registerTimeoutSec 60 连接后未注册的僵尸断开(秒, 0=关闭)");
        System.out.println("  --newConnLimitPer10s 5  同一IP 10秒内最大新连接数(0=关闭限速)");
        System.out.println("  --hostGraceSec 90      房主断线后房间宽限保留(秒, 0=立即解散, 用于切屏/掉线防全员退出)");
        System.out.println("  --httpPort 0           更新用 HTTP 端口(0=关闭; 建议填游戏端口+1, 如 40687)");
        System.out.println("  --updateDir update     更新文件目录(含 version.json 与 app-release.apk)");
        System.out.println("  --updateReqLimitPerMin 60   更新服务:每IP每分钟请求上限(0=不限)");
        System.out.println("  --updateApkLimitPerHour 20  更新服务:每IP每小时APK下载上限(0=不限)");
        System.out.println("  --updateMaxConcurrent 3     更新服务:全服并发下载上限(0=不限)");
        System.out.println("  --updateMaxKBps 0           更新服务:单连接下载限速KB/s(0=不限)");
        System.out.println("  --updateOverGame 1          允许通过游戏TCP端口分发更新(1=开 0=关; 单端口模式)");
        System.out.println("  --config server.properties   指定配置文件路径");
        System.out.println();
        System.out.println("server.properties 支持键:");
        System.out.println("  tcp / udp / maxRooms / maxPlayersPerRoom / maxConnections /");
        System.out.println("  maxMapPushMB / roomIdleTimeoutMin / pluginsDir / registerTimeoutSec / newConnLimitPer10s / hostGraceSec /");
        System.out.println("  httpPort / updateDir / updateOverGame /");
        System.out.println("  updateReqLimitPerMin / updateApkLimitPerHour / updateMaxConcurrent / updateMaxKBps");
        System.out.println();
        System.out.println("插件开发: 实现接口 com.mirwanda.nottiled.server.plugin.NotTiledPlugin");
        System.out.println("  将编译产物 .jar 放入 plugins 目录即可自动加载。");
        System.out.println("==================================================");
    }

    /** 宽松解析整数字符串，失败返回 def（用于客户端版本号等上报字段）。 */
    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static int getInt(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (Exception ex) {
            return def;
        }
    }

    // ====================================================================
    //  更新用 HTTP 服务：/version.json 与 /app-release.apk
    // ====================================================================
    /**
     * 启动内置更新 HTTP 服务（仅当 httpPort>0）。
     * 该服务与游戏 TCP 端口相互独立，只做静态文件分发，不参与联机中继。
     */
    private void startHttpServer() {
        if (httpPort <= 0) {
            log("[http] 更新服务未启用（httpPort=0）");
            return;
        }
        try {
            http = HttpServer.create(new InetSocketAddress(httpPort), 0);
            http.createContext("/version.json",
                    ex -> serveUpdateFile(ex, "version.json", "application/json; charset=utf-8"));
            http.createContext("/app-release.apk",
                    ex -> serveUpdateFile(ex, "app-release.apk", "application/vnd.android.package-archive"));
            // 有界线程池：允许一定并发，同时由 httpApkSlots 限制同时下载数
            int pool = (updateMaxConcurrent > 0) ? Math.max(2, updateMaxConcurrent) : 4;
            httpPool = Executors.newFixedThreadPool(pool);
            http.setExecutor(httpPool);
            httpApkSlots = (updateMaxConcurrent > 0) ? new Semaphore(updateMaxConcurrent, true) : null;
            http.start();
            log("[http] 更新服务已启动: http://0.0.0.0:" + httpPort
                    + "/version.json  (更新目录 " + new File(updateDir).getAbsolutePath() + ")");
        } catch (Exception e) {
            log("[http] 更新服务启动失败（端口 " + httpPort + "）: " + e);
            try { if (http != null) http.stop(0); } catch (Exception ignore) {}
            http = null;
        }
    }

    /** 停止更新 HTTP 服务。 */
    private void stopHttpServer() {
        try {
            if (http != null) {
                http.stop(0);
                http = null;
                log("[http] 更新服务已停止");
            }
            if (httpPool != null) {
                httpPool.shutdownNow();
                httpPool = null;
            }
            synchronized (httpLock) {
                httpReqWindow.clear();
                httpApkWindow.clear();
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 返回更新目录下的指定文件（静态服务）。
     * 防护：每 IP 通用请求限速 / 每 IP APK 下载限次 / 全服并发下载上限 / 单连接下载限速。
     * 仅支持 GET/HEAD；文件不存在返回 404。带 Content-Length，便于客户端显示下载进度。
     */
    private void serveUpdateFile(HttpExchange ex, String fileName, String contentType) {
        OutputStream os = null;
        boolean slotAcquired = false;
        String ip = clientIp(ex);
        try {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            // 1) 通用请求频率限制（每 IP 每分钟）
            if (!allowHttpRequest(ip)) {
                reject(ex, 429, "请求过于频繁，请稍后再试。");
                log("[http] 429 限速 " + ip + " " + ex.getRequestURI());
                return;
            }
            boolean isApk = fileName.toLowerCase().endsWith(".apk");
            File f = new File(updateDir, fileName);
            if (!f.exists() || !f.isFile()) {
                reject(ex, 404, "404: " + fileName + " not found");
                log("[http] 404 " + ex.getRequestURI() + "（缺少 " + f.getAbsolutePath() + "）");
                return;
            }
            // 2) APK 下载频率限制（每 IP 每小时，防刷大流量）
            if (isApk && !allowApkDownload(ip)) {
                reject(ex, 429, "下载次数过于频繁，请稍后再试。");
                log("[http] 429 APK 限次 " + ip);
                return;
            }
            // 3) 并发下载数限制（仅对 APK 的实际下载，HEAD 不计）
            if (isApk && !"HEAD".equalsIgnoreCase(method) && httpApkSlots != null) {
                if (!httpApkSlots.tryAcquire()) {
                    reject(ex, 503, "当前下载人数过多，请稍后再试。");
                    log("[http] 503 并发已满 " + ip);
                    return;
                }
                slotAcquired = true;
            }
            long len = f.length();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
            if ("HEAD".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, len);
            os = ex.getResponseBody();
            // 4) 单连接下载限速（可选）
            long maxBytesPerSec = (updateMaxKBps > 0) ? (updateMaxKBps * 1024L) : 0;
            long startNs = System.nanoTime();
            long sent = 0;
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    sent += n;
                    if (maxBytesPerSec > 0) {
                        long expectedNs = sent * 1_000_000_000L / maxBytesPerSec;
                        long sleepNs = expectedNs - (System.nanoTime() - startNs);
                        if (sleepNs > 0) {
                            try { Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L)); }
                            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        }
                    }
                }
            }
            log("[http] 200 " + ex.getRequestURI() + "  " + len + " bytes");
        } catch (Exception e) {
            log("[http] 响应异常: " + e);
        } finally {
            if (slotAcquired && httpApkSlots != null) {
                try { httpApkSlots.release(); } catch (Exception ignore) {}
            }
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { ex.close(); } catch (Exception ignore) {}
        }
    }

    /** 取客户端 IP（取不到返回 "?"）。 */
    private String clientIp(HttpExchange ex) {
        try {
            if (ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null)
                return ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception ignore) {}
        return "?";
    }

    /** 每 IP 每分钟通用请求限速；超限返回 false。updateReqLimitPerMin<=0 时不限。 */
    private boolean allowHttpRequest(String ip) {
        if (updateReqLimitPerMin <= 0) return true;
        synchronized (httpLock) {
            long now = System.currentTimeMillis();
            Deque<Long> q = httpReqWindow.computeIfAbsent(ip, k -> new ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst() < now - 60_000L) q.pollFirst();
            boolean ok = q.size() < updateReqLimitPerMin;
            if (ok) q.addLast(now);
            trimHttpWindows(now);
            return ok;
        }
    }

    /** 每 IP 每小时 APK 下载限次；超限返回 false。updateApkLimitPerHour<=0 时不限。 */
    private boolean allowApkDownload(String ip) {
        if (updateApkLimitPerHour <= 0) return true;
        synchronized (httpLock) {
            long now = System.currentTimeMillis();
            Deque<Long> q = httpApkWindow.computeIfAbsent(ip, k -> new ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst() < now - 3600_000L) q.pollFirst();
            boolean ok = q.size() < updateApkLimitPerHour;
            if (ok) q.addLast(now);
            trimHttpWindows(now);
            return ok;
        }
    }

    /** 防止限流表无限增长：过大时清理已过窗口的空 IP 记录。须在 httpLock 内调用。 */
    private void trimHttpWindows(long now) {
        if (!httpReqWindow.isEmpty()) {
            Iterator<Map.Entry<String, Deque<Long>>> it = httpReqWindow.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Deque<Long>> e = it.next();
                Deque<Long> dq = e.getValue();
                while (!dq.isEmpty() && dq.peekFirst() < now - 60_000L) dq.pollFirst();
                if (dq.isEmpty()) it.remove();
            }
        }
        if (!httpApkWindow.isEmpty()) {
            Iterator<Map.Entry<String, Deque<Long>>> it = httpApkWindow.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Deque<Long>> e = it.next();
                Deque<Long> dq = e.getValue();
                while (!dq.isEmpty() && dq.peekFirst() < now - 3600_000L) dq.pollFirst();
                if (dq.isEmpty()) it.remove();
            }
        }
    }

    /** 返回一个带中文说明的限流/错误响应（429/503/404 等）。 */
    private void reject(HttpExchange ex, int code, String msg) {
        OutputStream os = null;
        try {
            byte[] body = msg.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(code, body.length);
            os = ex.getResponseBody();
            os.write(body);
        } catch (Exception ignore) {
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignore) {}
            try { ex.close(); } catch (Exception ignore) {}
        }
    }

    // ====================================================================
    //  启动 / 停止
    // ====================================================================
    public void start() throws Exception {
        log("========== Re-NotTiled Server ==========");
        log("TCP=" + tcpPort + "  UDP=" + (udpPort > 0 ? udpPort : "disabled"));
        log("maxRooms=" + maxRooms + "  maxPlayersPerRoom=" + maxPlayersPerRoom + "  maxConnections=" + maxConnections);
        log("maxMapPushMB=" + (maxMapChars / 1024 / 1024) + "  roomIdleTimeoutMin="
                + (roomIdleTimeoutMin > 0 ? roomIdleTimeoutMin : "disabled") + "  pluginsDir=" + pluginsDir);
        log("registerTimeoutSec=" + (registerTimeoutSec > 0 ? registerTimeoutSec : "disabled")
                + "  newConnLimitPer10s=" + (newConnLimitPer10s > 0 ? newConnLimitPer10s : "disabled"));
        log("hostGraceSec=" + (hostGraceSec > 0 ? hostGraceSec : "disabled")
                + "  (房主断线后房间宽限保留，超时未重连则解散)");

        log("httpPort=" + (httpPort > 0 ? httpPort : "disabled")
                + "  updateDir=" + updateDir + "  (更新服务: /version.json 与 /app-release.apk)");

        // 单端口 OTA：允许客户端通过游戏 TCP 端口拉取更新（无需额外 HTTP 端口）
        otaSlots = (updateMaxConcurrent > 0) ? new Semaphore(updateMaxConcurrent, true) : null;
        // OTA 下载专用线程池：把「发送整包 APK」从 KryoNet 网络线程剥离，避免下载期间网络线程被占满，
        // 导致新连接无法完成 TCP 注册（Connected, but timed out during TCP registration）与消息转发停滞。
        int otaThreads = (updateMaxConcurrent > 0) ? Math.max(2, updateMaxConcurrent) : 4;
        otaPool = Executors.newFixedThreadPool(otaThreads, r -> {
            Thread t = new Thread(r, "ota-send");
            t.setDaemon(true);
            return t;
        });
        log("updateOverGame=" + (updateOverGame ? "on" : "off")
                + "  (单端口模式：更新经游戏 TCP 端口 " + tcpPort + " 分发，异步发送线程=" + otaThreads + ")");

        // 先加载插件再进入监听：插件 onEnable 需要尽早拿到上下文
        loadPlugins();

        // 启动更新用 HTTP 服务（与游戏 TCP 端口并存，端口独立）
        startHttpServer();

        // 缓冲说明：整图已按 8192 字符分片，单个对象/分片/聊天包序列化后远小于 1MB，
        // 2MB 读写缓冲足够；原 10MB(9999999) 每连接占两块大缓冲，10 连接约 200MB，下调可明显省内存。
        // SafeServer：重写 run() 兜住网络线程异常并继续服务，单个坏包不再让整个服务器网络循环退出停摆。
        server = new SafeServer(2 * 1024 * 1024, 2 * 1024 * 1024);

        // Kryo 注册顺序必须与客户端 loadKryonet() 完全一致
        server.getKryo().register(TextChat.class);
        server.getKryo().register(layerhistory.class);
        server.getKryo().register(PlayerState.class);
        server.getKryo().register(command.class);

        server.addListener(new Listener() {
            @Override
            public void connected(Connection connection) {
                onConnected(connection);
            }

            @Override
            public void disconnected(Connection connection) {
                onDisconnected(connection);
            }

            @Override
            public void received(Connection connection, Object object) {
                onReceived(connection, object);
            }
        });

        // 停机钩子：先停用插件（通知其释放资源），再关闭服务器与守护循环
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            disablePlugins();
            stopHttpServer();
            if (otaPool != null) {
                otaPool.shutdownNow();
                otaPool = null;
            }
            try {
                if (server != null) server.stop();
            } catch (Exception ignore) {
            }
            log("服务器已停止。");
        }));

        server.start();
        if (udpPort > 0) {
            server.bind(tcpPort, udpPort);
        } else {
            server.bind(tcpPort);
        }
        log("服务器已启动，正在监听端口。按 Ctrl+C 停止。");

        // 主线程改为守护循环：每秒做一次清扫（到期踢出 / 空房与空闲房间回收）
        while (running) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                break;
            }
            try {
                housekeep();
            } catch (Throwable t) {
                log("[S] 守护循环异常: " + t);
            }
        }
    }

    // ====================================================================
    //  插件系统：目录扫描 + 自动加载 + 上下文
    // ====================================================================
    /**
     * 扫描插件目录下所有 .jar，找出实现 {@link NotTiledPlugin} 的类并启用。
     * 单个插件出错只记录日志，绝不中断服务器主流程。
     */
    private void loadPlugins() {
        if (!enablePlugins) {
            log("[plugin] 插件加载已禁用（enablePlugins=0），跳过 " + new File(pluginsDir).getAbsolutePath());
            return;
        }
        File dir = new File(pluginsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log("[plugin] 插件目录不存在: " + dir.getAbsolutePath() + "（跳过插件加载）");
            return;
        }
        warnIfPluginsDirInsecure(dir);
        File[] jars = dir.listFiles((d, n) -> n != null && n.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            log("[plugin] 插件目录 " + dir.getAbsolutePath() + " 中没有 .jar 文件");
            return;
        }
        for (File jar : jars) {
            URLClassLoader loader = null;
            List<NotTiledPlugin> found = new ArrayList<>();
            try {
                // 父级 = 服务器类加载器：插件可直接引用协议类与插件 API
                loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, getClass().getClassLoader());
                try (JarFile jf = new JarFile(jar)) {
                    Enumeration<JarEntry> en = jf.entries();
                    while (en.hasMoreElements()) {
                        JarEntry e = en.nextElement();
                        String nm = e.getName();
                        // 只看顶层 .class（跳过内部类 / META-INF / module-info）
                        if (!nm.endsWith(".class") || nm.contains("$")
                                || nm.startsWith("META-INF") || nm.startsWith("module-info")) {
                            continue;
                        }
                        String cn = nm.substring(0, nm.length() - 6).replace('/', '.');
                        try {
                            Class<?> clz = Class.forName(cn, false, loader);
                            if (clz == NotTiledPlugin.class) continue;
                            if (!NotTiledPlugin.class.isAssignableFrom(clz)) continue;
                            if (clz.isInterface() || Modifier.isAbstract(clz.getModifiers())) continue;
                            NotTiledPlugin inst = (NotTiledPlugin) clz.getDeclaredConstructor().newInstance();
                            found.add(inst);
                        } catch (Throwable t) {
                            // 类加载失败/不是插件：忽略该 class，继续扫描 jar 内其余类
                        }
                    }
                }
                if (found.isEmpty()) {
                    log("[plugin] " + jar.getName() + " 中未发现 NotTiledPlugin 实现，跳过");
                    continue;
                }
                // 保留 loader 引用，防止插件类在运行期被卸载
                pluginLoaders.add(loader);
                loader = null;
                for (NotTiledPlugin inst : found) {
                    plugins.add(inst);
                    try {
                        inst.onEnable(pluginContext);
                        log("[plugin] 已加载插件: " + inst.name() + "（来源 " + jar.getName() + "）");
                    } catch (Throwable t) {
                        log("[plugin] 插件 " + inst.name() + " onEnable 异常: " + t);
                    }
                }
            } catch (Throwable t) {
                log("[plugin] 加载 " + jar.getName() + " 失败: " + t);
            } finally {
                // 若未能保留 loader（无插件/加载失败），立即释放
                if (loader != null) {
                    try {
                        loader.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /**
     * 插件目录安全提示：插件以内置代码同等的权限运行（等同本地代码执行面），
     * 任何能写入该目录的人都可以执行任意代码，故该目录必须仅管理员可写。
     */
    private void warnIfPluginsDirInsecure(File dir) {
        boolean warn = false;
        try {
            try {
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                        java.nio.file.Files.getPosixFilePermissions(dir.toPath());
                if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                        || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                    warn = true;
                }
            } catch (UnsupportedOperationException notPosix) {
                // 非 POSIX（如 Windows）：无法可靠判断“他人”权限，提示管理员自行确认访问控制
                warn = dir.canWrite();
            }
        } catch (Throwable ignore) {
        }
        if (warn) {
            log("[plugin] 安全提示：插件以服务器同等权限运行（等同代码执行面），"
                    + "请确保插件目录仅管理员可写：" + dir.getAbsolutePath());
        }
    }

    /** 停用全部插件并释放其 ClassLoader（服务器关闭时调用）。 */
    private void disablePlugins() {
        for (int i = plugins.size() - 1; i >= 0; i--) {
            NotTiledPlugin pl = plugins.get(i);
            try {
                pl.onDisable();
                log("[plugin] 已停用插件: " + pl.name());
            } catch (Throwable t) {
                log("[plugin] 插件 " + pl.name() + " onDisable 异常: " + t);
            }
        }
        plugins.clear();
        for (URLClassLoader l : pluginLoaders) {
            try {
                l.close();
            } catch (Exception ignore) {
            }
        }
        pluginLoaders.clear();
    }

    /** 构造插件运行期上下文（匿名实现），把服务器能力以最小接口暴露给插件。 */
    private PluginContext buildPluginContext() {
        return new PluginContext() {
            @Override
            public String getServerName() {
                return "Re-NotTiled Server";
            }

            @Override
            public int getOnlineCount() {
                synchronized (lock) {
                    return peers.size();
                }
            }

            @Override
            public List<PlayerInfo> getRoomPlayers(String room) {
                synchronized (lock) {
                    List<PlayerInfo> out = new ArrayList<>();
                    for (Peer m : peers.values()) {
                        if (m.room != null && m.room.equalsIgnoreCase(room == null ? "" : room)) {
                            out.add(new PlayerInfo(m.id, m.room, m.creator));
                        }
                    }
                    return out;
                }
            }

            @Override
            public void replyToPlayer(String playerId, String text) {
                synchronized (lock) {
                    Peer m = findPeerById(playerId);
                    if (m != null) sendSystemChat(m.conn, text);
                }
            }

            @Override
            public void sendToRoom(String room, String text) {
                synchronized (lock) {
                    List<Peer> list = roomMembers(room);
                    for (Peer m : list) sendSystemChat(m.conn, text);
                }
            }

            @Override
            public void sendToAll(String text) {
                synchronized (lock) {
                    for (Peer m : peers.values()) sendSystemChat(m.conn, text);
                }
            }

            @Override
            public void log(String line) {
                ReNotTiledServer.log("[plugin] " + line);
            }
        };
    }

    /** 给某个连接发送一条系统聊天消息（发送方显示为「系统」）。 */
    private void sendSystemChat(Connection cc, String text) {
        TextChat out = new TextChat();
        out.sender = "系统";
        out.text = text;
        send(cc, out);
    }

    // ====================================================================
    //  连接管理
    // ====================================================================
    private void onConnected(Connection c) {
        synchronized (lock) {
            // 频率限速：同一 IP 在 10 秒窗口内新建连接超过阈值则拒绝，防恶意刷连接占满名额
            String ip = "";
            try {
                if (c.getRemoteAddressTCP() != null && c.getRemoteAddressTCP().getAddress() != null)
                    ip = c.getRemoteAddressTCP().getAddress().getHostAddress();
            } catch (Exception ignore) {}
            if (newConnLimitPer10s > 0 && ip != null && !ip.isEmpty()) {
                long now10 = System.currentTimeMillis();
                Deque<Long> q = ipNewConns.computeIfAbsent(ip, k -> new ArrayDeque<>());
                // 清掉 10 秒前的记录，只统计窗口内
                while (!q.isEmpty() && q.peekFirst() < now10 - 10_000L) q.pollFirst();
                if (q.size() >= newConnLimitPer10s) {
                    log("[S] 拒绝连接：IP " + ip + " 10 秒内新建连接过频 (" + q.size()
                            + "/" + newConnLimitPer10s + ")");
                    closeAfterFlush(c);
                    return;
                }
                q.addLast(now10);
            }
            if (peers.size() >= maxConnections) {
                log("[S] 拒绝新连接：服务器人数已满 (" + peers.size() + "/" + maxConnections + ") "
                        + c.getRemoteAddressTCP());
                closeAfterFlush(c);
                return;
            }
            Peer np = new Peer(c);
            np.remoteIp = ip;
            peers.put(c.getID(), np);
            log("[S] 新连接 " + c.getID() + " 来自 " + c.getRemoteAddressTCP()
                    + " (" + peers.size() + "/" + maxConnections + ")");
            // 连接即推送当前房间快照，客户端无需等待注册
            send(c, makeRoomList());
        }
    }

    /**
     * 延迟关闭连接：让已在写队列中的 RegisterTCP 先刷出，避免客户端误报
     * 「Connected, but timed out during TCP registration」。拒绝连接发生在网络线程上，
     * 严禁在此 sleep，故交给一个短命守护线程在约 500ms 后关闭。
     */
    private void closeAfterFlush(Connection c) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(500L); } catch (InterruptedException ignore) {}
            try { c.close(); } catch (Exception ignore) {}
        }, "graceful-close");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 连接断开统一收尾：
     * <ul>
     *   <li>房主（creator）断开：若房内还有其他成员且启用了宽限（hostGraceSec>0），房间进入托管，
     *       其余成员继续协作；否则立即解散整个房间并通知成员；</li>
     *   <li>普通成员断开 -> 仅通知房内其余成员离开；</li>
     *   <li>最后统一向全服广播一次最新房间列表。</li>
     * </ul>
     */
    private void onDisconnected(Connection c) {
        command msg = null;
        List<Connection> notify = new ArrayList<>();
        String awayNotice = null;                       // 房主离线托管提示（锁外发送）
        List<Connection> noticeTargets = new ArrayList<>();
        synchronized (lock) {
            Peer p = peers.remove(c.getID());
            if (p == null) {
                log("[S] 连接 " + c.getID() + " 断开（不在会话表中，属半连接/异常连接，不影响服务器）");
                return;
            }
            // 先快照该连接的关键信息（后续房间解散/成员离开会清理 p 的房间字段）
            long heldSec = (System.currentTimeMillis() - p.connectedAt) / 1000L;
            String wasReg = p.registered ? "已注册" : "未注册";
            String wasRoom = (p.room == null || p.room.isEmpty()) ? "无" : p.room;
            boolean wasCreator = p.creator;
            String wasIp = (p.remoteIp == null || p.remoteIp.isEmpty()) ? "?" : p.remoteIp;
            if (p.creator && !p.room.isEmpty()) {
                List<Peer> stay = roomMembers(p.room);
                if (hostGraceSec > 0 && !stay.isEmpty()) {
                    // 房主意外断线：房间进入宽限托管，其余成员留在房内继续协作
                    hostLeaves.put(normRoom(p.room), new RoomPreserve(p.room, parseNickname(p.id),
                            p.pass, p.map, p.maxPlayers,
                            System.currentTimeMillis() + hostGraceSec * 1000L));
                    // 房主离线托管：房主不在则无人可解除禁编，自动解除该房全部禁编，避免成员被永久锁死
                    Set<String> bannedSet = roomBanned.remove(normRoom(p.room));
                    if (bannedSet != null && !bannedSet.isEmpty()) {
                        for (Peer m : stay) {
                            if (m.id != null && bannedSet.contains(m.id)) {
                                command ub = new command();
                                ub.command = "memberEditBanned";
                                ub.room = p.room;
                                ub.data = "0";
                                send(m.conn, ub);
                            }
                        }
                        log("[S] 房主离线托管，已解除房间[" + p.room + "] " + bannedSet.size() + " 名成员的禁编");
                    }
                    msg = new command();
                    msg.command = "leaveInformation";
                    msg.room = p.room;
                    msg.data = p.id;
                    for (Peer m : stay) {
                        if (m.conn != c) notify.add(m.conn);
                    }
                    awayNotice = "房主暂时离线，房间将保留 " + hostGraceSec
                            + " 秒，队友可继续编辑；房主重连后自动恢复。";
                    for (Peer m : stay) {
                        if (m.conn != c) noticeTargets.add(m.conn);
                    }
                    pushRoomMembers(p.room);   // 房主已不在 peers，刷新后从成员名单移除
                    log("[S] 房主离线，房间进入宽限期托管: " + p.room
                            + "（" + hostGraceSec + " 秒，剩余成员 " + stay.size() + " 人）");
                } else {
                    // 房主离开（房内无人可保留 / 宽限关闭）-> 立即解散
                    msg = new command();
                    msg.command = "roomDestroyed";
                    msg.room = p.room;
                    for (Peer m : stay) {
                        resetPeerRoom(m);
                        if (m.conn != c) notify.add(m.conn);
                    }
                    log("[S] 房主离开，房间已解散: " + p.room);
                    roomBanned.remove(normRoom(p.room));   // 房间已解散，清掉禁编名单
                }
            } else if (!p.room.isEmpty()) {
                // 普通成员离开 -> 通知房内其余人
                String roomLeft = p.room;   // 先快照房间名（resetPeerRoom 会清空 p.room）
                msg = new command();
                msg.command = "leaveInformation";
                msg.room = roomLeft;
                msg.data = p.id;
                onMemberLeftLocked(roomLeft, p.id);   // 清理投票/推送授权（发起人或目标离开则作废投票）
                resetPeerRoom(p);
                for (Peer m : roomMembers(roomLeft)) {
                    if (m.conn != c) notify.add(m.conn);
                }
                log("[S] 成员离开房间: " + roomLeft + "（" + p.id + "）");
                pushRoomMembers(roomLeft);   // 刷新房内剩余成员名单（若已空会自动清理禁编名单/密码/上次地图）
                broadcastRoomList();         // 常驻房空房时同步房间列表（即时清除密码与上次地图名）
            }
            log("[S] 连接 " + c.getID() + " 断开: " + wasReg + " 维持" + heldSec
                    + "秒 房间[" + wasRoom + "] 房主=" + (wasCreator ? "是" : "否")
                    + " IP=" + wasIp + "，当前在线 " + peers.size() + "/" + maxConnections);
        }
        if (msg != null) {
            for (Connection cc : notify) send(cc, msg);
        }
        if (awayNotice != null) {
            TextChat tc = new TextChat();
            tc.sender = "系统";
            tc.text = awayNotice;
            for (Connection cc : noticeTargets) send(cc, tc);
        }
        broadcastRoomList();
    }

    /** 接收统一入口：任何到达的对象都会刷新发送者活跃时间（供空闲房间判定），再按类型分发。 */
    private void onReceived(Connection c, Object object) {
        synchronized (lock) {
            Peer p = peers.get(c.getID());
            if (p != null) p.lastActive = System.currentTimeMillis();
        }
        if (object instanceof TextChat) {
            handleChat(c, (TextChat) object);
        } else if (object instanceof command) {
            handleCommand(c, (command) object);
        } else if (object instanceof layerhistory || object instanceof PlayerState) {
            // 绘画 / 玩家状态：按房间转发给除自己外的成员
            synchronized (lock) {
                Peer p = peers.get(c.getID());
                if (p == null || p.room.isEmpty()) return;
                // 禁编成员：丢弃绘画数据（layerhistory）；玩家位置 PlayerState 不受影响
                if (object instanceof layerhistory && isEditBannedLocked(p)) {
                    log("[S] 已丢弃被禁编成员 " + p.id + " 的绘画数据（房间 " + p.room + "）");
                    return;
                }
                List<Peer> list = roomMembers(p.room);
                for (Peer m : list) {
                    if (m.conn != c) send(m.conn, object);
                }
            }
        }
    }

    // ====================================================================
    //  聊天管线：插件优先，未知 /命令回执，普通消息按房间转发（保留 sender）
    // ====================================================================
    /**
     * 处理一条聊天消息（TextChat）。
     * 流程：
     * <ol>
     *   <li>把玩家快照与昵称交给所有已加载插件依次 onChat；任意插件返回 true 即消费，不再转发；</li>
     *   <li>以 / 开头的文本视为命令：没有插件消费时由服务器回一句「未知命令」且不广播；</li>
     *   <li>普通消息在房间内转发（保留原始 sender / text，兼容新旧客户端展示）。</li>
     * </ol>
     */
    private void handleChat(Connection c, TextChat chat) {
        Peer p;
        synchronized (lock) {
            p = peers.get(c.getID());
        }
        if (p == null || chat == null) return;
        String text = (chat.text == null) ? "" : chat.text;
        String senderRaw = (chat.sender == null) ? "" : chat.sender.trim();
        // 展示名：优先用 sender 字段；旧客户端未填则退化为玩家注册 ID
        String displayName = senderRaw.isEmpty() ? (p.id.isEmpty() ? "匿名" : p.id) : senderRaw;
        PlayerInfo who = new PlayerInfo(p.id, p.room, p.creator);

        // 1) 插件先行（可能在锁外做网络发送，因此这里不持有 lock）
        boolean consumed = false;
        boolean commandMode = text.startsWith("/");
        for (NotTiledPlugin pl : plugins) {
            try {
                if (pl.onChat(who, displayName, text)) {
                    consumed = true;
                    break;
                }
            } catch (Throwable t) {
                log("[plugin] 插件 " + pl.name() + " onChat 异常: " + t);
            }
        }
        if (consumed) return;

        // 2) 服务器不认识的命令 -> 回执，不广播
        if (commandMode) {
            sendSystemChat(c, "未知命令: " + text + "（输入 /help 查看服务器可用命令）");
            return;
        }

        // 3) 普通聊天：按房间转发（排除发送者本人，保留 sender 供客户端显示昵称）
        synchronized (lock) {
            Peer cur = peers.get(c.getID());
            if (cur == null) return;
            log("[CHAT] 房间[" + cur.room + "] " + displayName + ": " + text);
            if (cur.room.isEmpty()) return; // 未入房则无房间可转发
            TextChat out = new TextChat();
            out.sender = chat.sender;   // 原样保留昵称（可为空，旧客户端整行内联）
            out.text = text;
            List<Peer> list = roomMembers(cur.room);
            for (Peer m : list) {
                if (m.conn != c) send(m.conn, out);
            }
        }
    }

    // ====================================================================
    //  命令处理
    // ====================================================================
    private void handleCommand(Connection c, command cmd) {
        if (cmd == null || cmd.command == null) return;
        switch (cmd.command) {

            // ---- 注册：客户端连上后上报自身 ID ----
            case "registerID":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null) return;
                    p.registered = true;             // 标记已完成注册（僵尸清理依据）
                    p.id = cmd.data == null ? "" : cmd.data;
                    p.nickname = parseNickname( p.id );
                    p.room = "";
                    p.creator = false;
                    p.pass = "";
                    p.map = "";
                    p.pushChars = 0;
                    p.pushBlocked = false;
                    p.closeAt = 0;
                    command ack = new command();
                    ack.from = p.id;
                    ack.command = "registered";
                    send(c, ack);
                    log("[S] 玩家注册成功: " + p.id);
                    // 房主断线宽限恢复：同昵称重连自动回房并收回房主身份
                    tryHostRestoreLocked(p, c);
                    send(c, makeRoomList());
                }
                break;

            // ---- 客户端上报自身版本号：房间列表展示 + 同版本才能进房的校验依据 ----
            case "clientVersion":
                synchronized (lock) {
                    Peer cvp = peers.get(c.getID());
                    if (cvp == null) break;
                    String cvd = cmd.data == null ? "" : cmd.data.trim();
                    int cvsep = cvd.indexOf('|');
                    if (cvsep >= 0) {
                        cvp.versionCode = parseIntSafe(cvd.substring(0, cvsep).trim(), 0);
                        cvp.versionName = cvd.substring(cvsep + 1).trim();
                    } else {
                        cvp.versionCode = parseIntSafe(cvd, 0);
                    }
                    log("[S] 客户端版本上报: " + cvp.id + " versionCode=" + cvp.versionCode
                            + " versionName=" + cvp.versionName);
                    broadcastRoomList();
                }
                break;

            // ---- 房主禁编/解禁成员编辑（禁绘画/对象增删改，不禁聊天） ----
            case "memberBan":
            case "memberUnban":
                synchronized (lock) {
                    Peer hp = peers.get(c.getID());
                    if (hp == null || !hp.creator || hp.room == null || hp.room.isEmpty()) break;
                    String tRoom = cmd.room == null ? "" : cmd.room.trim();
                    String targetId = cmd.data == null ? "" : cmd.data.trim();
                    if (tRoom.isEmpty() || targetId.isEmpty() || !hp.room.equalsIgnoreCase(tRoom)) break;
                    Peer t = findPeerById(targetId);
                    if (t == null || t.creator || !t.room.equalsIgnoreCase(tRoom)) {
                        log("[S] 禁编失败：目标不在房间或为房主 " + targetId);
                        break;
                    }
                    boolean unban = cmd.command.equalsIgnoreCase("memberUnban");
                    String rk = normRoom(tRoom);
                    if (unban) {
                        Set<String> s = roomBanned.get(rk);
                        if (s != null) s.remove(t.id);
                        log("[S] 房主 " + hp.id + " 恢复成员编辑: " + t.id + "（房间 " + tRoom + "）");
                    } else {
                        roomBanned.computeIfAbsent(rk, k -> new HashSet<>()).add(t.id);
                        log("[S] 房主 " + hp.id + " 禁编成员: " + t.id + "（房间 " + tRoom + "）");
                    }
                    command nt = new command();
                    nt.command = "memberEditBanned";
                    nt.room = tRoom;
                    nt.data = unban ? "0" : "1";   // 1=已被禁编 0=已恢复
                    send(t.conn, nt);
                    pushRoomMembers(tRoom);
                }
                break;

            // ---- 发起投票（仅常驻房间）：推送更新 / 禁编 / 解除禁编，过半数通过 ----
            case "voteStart":
                synchronized (lock) {
                    Peer vp = peers.get(c.getID());
                    if (vp == null || vp.room == null || vp.room.isEmpty()) break;
                    String vroom = cmd.room == null ? "" : cmd.room;
                    if (!vp.room.equalsIgnoreCase(vroom)) break;
                    if (!isLobbyRoom(vp.room)) {
                        sendSystemChat(c, "仅常驻房间支持投票。");
                        break;
                    }
                    String rk = normRoom(vp.room);
                    Vote running = roomVotes.get(rk);
                    if (running != null && running.deadline > System.currentTimeMillis()) {
                        sendSystemChat(c, "当前已有投票进行中，请等待结束。");
                        break;
                    }
                    String raw = cmd.data == null ? "" : cmd.data;
                    String vtype;
                    String vtarget = "";
                    int sep = raw.indexOf(command.ROOM_SEP);
                    if (sep >= 0) {
                        vtype = raw.substring(0, sep).trim().toUpperCase();
                        vtarget = raw.substring(sep + command.ROOM_SEP.length()).trim();
                    } else {
                        vtype = raw.trim().toUpperCase();
                    }
                    if (!vtype.equals("PUSH") && !vtype.equals("BAN") && !vtype.equals("UNBAN")) {
                        sendSystemChat(c, "无效的投票类型。");
                        break;
                    }
                    if (vtype.equals("BAN") || vtype.equals("UNBAN")) {
                        Peer t = findPeerById(vtarget);
                        // 允许被禁编者本人发起「解除自己」的投票（他人退出后也能自救）；但禁止对自己发起禁编
                        boolean selfTarget = (t != null && t.id != null && t.id.equalsIgnoreCase(vp.id));
                        if (t == null || t.room == null || !t.room.equalsIgnoreCase(vp.room)) {
                            sendSystemChat(c, "投票目标无效。");
                            break;
                        }
                        if (selfTarget && !vtype.equals("UNBAN")) {
                            sendSystemChat(c, "投票目标无效。");
                            break;
                        }
                        boolean banned = isEditBannedLocked(t);
                        if (vtype.equals("BAN") && banned) { sendSystemChat(c, "该成员已被禁编。"); break; }
                        if (vtype.equals("UNBAN") && !banned) { sendSystemChat(c, "该成员未被禁编。"); break; }
                    }
                    List<Peer> members = roomMembers(vp.room);
                    int threshold = Math.max(1, members.size()) / 2 + 1;
                    Vote v = new Vote(vp.room, vtype, vtarget, vp.id, threshold,
                            System.currentTimeMillis() + VOTE_TIMEOUT_MS);
                    v.yes.add(vp.id);   // 发起人默认同意
                    roomVotes.put(rk, v);
                    command vs = new command();
                    vs.command = "voteStart";
                    vs.room = vp.room;
                    vs.from = vp.id;
                    vs.data = vtype + command.ROOM_SEP + vtarget + command.ROOM_SEP
                            + threshold + command.ROOM_SEP + (VOTE_TIMEOUT_MS / 1000);
                    for (Peer m : members) send(m.conn, vs);
                    log("[S] 投票发起: 房间[" + vp.room + "] 类型=" + vtype + " 目标=" + vtarget
                            + " 发起=" + vp.id + " 阈值=" + threshold);
                    if (v.yes.size() >= threshold) settleVoteLocked(vp.room, rk, true, "passed");
                }
                break;

            // ---- 投票表决（仅常驻房间）：data=1 同意 / 0 反对 ----
            case "voteCast":
                synchronized (lock) {
                    Peer cp = peers.get(c.getID());
                    if (cp == null || cp.room == null || cp.room.isEmpty()) break;
                    String rk = normRoom(cp.room);
                    Vote v = roomVotes.get(rk);
                    if (v == null || v.deadline <= System.currentTimeMillis()) break;
                    boolean agree = cmd.data != null && cmd.data.trim().equals("1");
                    v.yes.remove(cp.id);
                    v.no.remove(cp.id);
                    if (agree) v.yes.add(cp.id); else v.no.add(cp.id);
                    command vu = new command();
                    vu.command = "voteUpdate";
                    vu.room = cp.room;
                    vu.data = v.yes.size() + command.ROOM_SEP + v.no.size() + command.ROOM_SEP + v.threshold;
                    for (Peer m : roomMembers(cp.room)) send(m.conn, vu);
                    log("[S] 投票表决: 房间[" + cp.room + "] 成员=" + cp.id + " 同意=" + agree
                            + " 票数=" + v.yes.size() + "/" + v.threshold);
                    if (v.yes.size() >= v.threshold) settleVoteLocked(cp.room, rk, true, "passed");
                }
                break;

            // ---- 设置/取消房间密码：常驻房由管理员（第一个进入者）操作；普通房由房主操作 ----
            case "setRoomPass":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null || p.room == null || p.room.isEmpty()) break;
                    String room = cmd.room == null ? "" : cmd.room;
                    if (!p.room.equalsIgnoreCase(room)) break;
                    String np = cmd.data == null ? "" : cmd.data.trim();
                    if (isLobbyRoom(room)) {
                        Peer ctrl = lobbyControllerLocked(room);
                        if (ctrl == null || ctrl.id == null || !ctrl.id.equalsIgnoreCase(p.id)) {
                            sendSystemChat(c, "只有第一个进入房间的成员可以设置密码。");
                            break;
                        }
                        String rk = normRoom(room);
                        if (np.isEmpty()) lobbyPass.remove(rk);
                        else lobbyPass.put(rk, np);
                        log("[S] 常驻房密码" + (np.isEmpty() ? "已取消" : "已设置") + ": " + room);
                    } else {
                        // 普通房：仅房主可设置/取消房间密码（入房校验读取 creator.pass）
                        if (!p.creator) {
                            sendSystemChat(c, "只有房主可以设置房间密码。");
                            break;
                        }
                        p.pass = np;
                        log("[S] 房间密码" + (np.isEmpty() ? "已取消" : "已设置") + ": " + room + " 房主=" + p.id);
                    }
                    TextChat nt = new TextChat();
                    nt.sender = "系统";
                    nt.text = np.isEmpty() ? "房间密码已取消（恢复公开）。" : "房间已设置密码，新成员需密码加入。";
                    for (Peer m : roomMembers(room)) send(m.conn, nt);
                    broadcastRoomList();
                }
                break;

            // ---- 创建房间：校验名称重复 / 总房间数上限 / 玩家已入房，成功后记录密码与人数上限 ----
            case "createRoom":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null || p.id.isEmpty()) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    command reply = new command();
                    reply.command = "roomCreateFailed";
                    if (room.isEmpty() || !p.room.isEmpty()) {
                        send(c, reply);
                        break;
                    }
                    boolean exists = false;
                    int activeRooms = 0;
                    for (Peer m : peers.values()) {
                        if (m.creator && !m.room.isEmpty()) {
                            activeRooms++;
                            if (m.room.equalsIgnoreCase(room)) exists = true;
                        }
                    }
                    // 房间名正处于「房主断线宽限托管」中时同样视为已被占用（防同名牌分身复活撞车）
                    if (hostGraceSec > 0 && hostLeaves.containsKey(normRoom(room))) exists = true;
                    // 与常驻房间重名的房间不允许创建（防止同名牌分身）
                    if (isLobbyRoom(room)) exists = true;
                    if (exists || activeRooms >= maxRooms) {
                        send(c, reply);
                        log("[S] 房间创建失败 (重名=" + exists + " 活跃=" + activeRooms + "/" + maxRooms + "): " + room);
                    } else {
                        p.room = room;
                        p.creator = true;
                        p.joinedAt = System.currentTimeMillis();
                        // data 载荷 = 密码 [分隔符 人数上限]
                        p.pass = command.parseRoomPass(cmd.data);
                        int reqMax = command.parseRoomMax(cmd.data, maxPlayersPerRoom);
                        p.maxPlayers = Math.max(1, Math.min(reqMax, maxPlayersPerRoom));
                        p.map = "";
                        p.pushChars = 0;
                        p.pushBlocked = false;
                        reply.command = "roomCreateOK";
                        reply.room = room;
                        send(c, reply);
                        log("[S] 房间创建成功 (" + (activeRooms + 1) + "/" + maxRooms + "): " + room
                                + " 房主=" + p.id + (p.pass.isEmpty() ? "（公开房）" : "（已上锁）"));
                        pushRoomMembers(room);   // 房主入房：下发成员名单（此时仅房主一人）
                        broadcastRoomList();
                    }
                }
                break;

            // ---- 销毁房间：仅房主可发起；解散后房主留在大厅 ----
            case "destroyRoom":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    if (!p.creator || !p.room.equalsIgnoreCase(room)) {
                        log("[S] 忽略销毁请求（非房主发起）: " + room + " 来自 " + (p.id.isEmpty() ? "?" : p.id));
                        break;
                    }
                    if (isLobbyRoom(room)) {
                        log("[S] 忽略销毁请求（常驻房间不可解散）: " + room);
                        break;
                    }
                    disbandRoomLocked(room);
                    log("[S] 房间已销毁: " + room);
                    broadcastRoomList();
                }
                break;

            // ---- 加入房间：校验房间存在 / 人数是否满 / 密码是否一致（常驻房无房主；若管理员设了密码则需密码加入） ----
            case "joinRequest":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null || p.id.isEmpty()) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    command reply = new command();
                    reply.command = "joinRequestRejected";
                    reply.room = room;
                    if (room.isEmpty() || !p.room.isEmpty()) {
                        reply.data = "invalid";
                        send(c, reply);
                        break;
                    }
                    boolean lobby = isLobbyRoom(room);
                    Peer creator = null;
                    int size = 0;
                    for (Peer m : peers.values()) {
                        if (m.creator && m.room.equalsIgnoreCase(room)) creator = m;
                        if (m.room.equalsIgnoreCase(room)) size++;
                    }
                    String reason = null;
                    String providerId = null;   // 常驻房：被指定把地图定向下发给新人的成员
                    if (lobby) {
                        // 常驻房无房主：仅判满员；若管理员设置了密码则校验密码
                        if (size >= maxPlayersPerRoom) {
                            reason = "full";
                        } else {
                            String want = lobbyPass.get(normRoom(room));
                            String got = cmd.data == null ? "" : cmd.data;
                            if (want != null && !want.isEmpty() && !want.equals(got)) {
                                reason = "password";
                            } else {
                                Peer earliest = null;
                                for (Peer m : peers.values()) {
                                    if (m.room.equalsIgnoreCase(room) && m.conn != c) {
                                        if (earliest == null || m.joinedAt < earliest.joinedAt) earliest = m;
                                    }
                                }
                                if (earliest != null && earliest.id != null && !earliest.id.isEmpty()) providerId = earliest.id;
                            }
                        }
                    } else if (creator == null) {
                        reason = "missing";
                    } else {
                        int roomMax = (creator.maxPlayers > 0) ? creator.maxPlayers : maxPlayersPerRoom;
                        if (size >= roomMax) reason = "full";
                        else {
                            String want = creator.pass == null ? "" : creator.pass;
                            String got = cmd.data == null ? "" : cmd.data;
                            if (!want.isEmpty() && !want.equals(got)) reason = "password";
                        }
                    }
                    // 版本隔离：房间已有确定版本且申请人也上报了版本时，必须一致才能进入
                    if (reason == null) {
                        int roomVer = 0;
                        if (lobby) {
                            Peer lc = lobbyControllerLocked(room);
                            if (lc != null) roomVer = lc.versionCode;
                        } else if (creator != null) {
                            roomVer = creator.versionCode;
                        }
                        if (roomVer > 0 && p.versionCode > 0 && roomVer != p.versionCode) reason = "version";
                    }
                    if (reason != null) {
                        reply.data = reason;
                        send(c, reply);
                        log("[S] 加入被拒绝 (" + reason + "): " + room + " 来自 " + cmd.from);
                        break;
                    }
                    p.room = room;
                    p.creator = false;
                    p.joinedAt = System.currentTimeMillis();
                    reply.command = "joinRequestAccepted";
                    // 常驻房：回执 persistent，客户端据此启用投票 UI
                    reply.data = lobby ? "persistent" : "";
                    send(c, reply);
                    command jn = new command();
                    jn.command = "joinInformation";
                    jn.room = room;
                    jn.data = cmd.from;
                    List<Peer> list = roomMembers(room);
                    for (Peer m : list) send(m.conn, jn);
                    log("[S] " + cmd.from + " 加入房间: " + room + "（" + list.size() + "/"
                            + maxPlayersPerRoom + "）" + (lobby ? " [常驻]" : ""));
                    pushRoomMembers(room);   // 新成员与房内其余人都刷新成员名单
                    // 常驻房：指定一位已有成员向全房推送当前地图（新成员与已有成员统一为同一张图）。
                    // 服务器保持纯中继：为该成员临时授予整图推送权限，推送完成后自动收回。
                    if (lobby && providerId != null) {
                        Peer prov = findPeerById(providerId);
                        if (prov != null) {
                            pushGrants.computeIfAbsent(normRoom(room), k -> new HashSet<>()).add(providerId);
                            command pm = new command();
                            pm.command = "provideMapTo";
                            pm.room = room;
                            pm.from = "服务器";
                            pm.data = cmd.from;
                            send(prov.conn, pm);
                            log("[S] 已指定 " + providerId + " 向全房推送地图以同步新成员 " + cmd.from + "（常驻房 " + room + "）");
                        }
                    }
                    broadcastRoomList();
                }
                break;

            // ---- 申请加入：无需密码，向房主转发审批请求（房主批准后免密码入房） ----
            case "joinApply":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null || p.id.isEmpty()) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    command reply = new command();
                    reply.command = "joinRequestRejected";
                    reply.room = room;
                    if (room.isEmpty() || !p.room.isEmpty()) {
                        reply.data = "invalid";
                        send(c, reply);
                        break;
                    }
                    Peer creator = null;
                    int size = 0;
                    for (Peer m : peers.values()) {
                        if (m.creator && m.room.equalsIgnoreCase(room)) creator = m;
                        if (m.room.equalsIgnoreCase(room)) size++;
                    }
                    if (creator == null || creator.conn == null) {
                        // 无房主的房间（如常驻房）不支持申请加入，走密码通道
                        reply.data = "missing";
                        send(c, reply);
                        log("[S] 加入申请被拒(无房主): " + room + " 来自 " + p.id);
                        break;
                    }
                    int roomMax = (creator.maxPlayers > 0) ? creator.maxPlayers : maxPlayersPerRoom;
                    if (size >= roomMax) {
                        reply.data = "full";
                        send(c, reply);
                        break;
                    }
                    // 版本隔离：申请人与房主版本不一致时拒绝申请
                    if (creator.versionCode > 0 && p.versionCode > 0 && creator.versionCode != p.versionCode) {
                        reply.data = "version";
                        send(c, reply);
                        log("[S] 加入申请被拒(版本不一致): " + p.id + "(v" + p.versionCode + ") -> " + room
                                + "(v" + creator.versionCode + ")");
                        break;
                    }
                    pendingApply.computeIfAbsent(normRoom(room), k -> new LinkedHashMap<>())
                            .put(p.id, p.nickname == null ? "" : p.nickname);
                    command req = new command();
                    req.command = "joinApplyRequest";
                    req.room = room;
                    req.from = p.id;
                    req.data = p.nickname == null ? "" : p.nickname;
                    send(creator.conn, req);
                    log("[S] 加入申请已转交房主: " + p.id + " -> " + creator.id + " @ " + room);
                }
                break;

            // ---- 房主审批通过：申请人免密码入房 ----
            case "joinApprove":
                synchronized (lock) {
                    Peer hp = peers.get(c.getID());
                    if (hp == null || !hp.creator) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    if (!hp.room.equalsIgnoreCase(room)) return;
                    String targetId = cmd.data == null ? "" : cmd.data.trim();
                    Map<String, String> pend = pendingApply.get(normRoom(room));
                    if (targetId.isEmpty() || pend == null || !pend.containsKey(targetId)) {
                        log("[S] 审批失败：无待处理申请 " + targetId + " @" + room);
                        break;
                    }
                    Peer t = findPeerById(targetId);
                    pend.remove(targetId);
                    if (pend.isEmpty()) pendingApply.remove(normRoom(room));
                    if (t == null || t.conn == null || !t.room.isEmpty()) {
                        // 申请人已离开或已入房：忽略
                        break;
                    }
                    int size = roomMembers(room).size();
                    int roomMax = (hp.maxPlayers > 0) ? hp.maxPlayers : maxPlayersPerRoom;
                    if (size >= roomMax) {
                        command rej = new command();
                        rej.command = "joinRequestRejected";
                        rej.room = room;
                        rej.data = "full";
                        send(t.conn, rej);
                        break;
                    }
                    t.room = room;
                    t.creator = false;
                    t.joinedAt = System.currentTimeMillis();
                    command ok = new command();
                    ok.command = "joinRequestAccepted";
                    ok.room = room;
                    ok.data = "";
                    send(t.conn, ok);
                    command jn = new command();
                    jn.command = "joinInformation";
                    jn.room = room;
                    jn.data = targetId;
                    for (Peer m : roomMembers(room)) send(m.conn, jn);
                    log("[S] 房主 " + hp.id + " 批准 " + targetId + " 加入: " + room);
                    pushRoomMembers(room);
                    broadcastRoomList();
                }
                break;

            // ---- 房主审批拒绝：通知申请人 ----
            case "joinReject":
                synchronized (lock) {
                    Peer hp = peers.get(c.getID());
                    if (hp == null || !hp.creator) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    if (!hp.room.equalsIgnoreCase(room)) return;
                    String targetId = cmd.data == null ? "" : cmd.data.trim();
                    Map<String, String> pend = pendingApply.get(normRoom(room));
                    if (pend != null) {
                        pend.remove(targetId);
                        if (pend.isEmpty()) pendingApply.remove(normRoom(room));
                    }
                    Peer t = findPeerById(targetId);
                    if (t != null && t.room.isEmpty()) {
                        command rej = new command();
                        rej.command = "joinRequestRejected";
                        rej.room = room;
                        rej.data = "denied";
                        send(t.conn, rej);
                    }
                    log("[S] 房主 " + hp.id + " 拒绝 " + targetId + " 加入: " + room);
                }
                break;

            // ---- 主动离开房间 ----
            case "leaveRequest":
                synchronized (lock) {
                    Peer p = peers.get(c.getID());
                    if (p == null) return;
                    String room = cmd.room == null ? "" : cmd.room;
                    if (p.creator && !p.room.isEmpty() && p.room.equalsIgnoreCase(room)) {
                        // 房主若走 leave 通道退出，视同解散房间
                        disbandRoomLocked(room);
                        log("[S] 房主通过 leave 退出，房间已解散: " + room);
                        broadcastRoomList();
                        break;
                    }
                    onMemberLeftLocked(p.room, p.id);   // 清理投票/推送授权
                    resetPeerRoom(p);
                    command ack = new command();
                    ack.command = "leaveRequestAccepted";
                    ack.room = room;
                    send(c, ack);
                    command lv = new command();
                    lv.command = "leaveInformation";
                    lv.room = room;
                    lv.data = cmd.from;
                    List<Peer> list = roomMembers(room);
                    for (Peer m : list) {
                        if (m.conn != c) send(m.conn, lv);
                    }
                    log("[S] " + cmd.from + " 离开房间: " + room);
                    pushRoomMembers(room);   // 离开者已重置出房，刷新房内剩余成员名单
                    broadcastRoomList();
                }
                break;

            // ------------------------------------------------------------
            //  地图/绘画数据转发（含 10MB 推送上限保护）
            // ------------------------------------------------------------
            // 说明：整图推送由房主(creator)发起，包含「推送开始」与大量「数据分片」两类命令；
            // 服务器按房间转发的同时，对分片做字符累计。以下命令的字符均计入房主 pushChars：
            //   - startDataAll / startData（推送开始，重置计数）
            //   - dataAll / data（数据分片，累计计数）
            // 分片格式兼容旧客户端写法（startdata / startData / dataAll / data 大小写）。

            // 整图推送开始（广播给全房间）：重置计数器并解除封禁
            case "startDataAll":
                synchronized (lock) {
                    Peer sp = peers.get(c.getID());
                    if (sp == null) return;
                    if (!canPushLocked(sp)) break;   // 房主，或常驻房内经投票授权的成员
                    sp.pushChars = 0;
                    sp.pushBlocked = false;
                    sp.closeAt = 0;
                }
                relayRoomExclude(cmd.room, cmd, c);
                break;

            // 整图推送开始（定向发给单个目标，即新加入者拉图）：重置计数器并解除封禁
            // 允许：房主；或常驻房成员且目标在同一常驻房（无房主时下发地图）
            case "startdata":
            case "startData":
                synchronized (lock) {
                    Peer sp = peers.get(c.getID());
                    if (sp == null) return;
                    if (!canSendDirectedLocked(sp, cmd)) break;
                    sp.pushChars = 0;
                    sp.pushBlocked = false;
                    sp.closeAt = 0;
                    // 定向发送：命令 from 指定了目标接收者（新加入者），直接投递到该连接
                    Peer target = findPeerById(cmd.from);
                    if (target != null) {
                        send(target.conn, cmd);
                    } else {
                        relayRoomExclude(cmd.room, cmd, c);
                    }
                }
                break;

            // 整图数据分片（广播给全房间）：累计字符，超限即中止转发并准备踢出推送者
            case "dataAll":
                synchronized (lock) {
                    Peer sp = peers.get(c.getID());
                    if (sp == null || !canPushLocked(sp)) return;
                    if (!accumulatePush(c, cmd)) return;
                }
                relayRoomExclude(cmd.room, cmd, c);
                break;

            // 整图数据分片（定向发给单个目标）：房主累计字符；常驻房定向下发不计数
            case "data":
                synchronized (lock) {
                    Peer sp = peers.get(c.getID());
                    if (sp == null) return;
                    if (!canSendDirectedLocked(sp, cmd)) return;
                    if (sp.creator && !accumulatePush(c, cmd)) return;
                    Peer target = findPeerById(cmd.from);
                    if (target != null) {
                        send(target.conn, cmd);
                    } else {
                        relayRoomExclude(cmd.room, cmd, c);
                    }
                }
                break;

            // 画图/对象操作/地图尺寸/图层结构变更：房间内所有成员均可参与转发（不含发送者自己）；被禁编成员的操作直接丢弃
            case "draw":
            case "objOp":
            case "mapResize":
            case "layerOp":
                synchronized (lock) {
                    Peer dp = peers.get(c.getID());
                    if (dp != null && isEditBannedLocked(dp)) {
                        log("[S] 已丢弃被禁编成员 " + dp.id + " 的编辑操作（房间 " + dp.room + "）");
                        break;
                    }
                    relayRoomExclude(cmd.room, cmd, c);
                }
                break;

            // 地图同步检测/图块集结构变更：房间内转发（不做禁编过滤，被禁编成员也能参与比对）
            case "mapCheck":
            case "mapHash":
            case "tsetChanged":
                relayRoomExclude(cmd.room, cmd, c);
                break;

            // 房主/授权成员通知「地图已发给某人」-> 由服务器向目标补发 mapInformation（元数据，不占流量）
            case "readThisBoy":
                synchronized (lock) {
                    Peer rp = peers.get(c.getID());
                    if (rp == null) break;
                    if (!rp.creator && !canSendDirectedLocked(rp, cmd)) break;
                    Peer target = findPeerById(cmd.from);
                    command map = new command();
                    map.command = "mapInformation";
                    map.from = cmd.from;
                    map.room = cmd.room;
                    if (target != null) {
                        send(target.conn, map);
                    } else {
                        // 房间隔离：目标找不到时仅在房内转发（对方按 from==myID 过滤），绝不全服广播
                        relayRoomExclude(cmd.room, map, c);
                    }
                    log("[S] 地图信息已发送给: " + cmd.from);
                }
                break;

            // 房主/常驻房成员通知「地图已广播」-> 由服务器向全房间补发 mapInformationAll（元数据，不占流量）
            case "allReadThis":
                synchronized (lock) {
                    Peer ap = peers.get(c.getID());
                    if (ap == null) break;
                    if (!ap.creator && !canPushLocked(ap)) break;
                    // 常驻房整图推送完成：清除授权（下次推送需重新投票）
                    if (!ap.creator && ap.room != null && !ap.room.isEmpty()) {
                        Set<String> g = pushGrants.get(normRoom(ap.room));
                        if (g != null) {
                            g.remove(ap.id);
                            if (g.isEmpty()) pushGrants.remove(normRoom(ap.room));
                        }
                    }
                    command map = new command();
                    map.command = "mapInformationAll";
                    map.from = cmd.from;
                    map.room = cmd.room;
                    for (Peer m : roomMembers(cmd.room)) {
                        if (m.conn != c) send(m.conn, map);
                    }
                    log("[S] 地图信息(全房)已广播: " + cmd.room);
                }
                break;

            // 房主/常驻房成员上报地图名（数据不落服务器，仅用于房间列表展示）
            case "roomInfo":
                synchronized (lock) {
                    if (isLobbyRoom(cmd.room)) {
                        // 常驻房：允许任意成员上报，写入房间级 lobbyMap
                        Peer rp = peers.get(c.getID());
                        if (rp != null && rp.room != null && rp.room.equalsIgnoreCase(cmd.room)) {
                            lobbyMap.put(normRoom(cmd.room), cmd.data == null ? "" : cmd.data);
                            log("[S] 常驻房信息 room=" + cmd.room + " map=" + cmd.data);
                        }
                    } else {
                        for (Peer m : peers.values()) {
                            if (m.creator && m.room.equalsIgnoreCase(cmd.room)) {
                                m.map = cmd.data == null ? "" : cmd.data;
                                log("[S] 房间信息 room=" + cmd.room + " map=" + m.map);
                                break;
                            }
                        }
                    }
                    broadcastRoomList();
                }
                break;

            // 主动请求房间列表
            case "roomListRequest":
                synchronized (lock) {
                    send(c, makeRoomList());
                    log("[S] 房间列表已发送给连接 " + c.getID());
                }
                break;

            // 客户端主动断开前擦除记录
            case "disconnect":
                {
                    command dmsg = null;
                    List<Connection> dnotify = new ArrayList<>();
                    synchronized (lock) {
                        Peer p = peers.remove(c.getID());
                        if (p != null) {
                            onMemberLeftLocked(p.room, p.id);   // 清理投票/推送授权
                            clearPendingApplyFor(p.id);         // 清理该申请人待审批的加入申请
                            if (p.creator && p.room != null && !p.room.isEmpty() && !isLobbyRoom(p.room)) {
                                disbandRoomLocked(p.room);
                            } else if (p.room != null && !p.room.isEmpty()) {
                                // 普通成员/常驻房成员：通知房内其余成员离开，并刷新成员名单与房间列表，
                                // 避免「未经 leave 直接 disconnect」时其余成员名单/列表不刷新的问题
                                String roomLeft = p.room;
                                dmsg = new command();
                                dmsg.command = "leaveInformation";
                                dmsg.room = roomLeft;
                                dmsg.data = p.id;
                                resetPeerRoom(p);
                                for (Peer m : roomMembers(roomLeft)) {
                                    if (m.conn != c) dnotify.add(m.conn);
                                }
                                pushRoomMembers(roomLeft);
                                broadcastRoomList();
                            }
                        }
                        log("[S] 客户端已擦除 (disconnect): " + (p == null ? "unknown" : p.id));
                    }
                    if (dmsg != null) {
                        for (Connection cc : dnotify) send(cc, dmsg);
                    }
                }
                break;

            // ---- 单端口 OTA：通过游戏 TCP 连接分发更新（无需额外端口）----
            case "otaCheck":
                try { serveOtaVersion(c); } catch (Throwable t) { log("[ota] 版本查询异常: " + t); }
                break;
            case "otaFetch":
                try { serveOtaApk(c); } catch (Throwable t) { log("[ota] 下发异常: " + t); }
                break;

            default:
                log("[S] 未知命令: " + cmd.command);
        }
    }

    /**
     * 累计房主的地图推送字符数；超限时置位封禁并计划踢出。
     * 必须在持有 lock 时调用。
     *
     * @return true 表示可继续转发本片；false 表示已超限，调用方应丢弃本片。
     */
    private boolean accumulatePush(Connection c, command cmd) {
        Peer sp = peers.get(c.getID());
        if (!canPushLocked(sp)) return false;
        // 已封禁：后续分片一律丢弃（不转发、不累计），等待守护循环踢出
        if (sp.pushBlocked) return false;
        int inc = (cmd.data == null) ? 0 : cmd.data.length();
        sp.pushChars += inc;
        if (sp.pushChars > maxMapChars) {
            // 超限：停止转发并计划踢出房主
            sp.pushBlocked = true;
            sp.closeAt = System.currentTimeMillis() + 1000L; // 1 秒后由守护循环断开
            log("[S] 地图推送超限（累计 " + sp.pushChars + " 字符 > " + maxMapChars
                    + "）: 玩家 " + sp.id + " 将被断开（房间 " + sp.room + "）");
            // 向整个房间广播中文提示（含发送者自己，客户端据此弹窗）
            command kick = new command();
            kick.command = "mapRejectedTooBig";
            kick.room = sp.room;
            kick.data = "推送的地图超过服务器上限(" + (maxMapChars / 1024 / 1024)
                    + "MB)，已停止接收并断开连接。请压缩或精简地图后再试。";
            List<Peer> all = roomMembers(sp.room);
            for (Peer m : all) send(m.conn, kick);
            return false;
        }
        return true;
    }

    // ====================================================================
    //  守护循环：到期踢出 + 空房/空闲房间回收
    // ====================================================================
    /**
     * 每秒执行一次的后台清扫：
     * <ol>
     *   <li>把 pushBlocked / closeAt 已到期的连接断开（地图超限踢出）；</li>
     *   <li>清理僵尸连接：连上后长时间未 registerID 的连接断开；</li>
     *   <li>回收异常房间：房间名仍被占用但房主已不在（成员残留）-> 解散；</li>
     *   <li>回收空闲房间：房间内所有成员超过 roomIdleTimeoutMin 分钟无活动 -> 解散。</li>
     * </ol>
     */
    private void housekeep() {
        long now = System.currentTimeMillis();
        List<Connection> toKick = new ArrayList<>();
        List<String[]> toDisband = new ArrayList<>(); // {room, 原因}
        synchronized (lock) {
            // 1) 到期踢出（含地图超限房主）
            for (Peer m : peers.values()) {
                if (m.closeAt > 0 && m.closeAt <= now) {
                    toKick.add(m.conn);
                }
            }
            // 2) 僵尸连接清理：连上后超过 registerTimeoutSec 仍未 registerID 的断开（防挂机占连接名额）
            if (registerTimeoutSec > 0) {
                long zLimit = now - registerTimeoutSec * 1000L;
                for (Peer m : peers.values()) {
                    if (!m.registered && m.connectedAt > 0 && m.connectedAt < zLimit) {
                        log("[S] 僵尸连接清理：连接 " + m.conn.getID() + " (IP=" + m.remoteIp
                                + ") 超过 " + registerTimeoutSec + " 秒未注册，断开");
                        toKick.add(m.conn);
                    }
                }
            }
            // 2b) 房主断线宽限托管清扫：到期或房内已空的托管记录先行失效（随后按正常空房回收/自然消失）
            if (hostGraceSec > 0 && !hostLeaves.isEmpty()) {
                Iterator<Map.Entry<String, RoomPreserve>> hit = hostLeaves.entrySet().iterator();
                while (hit.hasNext()) {
                    Map.Entry<String, RoomPreserve> en = hit.next();
                    RoomPreserve rp = en.getValue();
                    if (rp == null) {
                        hit.remove();
                        continue;
                    }
                    boolean expired = rp.deadline <= now;
                    boolean emptied = roomMembers(rp.room).isEmpty();
                    if (expired || emptied) {
                        hit.remove();
                        if (expired && !emptied) {
                            log("[S] 房主离线超过宽限期(" + hostGraceSec
                                    + "秒)，房间将被解散: " + rp.room);
                        }
                    }
                }
            }

            // 3) 空房/空闲扫描：收集所有被引用且仍有成员的房间
            Map<String, List<Peer>> byRoom = new LinkedHashMap<>();
            for (Peer m : peers.values()) {
                if (m.room == null || m.room.isEmpty()) continue;
                byRoom.computeIfAbsent(m.room, k -> new ArrayList<>()).add(m);
            }
            for (Map.Entry<String, List<Peer>> e : byRoom.entrySet()) {
                String room = e.getKey();
                if (isLobbyRoom(room)) continue;   // 常驻房永不回收（可空房保留）
                List<Peer> members = e.getValue();
                boolean hasCreator = false;
                long lastActive = 0;
                for (Peer m : members) {
                    if (m.creator) hasCreator = true;
                    if (m.lastActive > lastActive) lastActive = m.lastActive;
                }
                // 3a) 空房防御：有成员但没有房主的异常房间 -> 解散
                //     例外：处于「房主断线宽限托管」的房间（hostLeaves 未过期）跳过，等待房主回来
                boolean preserved = hostGraceSec > 0 && hostLeaves.containsKey(normRoom(room));
                if (!hasCreator && !preserved) {
                    toDisband.add(new String[]{room, "空房（房主已不在）"});
                    continue;
                }
                if (preserved) continue;   // 宽限托管中：既不按空房回收，也不按空闲超时回收
                // 3b) 空闲超时：整个房间所有成员都长时间无任何消息 -> 解散
                if (roomIdleTimeoutMin > 0) {
                    long idleLimit = roomIdleTimeoutMin * 60_000L;
                    if (now - lastActive >= idleLimit) {
                        toDisband.add(new String[]{room, "空闲超过 " + roomIdleTimeoutMin + " 分钟"});
                    }
                }
            }
            // 3c) 投票超时结算：到期仍未达阈值则作废（收集后统一在锁内结算，避免并发修改）
            if (!roomVotes.isEmpty()) {
                List<String> expiredKeys = new ArrayList<>();
                for (Map.Entry<String, Vote> ve : roomVotes.entrySet()) {
                    Vote v = ve.getValue();
                    if (v == null || v.deadline <= now) expiredKeys.add(ve.getKey());
                }
                for (String k : expiredKeys) {
                    Vote v = roomVotes.get(k);
                    if (v == null) { roomVotes.remove(k); continue; }
                    log("[S] 投票超时: 房间[" + v.room + "] 类型=" + v.type);
                    settleVoteLocked(v.room, k, false, "timeout");
                }
            }
            // 4) 限流表清理：每次清扫都清掉早已过窗口的空 IP 记录（不影响正在限速的 IP），及时释放内存
            if (!ipNewConns.isEmpty()) {
                Iterator<Map.Entry<String, Deque<Long>>> it = ipNewConns.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Deque<Long>> en = it.next();
                    Deque<Long> dq = en.getValue();
                    while (!dq.isEmpty() && dq.peekFirst() < now - 10_000L) dq.pollFirst();
                    if (dq.isEmpty()) it.remove();
                }
            }
            // 5) 内存回收：及时释放「已无成员的房间」残留的附属状态，避免随运行时间累积
            reclaimEmptyRoomStateLocked();
        }
        // 更新 / OTA 限流表同样每秒清理过期记录，及时把内存交还 GC（原逻辑仅在表过大时才清理）
        synchronized (httpLock) { trimHttpWindows(now); }
        synchronized (otaLock) { trimOtaWindows(now); }
        // 锁外执行网络发送 / 断开，避免长时间占用锁
        for (Connection cc : toKick) {
            try {
                if (cc.isConnected()) {
                    log("[S] 守护循环断开连接（到期踢出）");
                    cc.close();
                }
            } catch (Exception ignore) {
            }
        }
        for (String[] d : toDisband) {
            synchronized (lock) {
                disbandRoomLocked(d[0]);
                log("[S] 房间回收(" + d[1] + ")，房间已解散: " + d[0]);
                broadcastRoomList();
            }
        }
    }

    /**
     * 内存回收：把「当前已无成员」的房间所残留的附属状态及时清掉，避免其随运行时间累积占用堆内存。
     * 覆盖禁编名单、推送授权、待审批申请、进行中投票；常驻房空房时额外清掉密码与地图名缓存。
     * 须在持有 lock 时调用。
     */
    private void reclaimEmptyRoomStateLocked() {
        // 常驻房：空房即清空其全部附属缓存（与「空房不显示地图名 / 密码」的语义保持一致）
        for (String lr : LOBBY_ROOMS) {
            if (!roomMembers(lr).isEmpty()) continue;
            String rk = normRoom(lr);
            roomBanned.remove(rk);
            pushGrants.remove(rk);
            pendingApply.remove(rk);
            roomVotes.remove(rk);
            lobbyPass.remove(rk);
            lobbyMap.remove(rk);
        }
        // 自建房：兜底清理「已无成员」房间的附属状态（正常解散路径已清，这里防止异常路径残留）
        pruneKeysOfEmptyRooms(roomBanned);
        pruneKeysOfEmptyRooms(pushGrants);
        pruneKeysOfEmptyRooms(pendingApply);
        pruneKeysOfEmptyRooms(roomVotes);
    }

    /** 移除所有「对应房间当前已无成员」的键（须在持有 lock 时调用）。 */
    private void pruneKeysOfEmptyRooms(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return;
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            String rk = it.next();
            if (rk == null || roomMembers(rk).isEmpty()) it.remove();
        }
    }

    /**
     * 解散指定房间：把所有成员重置回大厅，并向全员广播 roomDestroyed。
     * 必须在持有 lock 时调用。
     */
    private void disbandRoomLocked(String room) {
        if (room == null || room.isEmpty()) return;
        hostLeaves.remove(normRoom(room));   // 房间解散，清除房主断线宽限托管记录
        command rm = new command();
        rm.command = "roomDestroyed";
        rm.room = room;
        // 含发起者在内全员通知（客户端靠它重置 isCreateRoom/isJoinRoom）
        for (Peer m : roomMembers(room)) {
            resetPeerRoom(m);
            send(m.conn, rm);
        }
        roomBanned.remove(normRoom(room));   // 解散房间时同步清掉禁编名单
        roomVotes.remove(normRoom(room));    // 同步清掉进行中的投票
        pushGrants.remove(normRoom(room));   // 同步清掉推送授权
        pendingApply.remove(normRoom(room)); // 同步清掉待审批的加入申请
    }

    /** 重置某个 Peer 的房间状态（回到大厅）；须在持有 lock 时调用。 */
    private void resetPeerRoom(Peer m) {
        m.room = "";
        m.creator = false;
        m.pass = "";
        m.map = "";
        m.pushChars = 0;
        m.pushBlocked = false;
        m.closeAt = 0;
    }

    /**
     * 房主断线宽限恢复（须在持有 lock 时调用）：同昵称重连自动回到原房间并收回房主身份。
     * 客户端随后会收到 {@code roomRestored} 命令，恢复本端的房主界面状态。
     */
    private void tryHostRestoreLocked(Peer p, Connection c) {
        if (hostGraceSec <= 0 || p == null || p.nickname == null || p.nickname.isEmpty()) return;
        RoomPreserve rp = null;
        String rk = null;
        for (Map.Entry<String, RoomPreserve> en : hostLeaves.entrySet()) {
            if (en.getValue() != null && en.getValue().nick != null
                    && en.getValue().nick.equalsIgnoreCase(p.nickname)) {
                rp = en.getValue();
                rk = en.getKey();
                break;
            }
        }
        if (rp == null || rk == null) return;
        // 该房间若已存在同昵称在线房主（旧僵尸连接未清 / 重名玩家占位），不重复接管
        for (Peer m : roomMembers(rp.room)) {
            if (m.creator && m.id != null && !m.id.isEmpty()
                    && parseNickname(m.id).equalsIgnoreCase(p.nickname)) return;
        }
        hostLeaves.remove(rk);
        p.room = rp.room;
        p.creator = true;
        p.joinedAt = System.currentTimeMillis();
        p.pass = rp.pass;
        p.map = rp.map;
        p.maxPlayers = rp.maxPlayers;
        command ok = new command();
        ok.command = "roomRestored";
        ok.room = rp.room;
        ok.from = p.id;
        send(c, ok);
        pushRoomMembers(rp.room);
        broadcastRoomList();
        TextChat back = new TextChat();
        back.sender = "系统";
        back.text = "房主 " + p.nickname + " 重新上线，房间已恢复。";
        for (Peer m : roomMembers(rp.room)) {
            if (m.conn != c) send(m.conn, back);
        }
        log("[S] 房主重连并恢复房间: " + rp.room + "（房主 " + p.nickname + "）");
    }

    // ====================================================================
    //  工具方法
    // ====================================================================
    private Peer findPeerById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Peer m : peers.values()) {
            if (!m.id.isEmpty() && m.id.equalsIgnoreCase(id)) return m;
        }
        return null;
    }

    /** 从所有房间的待审批队列中移除某申请人的申请（须在持有 lock 时调用）。 */
    private void clearPendingApplyFor(String id) {
        if (id == null || id.isEmpty()) return;
        List<String> emptyKeys = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> en : pendingApply.entrySet()) {
            en.getValue().remove(id);
            if (en.getValue().isEmpty()) emptyKeys.add(en.getKey());
        }
        for (String k : emptyKeys) pendingApply.remove(k);
    }

    /** 判断某连接是否为当前房主（整图推送等仅房主可用） */
    private boolean isCreator(Connection c) {
        synchronized (lock) {
            Peer p = peers.get(c.getID());
            return p != null && p.creator;
        }
    }

    /** 返回某房间内所有成员（须在持有 lock 时调用） */
    private List<Peer> roomMembers(String room) {
        List<Peer> list = new ArrayList<>();
        if (room == null || room.isEmpty()) return list;
        for (Peer m : peers.values()) {
            if (m.room.equalsIgnoreCase(room)) list.add(m);
        }
        return list;
    }

    /** 房间名统一转小写键（房间名不区分大小写） */
    private static String normRoom(String room) {
        return room == null ? "" : room.trim().toLowerCase();
    }

    /** 判断是否为常驻房间（按不区分大小写的房间名比较）。 */
    private static boolean isLobbyRoom(String room) {
        if (room == null) return false;
        String r = normRoom(room);
        for (String s : LOBBY_ROOMS) {
            if (normRoom(s).equals(r)) return true;
        }
        return false;
    }

    /** 从注册 ID（形如 "昵称_1234567"）剥离尾段随机数字得到展示昵称；无法剥离时原样返回 */
    private static String parseNickname(String raw) {
        if (raw == null) return "";
        int u = raw.lastIndexOf('_');
        if (u > 0 && u < raw.length() - 1) {
            String tail = raw.substring(u + 1);
            if (tail.matches("\\d{1,10}")) return raw.substring(0, u);
        }
        return raw;
    }

    /** 判断某成员是否处于「禁编」状态（须持有 lock 时调用）。禁编只影响编辑转发，不影响聊天。 */
    private boolean isEditBannedLocked(Peer p) {
        if (p == null || p.room == null || p.room.isEmpty()) return false;
        Set<String> s = roomBanned.get(normRoom(p.room));
        return s != null && p.id != null && !p.id.isEmpty() && s.contains(p.id);
    }

    /** 返回常驻房间的「管理员」（即第一个进入房间、当前仍在房的成员；须持有 lock 时调用）。 */
    private Peer lobbyControllerLocked(String room) {
        if (room == null || room.isEmpty()) return null;
        Peer best = null;
        for (Peer m : peers.values()) {
            if (m.room != null && m.room.equalsIgnoreCase(room)) {
                if (best == null || m.joinedAt < best.joinedAt) best = m;
            }
        }
        return best;
    }

    /** 是否允许发起整图广播推送（须持有 lock 时调用）：房主，或常驻房内已获投票授权的成员。 */
    private boolean canPushLocked(Peer p) {
        if (p == null) return false;
        if (p.creator) return true;
        if (p.room != null && !p.room.isEmpty() && isLobbyRoom(p.room)) {
            Set<String> g = pushGrants.get(normRoom(p.room));
            return g != null && p.id != null && !p.id.isEmpty() && g.contains(p.id);
        }
        return false;
    }

    /** 是否允许向单个目标定向发送地图（须持有 lock 时调用）：房主，或常驻房成员且目标在同一常驻房。 */
    private boolean canSendDirectedLocked(Peer sender, command cmd) {
        if (sender == null || cmd == null) return false;
        if (sender.creator) return true;
        if (sender.room == null || sender.room.isEmpty() || !isLobbyRoom(sender.room)) return false;
        Peer target = findPeerById(cmd.from);
        return target != null && target.room != null && target.room.equalsIgnoreCase(sender.room);
    }

    /** 成员离开房间时的投票/授权清理（须持有 lock 时调用）：移除推送授权；发起人或目标离开则作废投票。 */
    private void onMemberLeftLocked(String room, String id) {
        if (room == null || room.isEmpty() || id == null || id.isEmpty()) return;
        String rk = normRoom(room);
        Set<String> g = pushGrants.get(rk);
        if (g != null) {
            g.remove(id);
            if (g.isEmpty()) pushGrants.remove(rk);
        }
        Vote v = roomVotes.get(rk);
        if (v != null) {
            v.yes.remove(id);
            v.no.remove(id);
            if (v.initiatorId.equalsIgnoreCase(id) || v.targetId.equalsIgnoreCase(id)) {
                settleVoteLocked(room, rk, false, "failed");
            }
        }
    }

    /**
     * 结算一次投票（须持有 lock 时调用）：通过时按类型生效（PUSH 授权推送 / BAN 禁编 / UNBAN 解除），
     * 并向房间广播 voteEnd，最后刷新成员名单。
     *
     * @param pass   true=通过；false=未通过（result 为 failed/timeout）
     * @param result 未通过时的原因（failed/timeout）
     */
    private void settleVoteLocked(String room, String rk, boolean pass, String result) {
        if (rk == null) return;
        Vote v = roomVotes.remove(rk);
        if (v == null) return;
        if (pass) {
            if ("PUSH".equals(v.type)) {
                pushGrants.computeIfAbsent(rk, k -> new HashSet<>()).add(v.initiatorId);
            } else if ("BAN".equals(v.type)) {
                roomBanned.computeIfAbsent(rk, k -> new HashSet<>()).add(v.targetId);
                Peer t = findPeerById(v.targetId);
                if (t != null && t.room != null && t.room.equalsIgnoreCase(room)) {
                    command nt = new command();
                    nt.command = "memberEditBanned";
                    nt.room = room;
                    nt.data = "1";
                    send(t.conn, nt);
                }
            } else if ("UNBAN".equals(v.type)) {
                Set<String> s = roomBanned.get(rk);
                if (s != null) s.remove(v.targetId);
                Peer t = findPeerById(v.targetId);
                if (t != null && t.room != null && t.room.equalsIgnoreCase(room)) {
                    command nt = new command();
                    nt.command = "memberEditBanned";
                    nt.room = room;
                    nt.data = "0";
                    send(t.conn, nt);
                }
            }
        }
        command ve = new command();
        ve.command = "voteEnd";
        ve.room = room;
        ve.from = v.initiatorId;
        ve.data = v.type + command.ROOM_SEP + v.targetId + command.ROOM_SEP + (pass ? "passed" : result);
        for (Peer m : roomMembers(room)) send(m.conn, ve);
        pushRoomMembers(room);
        log("[S] 投票结束: 房间[" + room + "] 类型=" + v.type + " 目标=" + v.targetId
                + " 结果=" + (pass ? "通过" : result) + " 同意=" + v.yes.size() + "/" + v.threshold);
    }

    /** 向房间全员下发当前成员名单（须持有 lock 时调用）。每行：成员ID\t昵称\t角色(host/member)\t禁编(1/0) */
    private void pushRoomMembers(String room) {
        List<Peer> list = roomMembers(room);
        String rk = normRoom(room);
        if (list.isEmpty()) {
            roomBanned.remove(rk);   // 房间已无人，顺带清理禁编名单
            pushGrants.remove(rk);   // 房间已无人，顺带清理推送授权
            lobbyMap.remove(rk);     // 房间已无人，清掉上次地图名（列表空房不显示）
            lobbyPass.remove(rk);    // 房间变空：取消密码，恢复公开
            return;
        }
        boolean lobby = isLobbyRoom(room);
        String ctrlId = null;
        if (lobby) {
            Peer ctrl = lobbyControllerLocked(room);
            if (ctrl != null) ctrlId = ctrl.id;
        }
        Set<String> banned = roomBanned.get(rk);
        StringBuilder sb = new StringBuilder();
        for (Peer m : list) {
            String role;
            if (lobby) role = (ctrlId != null && m.id != null && m.id.equalsIgnoreCase(ctrlId)) ? "admin" : "member";
            else role = m.creator ? "host" : "member";
            sb.append(m.id == null ? "" : m.id).append('\t')
              .append(m.nickname == null ? "" : m.nickname).append('\t')
              .append(role).append('\t')
              .append((banned != null && m.id != null && banned.contains(m.id)) ? "1" : "0").append('\n');
        }
        command out = new command();
        out.command = "roomMembers";
        out.room = room;
        out.data = sb.toString();
        for (Peer m : list) send(m.conn, out);
    }

    /**
     * 生成房间列表快照（须在持有 lock 时调用）。每行：name\towner\tmembers\tmax\tlocked\tmap\tsrv
     * 先输出全部常驻房间（无房主，0 人也在列表中，owner 显示「服务器」，srv=1），
     * 再输出房主创建且非空的房间（srv=0）。
     */
    private command makeRoomList() {
        command out = new command();
        out.command = "roomList";
        StringBuilder sb = new StringBuilder();
        // 1) 常驻房间：始终存在，可空房；空房时不显示上次地图名（避免残留旧地图）
        for (String lr : LOBBY_ROOMS) {
            int size = roomMembers(lr).size();
            String mp = (size > 0) ? lobbyMap.get(normRoom(lr)) : null;
            String lp = lobbyPass.get(normRoom(lr));
            int locked = (lp != null && !lp.isEmpty()) ? 1 : 0;
            Peer lctrl = lobbyControllerLocked(lr);   // 常驻房版本以当前管理员（最早进入者）为准
            String lver = (lctrl != null) ? lctrl.versionName : "";
            sb.append(lr).append('\t')
              .append("服务器").append('\t')
              .append(size).append('\t')
              .append(maxPlayersPerRoom).append('\t')
              .append(locked).append('\t')
              .append(mp == null ? "" : mp).append('\t')
              .append(1).append('\t')
              .append(lver == null ? "" : lver).append('\n');
        }
        // 2) 房主房间
        for (Peer m : peers.values()) {
            if (m.creator && !m.room.isEmpty()) {
                int size = 0;
                for (Peer x : peers.values()) {
                    if (x.room.equalsIgnoreCase(m.room)) size++;
                }
                int locked = (m.pass != null && !m.pass.isEmpty()) ? 1 : 0;
                int roomMax = (m.maxPlayers > 0) ? m.maxPlayers : maxPlayersPerRoom;
                sb.append(m.room).append('\t')
                  .append(m.id == null ? "" : m.id).append('\t')
                  .append(size).append('\t')
                  .append(roomMax).append('\t')
                  .append(locked).append('\t')
                  .append(m.map == null ? "" : m.map).append('\t')
                  .append(0).append('\t')
                  .append(m.versionName == null ? "" : m.versionName).append('\n');
            }
        }
        out.data = sb.toString();
        return out;
    }

    /**
     * 广播房间列表给所有在线连接（列表变化时调用；内容无变化则跳过，降低服务器推送流量）。
     */
    private void broadcastRoomList() {
        synchronized (lock) {
            command msg = makeRoomList();
            String cur = msg.data == null ? "" : msg.data;
            if (cur.equals(lastRoomList)) return;
            lastRoomList = cur;
            for (Connection cc : server.getConnections()) send(cc, msg);
        }
    }

    /** 房间内转发，排除指定连接（须在持有 lock 时调用，或该方法内部自行加锁） */
    private void relayRoomExclude(String room, Object o, Connection exclude) {
        synchronized (lock) {
            List<Peer> list = roomMembers(room);
            for (Peer m : list) {
                if (m.conn != exclude) send(m.conn, o);
            }
        }
    }

    private void broadcastAll(Object o) {
        for (Connection c : server.getConnections()) send(c, o);
    }

    private void send(Connection c, Object o) {
        try {
            if (c != null && c.isConnected()) c.sendTCP(o);
        } catch (Exception ignore) {
        }
    }

    // ====================================================================
    //  单端口 OTA：通过游戏 TCP 连接分发更新（不占用额外端口）
    // ====================================================================
    /** 取连接对端 IP（取不到返回 "?"）。 */
    private String connIp(Connection c) {
        try {
            if (c != null && c.getRemoteAddressTCP() != null && c.getRemoteAddressTCP().getAddress() != null)
                return c.getRemoteAddressTCP().getAddress().getHostAddress();
        } catch (Exception ignore) {}
        return "?";
    }

    /** OTA 版本查询每 IP 每分钟限速；超限返回 false。 */
    private boolean allowOtaReq(String ip) {
        if (updateReqLimitPerMin <= 0) return true;
        synchronized (otaLock) {
            long now = System.currentTimeMillis();
            Deque<Long> q = otaReqWindow.computeIfAbsent(ip, k -> new ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst() < now - 60_000L) q.pollFirst();
            boolean ok = q.size() < updateReqLimitPerMin;
            if (ok) q.addLast(now);
            trimOtaWindows(now);
            return ok;
        }
    }

    /** OTA 下载每 IP 每小时限次；超限返回 false。 */
    private boolean allowOtaApk(String ip) {
        if (updateApkLimitPerHour <= 0) return true;
        synchronized (otaLock) {
            long now = System.currentTimeMillis();
            Deque<Long> q = otaApkWindow.computeIfAbsent(ip, k -> new ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst() < now - 3600_000L) q.pollFirst();
            boolean ok = q.size() < updateApkLimitPerHour;
            if (ok) q.addLast(now);
            trimOtaWindows(now);
            return ok;
        }
    }

    /** 防止 OTA 限流表无限增长：过大时清理已过窗口的空 IP 记录。须在 otaLock 内调用。 */
    private void trimOtaWindows(long now) {
        if (!otaReqWindow.isEmpty()) {
            Iterator<Map.Entry<String, Deque<Long>>> it = otaReqWindow.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Deque<Long>> e = it.next();
                Deque<Long> dq = e.getValue();
                while (!dq.isEmpty() && dq.peekFirst() < now - 60_000L) dq.pollFirst();
                if (dq.isEmpty()) it.remove();
            }
        }
        if (!otaApkWindow.isEmpty()) {
            Iterator<Map.Entry<String, Deque<Long>>> it = otaApkWindow.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Deque<Long>> e = it.next();
                Deque<Long> dq = e.getValue();
                while (!dq.isEmpty() && dq.peekFirst() < now - 3600_000L) dq.pollFirst();
                if (dq.isEmpty()) it.remove();
            }
        }
    }

    /** 发送一条 OTA 控制/数据命令。 */
    private void sendOta(Connection c, String name, String data) {
        command x = new command();
        x.command = name;
        x.data = (data == null) ? "" : data;
        send(c, x);
    }

    /** 读取更新目录下的 version.json（不存在或关闭返回空串）。 */
    private String readUpdateVersionJson() {
        try {
            File f = new File(updateDir, "version.json");
            if (!f.exists() || !f.isFile()) return "";
            long len = f.length();
            if (len <= 0 || len > 1024 * 1024) return "";
            byte[] b = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            }
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 响应客户端的版本查询：把 version.json 原文回给客户端（关闭/无文件时回空串）。 */
    private void serveOtaVersion(Connection c) {
        if (!updateOverGame) { sendOta(c, "otaInfo", ""); return; }
        String ip = connIp(c);
        if (!allowOtaReq(ip)) { sendOta(c, "otaInfo", ""); return; }
        sendOta(c, "otaInfo", readUpdateVersionJson());
        log("[ota] 版本信息已下发 -> " + ip);
    }

    /**
     * 响应客户端下载请求：校验通过后，把「发送整个 APK」的任务交给专用线程池异步执行。
     *
     * <p><b>为什么必须异步</b>：本方法由 onReceived → handleCommand 在 KryoNet 的
     * <b>唯一网络线程</b>上调用。若在此同步发送整包（约 24MB），网络线程会被长时间占满，
     * 期间无法处理 OP_ACCEPT，新连接拿不到 RegisterTCP，客户端即报
     * 「Connected, but timed out during TCP registration」；消息转发与心跳也会停滞（服务器假死）。</p>
     *
     * <p>KryoNet 的 {@code TcpConnection.send()} 内部以 writeLock 同步，跨线程发送是安全的，
     * 且 otaStart 在网络线程先行排队，包序不会错乱。</p>
     */
    private void serveOtaApk(Connection c) {
        if (!updateOverGame) { sendOta(c, "otaError", "更新服务未启用"); return; }
        String ip = connIp(c);
        if (!allowOtaApk(ip)) { sendOta(c, "otaError", "下载次数过于频繁，请稍后再试。"); return; }
        File apk = new File(updateDir, "app-release.apk");
        if (!apk.exists() || !apk.isFile()) { sendOta(c, "otaError", "服务器上没有更新文件。"); return; }
        final Semaphore slots = otaSlots;
        if (slots != null && !slots.tryAcquire()) {
            sendOta(c, "otaError", "当前下载人数过多，请稍后再试。");
            return;
        }
        final long total = apk.length();
        sendOta(c, "otaStart", String.valueOf(total));
        ExecutorService pool = otaPool;
        if (pool == null) {
            // 理论不可达（start() 已初始化）；退化为当前线程发送，保证功能不丢。
            try { streamOtaApk(c, apk, ip, total); }
            finally { if (slots != null) { try { slots.release(); } catch (Exception ignore) {} } }
            return;
        }
        try {
            pool.execute(() -> {
                try { streamOtaApk(c, apk, ip, total); }
                finally { if (slots != null) { try { slots.release(); } catch (Exception ignore) {} } }
            });
        } catch (Exception e) {
            if (slots != null) { try { slots.release(); } catch (Exception ignore) {} }
            log("[ota] 下发任务提交失败: " + e);
            sendOta(c, "otaError", "服务器忙，请稍后再试。");
        }
    }

    /**
     * 实际把 apk 以 base64 分块下发（在 OTA 专用线程执行，不得在网络线程调用）。
     * 带写缓冲流控与坏连接保护：客户端长时间不消费（写缓冲排不空）时主动断开，
     * 避免线程与内存被慢连接/坏连接无限拖占。
     */
    private void streamOtaApk(Connection c, File apk, String ip, long total) {
        long sent = 0;
        try (InputStream in = new FileInputStream(apk)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (!c.isConnected()) break;
                byte[] chunk = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                sendOta(c, "otaChunk", java.util.Base64.getEncoder().encodeToString(chunk));
                sent += n;
                // 流控：写缓冲过大则等待排空；若持续排不空（客户端不读/网络极差）则判定坏连接并主动断开
                if (c.getTcpWriteBufferSize() > 1024 * 1024) {
                    long deadline = System.currentTimeMillis() + 60_000L;
                    while (c.isConnected() && c.getTcpWriteBufferSize() > 1024 * 1024
                            && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(5); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (c.isConnected() && c.getTcpWriteBufferSize() > 1024 * 1024) {
                        log("[ota] 客户端消费过慢（写缓冲持续积压），主动断开: " + ip);
                        try { c.close(); } catch (Exception ignore) {}
                        break;
                    }
                }
            }
            if (c.isConnected()) {
                sendOta(c, "otaDone", String.valueOf(total));
                log("[ota] APK 已下发 -> " + ip + " (" + sent + "/" + total + " bytes)");
            } else {
                log("[ota] 客户端中途断开，停止下发 -> " + ip + " (" + sent + "/" + total + " bytes)");
            }
        } catch (Exception e) {
            log("[ota] 下发失败: " + e);
            sendOta(c, "otaError", "下发失败: " + e.getMessage());
        }
    }

    /** 统一中文日志输出：带 [yyyy-MM-dd HH:mm:ss] 时间前缀。 */
    private static void log(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        System.out.println("[" + ts + "] " + msg);
        System.out.flush();
    }
}

