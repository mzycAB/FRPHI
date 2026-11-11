package com.mzycab.frphimc.net;

import com.mzycab.frphimc.net.MiniJson;
import com.mzycab.frphimc.net.NetClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 模拟 FRPHI 服务端（Coop.kt 协议）：用真实 ServerSocket + 每行一条 JSON 回话，
 * 验证 NetClient 的 join / 玩家列表 / 聊天 / bye 全流程。
 */
public final class FakeFrphiServer implements AutoCloseable {

    public final ServerSocket server;
    public final int port;
    public volatile Socket client;
    public volatile OutputStreamWriter out;
    /** 服务端收到的客户端消息（明文行） */
    public final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    /** 服务端是否答应了 join */
    public volatile boolean joined = false;

    public FakeFrphiServer() throws Exception {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        Thread t = new Thread(() -> {
            try {
                client = server.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
                String line;
                while ((line = in.readLine()) != null) {
                    received.add(line);
                    Object parsed = MiniJson.parse(line);
                    if (parsed instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) parsed;
                        String t2 = t(m, "t");
                        if ("join".equals(t2)) {
                            joined = true;
                            if (badPassword) {
                                send("{\"t\":\"joinerr\",\"msg\":\"密码错误\"}");
                            } else {
                                send("{\"t\":\"joinok\",\"id\":1,\"chart\":\"\",\"meta\":\"\","
                                        + "\"players\":[{\"id\":0,\"name\":\"房主手机\",\"ip\":\"192.168.1.5\",\"color\":0,\"ok\":true}]}");
                                send("{\"t\":\"ping\"}");
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "fake-frphi-server");
        t.setDaemon(true);
        t.start();
    }

    public volatile boolean badPassword = false;

    public void send(String line) throws Exception {
        OutputStreamWriter w = out;
        if (w != null) {
            w.write(line + "\n");
            w.flush();
        }
    }

    private static String t(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : "";
    }

    @Override
    public void close() throws Exception {
        try { if (client != null) client.close(); } catch (Exception ignored) {}
        server.close();
    }
}