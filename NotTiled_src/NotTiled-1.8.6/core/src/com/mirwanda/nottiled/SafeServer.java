package com.mirwanda.nottiled;

import com.esotericsoftware.kryonet.Server;

/**
 * KryoNet 服务端网络线程的崩溃防护壳（内嵌主机模式使用）。
 *
 * <p>背景同 {@link SafeClient}：KryoNet 的 {@code Server.run()} 只捕获 {@code IOException}，
 * 一旦底层网络循环抛出 {@code KryoNetException}（畸形/超限数据包），异常会终止名为 "Server"
 * 的网络线程；在「WiFi 直连主机」模式下该线程与游戏同进程，未捕获异常会直接拖垮整个 App。
 *
 * <p>这里重写 {@code run()}：捕获异常后「继续」跑网络循环（除非已被显式 stop），
 * 使单个坏包最多断开一条坏连接，而不会终止主机服务或拖垮游戏进程。
 */
public class SafeServer extends Server {

    /** 是否已被显式 stop：用于区分「正常停机」与「异常中断」，避免停机后重入循环。 */
    private volatile boolean stopping = false;

    public SafeServer (int writeBufferSize, int objectBufferSize) {
        super( writeBufferSize, objectBufferSize );
    }

    @Override
    public void start () {
        // 主机可重复开关房间：每次 start 都重置停机标记，保证新线程能正常进入网络循环
        stopping = false;
        super.start();
    }

    @Override
    public void stop () {
        stopping = true;
        super.stop();
    }

    @Override
    public void run () {
        while (!stopping) {
            try {
                super.run();
                return;   // 正常返回（stop() 触发 shutdown）→ 结束线程
            } catch (Throwable t) {
                // 单次网络异常不终止主机服务：若仍在运行则重新进入网络循环
                if (stopping) return;
            }
        }
    }
}
