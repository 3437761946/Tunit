package com.mirwanda.nottiled;

import com.esotericsoftware.kryonet.Client;

/**
 * KryoNet 客户端网络线程的崩溃防护壳。
 *
 * <p>背景：KryoNet 2.22 的 {@code Client.run()} 在收到无法反序列化、或长度超过对象缓冲区
 * 的数据包时，会先 {@code close()} 再执行 {@code throw ex;}，把 {@link com.esotericsoftware.kryonet.KryoNetException}
 * 抛出名为 "Client" 的网络线程。该线程没有任何异常兜底，异常会一路冒泡并终止整个 App 进程。
 * 客户端日志里的典型现象就是：
 * <pre>
 * FATAL EXCEPTION: Client
 * com.esotericsoftware.kryonet.KryoNetException: Unable to read object larger than read buffer: 4136
 * </pre>
 *
 * <p>工具绘制、地边(自动补边)、对象层增删改都会高频发送网络包，因此在多人联机下最容易触发。
 *
 * <p>这里重写 {@code run()}：一旦底层网络循环因协议/缓冲异常退出，直接吞掉异常即可。
 * 异常发生时 KryoNet 已经关闭了连接，UI 线程会通过 {@code isConnected()==false} 检测到断线，
 * 从而走正常的 {@code stopClient()} 流程——表现为「掉线」而不是「闪退」，也不会留下半开连接。
 */
public class SafeClient extends Client {

    public SafeClient (int writeBufferSize, int objectBufferSize) {
        super( writeBufferSize, objectBufferSize );
    }

    @Override
    public void run () {
        try {
            super.run();
        } catch (Throwable t) {
            // 网络线程异常不再向上抛出，避免未捕获异常终止进程
            try { if (isConnected()) close(); } catch (Exception ignore) {}
        }
    }
}
