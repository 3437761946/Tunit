package com.mirwanda.nottiled.server;

import com.esotericsoftware.kryonet.Server;

/**
 * KryoNet 服务端网络线程的崩溃防护壳（独立开服包使用）。
 *
 * <p>KryoNet 的 {@code Server.run()} 只捕获 {@code IOException}；若底层网络循环抛出
 * {@code KryoNetException}（畸形/超限数据包等），异常会终止名为 "Server" 的网络线程，
 * 使服务器不再处理任何连接（表现为「端口还在但连上没反应」）。
 *
 * <p>这里重写 {@code run()}：捕获异常后「继续」跑网络循环（除非已被显式 stop），
 * 使单个坏包最多断开一条坏连接，而不会让整个服务器网络循环退出停摆。
 */
public class SafeServer extends Server {

    /** 是否已被显式 stop：用于区分「正常停机」与「异常中断」，避免停机后重入循环。 */
    private volatile boolean stopping = false;

    public SafeServer (int writeBufferSize, int objectBufferSize) {
        super( writeBufferSize, objectBufferSize );
    }

    @Override
    public void start () {
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
                // 单次网络异常不终止服务器：若仍在运行则重新进入网络循环
                if (stopping) return;
            }
        }
    }
}
