package com.mirwanda.nottiled;

public class actvClients extends packet{
    public String id;
    public String room;
    public boolean creator;
    // 内嵌主机服务器专用：房间密码与人数上限（不参与 Kryo 序列化，仅供本机内存记账）
    public String pass="";
    public int maxPlayers=6;
    // 内嵌主机服务器专用：成员对应的 KryoNet 连接（供名单/禁编定向发送；不参与序列化）
    public transient com.esotericsoftware.kryonet.Connection conn;
    // 展示昵称（registerID 时从 id 尾部「_数字」剥离；不参与序列化）
    public String nickname="";
    // 内嵌主机服务器专用：客户端上报的版本号（clientVersion 命令）；用于房间内版本隔离，不参与序列化
    public int versionCode=0;
}
