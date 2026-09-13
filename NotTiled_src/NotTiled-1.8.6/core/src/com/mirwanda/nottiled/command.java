package com.mirwanda.nottiled;

public class command extends packet{
    public String from="";
    public String room="";
    public String command="";
    public String data="";
    public layerhistory lh=null;
    // 对象层实时同步（objOp: add/update/delete）
    public String objOp="";
    public int layer=-1;
    public String objName="";
    public String objJson="";

    // ---- 房间创建载荷（密码 \u0001 人数上限）编码/解析。仅静态方法，不增加序列化字段。 ----
    public static final String ROOM_SEP = "\u0001";

    // 编码 createRoom 的 cmd.data：密码在前，可选的"人数上限"在后，用控制符分隔（旧客户端无分隔符时向后兼容）
    public static String encodeRoomData(String pass, String maxPlayers){
        String p = (pass == null) ? "" : pass;
        String m = (maxPlayers == null) ? "" : maxPlayers.trim();
        if (m.isEmpty()) return p;
        return p + ROOM_SEP + m;
    }

    // 解析 createRoom 载荷中的密码部分
    public static String parseRoomPass(String data){
        if (data == null) return "";
        int i = data.indexOf( ROOM_SEP );
        return (i < 0) ? data : data.substring(0, i);
    }

    // 解析 createRoom 载荷中的"人数上限"；缺失/非法时返回 defMax
    public static int parseRoomMax(String data, int defMax){
        if (data == null) return defMax;
        int i = data.indexOf( ROOM_SEP );
        if (i < 0) return defMax;
        String m = data.substring(i + ROOM_SEP.length());
        try {
            int v = Integer.parseInt( m.trim() );
            if (v <= 0) return defMax;
            return v;
        } catch (Exception e) { return defMax; }
    }

    // ---- 图层结构实时同步（layerOp: add/dup/remove/move/meta）。仅用 data 载荷，不增加序列化字段。 ----
    // 采用固定 4 段编码 <op>SEP<a>SEP<b>SEP<c>，按 op 解释各段含义：
    //   add    : a=插入索引, b=类型(TILE/OBJECT/IMAGE), c=名称
    //   dup    : a=源索引,   b=插入索引
    //   remove : a=索引
    //   move   : a=源索引,   b=目标索引
    //   meta   : a=索引,     b=该图层元数据(名称/显隐/透明度/锁定/偏移/自定义属性)的 JSON
    public static final String LAYER_SEP = "\u0001";

    public static String encodeLayerOp(String op, Object a, Object b, Object c){
        StringBuilder sb = new StringBuilder(op == null ? "" : op);
        sb.append(LAYER_SEP).append(a == null ? "" : a);
        sb.append(LAYER_SEP).append(b == null ? "" : b);
        sb.append(LAYER_SEP).append(c == null ? "" : c);
        return sb.toString();
    }

    // 解析 layerOp 载荷；limit=4 保证 meta 的 JSON 段（可能含分隔符）不被再切分
    public static String[] parseLayerOp(String data){
        if (data == null) return new String[0];
        return data.split(LAYER_SEP, 4);
    }
}
