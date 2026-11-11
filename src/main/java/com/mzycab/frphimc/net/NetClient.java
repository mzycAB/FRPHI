package com.mzycab.frphimc.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FRPHI Coop 协议客户端（与 FRPHI 安卓端 Coop.kt 服务端互通）。
 *
 * 协议：TCP + 每行一条 JSON（UTF-8）。
 *  - 发  join: {"t":"join","pw":"密码","name":"玩家名"}
 *  - 收  joinok: {"t":"joinok","id":N,"chart":"...","meta":"...","players":[...]}
 *       joinerr: {"t":"joinerr","msg":"..."}
 *       players: {"t":"players","p":[{id,name,ip,...}]}
 *       chat:    {"t":"chat","n":"名字","d":"内容"}
 *       bye:     {"t":"bye","msg":"..."}
 *       ping / chart / file / chunk / fileend / mediaend / start / stop / frame / confirm / kick：
 *       本模组用不到，一律忽略。
 *
 * 本类刻意不依赖任何 Minecraft 类，方便在纯 JVM 单测里用模拟服务端验证。
 * 所有回调在网络线程上发出，调用方自行切回主线程。
 */
public final class NetClient {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public static final class Player {
        public final int id;
        public final String name;
        public final String ip;
        public Player(int id, String name, String ip) {
            this.id = id; this.name = name; this.ip = ip;
        }
        @Override public String toString() { return "Player{" + id + "," + name + "," + ip + "}"; }
    }

    public interface Listener {
        /** 握手成功（收到 joinok）。players 是房间里现有玩家。 */
        void onConnected(int myId, List<Player> players);
        /** 玩家列表变化（服务端广播 players）。 */
        void onPlayers(List<Player> players);
        /** 收到聊天消息。 */
        void onChat(String name, String text);
        /** 连接失败 / 被踢 / 断开。reason 可直接展示给用户。 */
        void onClosed(String reason);
    }

    private final Listener listener;
    private volatile State state = State.DISCONNECTED;
    private volatile Socket socket;
    private volatile Thread thread;
    private final CopyOnWriteArrayList<Player> players = new CopyOnWriteArrayList<>();
    private volatile int myId = 0;
    private volatile String hostAddr = "";
    private volatile String selfName = "MC";

    public NetClient(Listener listener) {
        this.listener = listener;
    }

    public State getState() { return state; }
    public boolean isConnected() { return state == State.CONNECTED; }
    public int getMyId() { return myId; }
    public String getHostAddr() { return hostAddr; }
    public List<Player> getPlayers() { return Collections.unmodifiableList(players); }

    /** 解析用户输入的地址：支持 "192.168.1.5:25566" 与 "frphi://192.168.1.5:25566" 两种。 */
    public static String normalizeAddress(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("frphi://")) s = s.substring("frphi://".length());
        else if (s.startsWith("frphi:")) s = s.substring("frphi:".length());
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("[") && s.contains("]:")) {
            // 忽略 IPv6 支持：FRPHI 只关心 IPv4
        }
        return s;
    }

    /**
     * 开始连接。立即返回；结果通过 listener 回调（网络线程）。
     * 若当前已连接/连接中，先断开旧的。
     */
    public void connect(final String host, final int port, final String password, final String username) {
        disconnect();
        state = State.CONNECTING;
        hostAddr = host + ":" + port;
        selfName = (username == null || username.isEmpty()) ? "MC" : username;
        final Thread t = new Thread(() -> runConnect(host, port, password, username), "frphi-net");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void runConnect(String host, int port, String password, String username) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(0);
            socket = s;
            OutputStreamWriter out = new OutputStreamWriter(s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));

            String join = "{\"t\":\"join\",\"pw\":" + MiniJson.quote(password == null ? "" : password)
                    + ",\"name\":" + MiniJson.quote(username == null ? "MC" : username) + "}";
            out.write(join + "\n");
            out.flush();

            // 等第一条回复：joinok 或 joinerr
            String first = in.readLine();
            if (first == null) { fireClosed("服务器没有响应"); return; }
            Map<?, ?> o = asObject(first);
            if (o == null) { fireClosed("服务器回复无法解析"); return; }
            String t = str(o, "t");
            if ("joinerr".equals(t)) {
                fireClosed("加入失败：" + str(o, "msg", "服务器拒绝"));
                return;
            }
            if (!"joinok".equals(t)) {
                fireClosed("握手异常：收到 " + (t == null ? "未知消息" : t));
                return;
            }
            state = State.CONNECTED;
            myId = MiniJson.optInt(o, "id", 1);
            replacePlayers(o.get("players"));
            fireConnected(myId, new ArrayList<>(players));

            // 读循环
            String line;
            while ((line = in.readLine()) != null) {
                Map<?, ?> m = asObject(line);
                if (m == null) continue;
                String type = str(m, "t");
                switch (type == null ? "" : type) {
                    case "players":
                        replacePlayers(m.get("p"));
                        firePlayers(new ArrayList<>(players));
                        break;
                    case "chat":
                        fireChat(str(m, "n", "？"), str(m, "d", ""));
                        break;
                    case "bye":
                        fireClosed(str(m, "msg", "房间已关闭"));
                        closeQuietly();
                        return;
                    default:
                        // ping / chart / file / chunk / fileend / mediaend / start / stop / frame / confirm / kick 一律忽略
                        break;
                }
            }
            fireClosed("与服务器的连接已断开");
        } catch (Exception e) {
            if (state != State.CONNECTED) {
                fireClosed("连接失败：" + safeMsg(e));
            } else {
                fireClosed("连接中断：" + safeMsg(e));
            }
        } finally {
            closeQuietly();
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    /** 发聊天消息（&lt;=512 字符；FRPHI 服务端会转发给房间其他人）。 */
    public void sendChat(String text) {
        if (text == null || text.isEmpty() || state != State.CONNECTED) return;
        String msg = "{\"t\":\"chat\",\"d\":" + MiniJson.quote(text) + ",\"n\":" + MiniJson.quote(selfName) + "}";
        writeLine(msg);
    }

    /** 主动断开（不发 bye，FRPHI 端 detect 断开后自动移除玩家）。 */
    public void disconnect() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        state = State.DISCONNECTED;
        players.clear();
        myId = 0;
        hostAddr = "";
    }

    private void writeLine(String line) {
        Socket s = socket;
        if (s == null || s.isClosed()) return;
        try {
            java.io.OutputStream out = s.getOutputStream();
            synchronized (this) {
                out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            }
        } catch (Exception ignored) {
            // 发送失败：读循环那边会感知断开
        }
    }

    private void closeQuietly() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void replacePlayers(Object listObj) {
        players.clear();
        if (listObj instanceof List) {
            for (Object e : (List<?>) listObj) {
                if (e instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) e;
                    players.add(new Player(
                            MiniJson.optInt(m, "id", 0),
                            str(m, "name", "?"),
                            str(m, "ip", "?")));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asObject(String line) {
        try {
            Object v = MiniJson.parse(line);
            return v instanceof Map ? (Map<?, ?>) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Map<?, ?> m, String key) { return str(m, key, ""); }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }

    // ---- 回调 ----
    private void fireConnected(int id, List<Player> ps) {
        try { listener.onConnected(id, ps); } catch (Throwable ignored) {}
    }

    private void firePlayers(List<Player> ps) {
        try { listener.onPlayers(ps); } catch (Throwable ignored) {}
    }

    private void fireChat(String name, String text) {
        try { listener.onChat(name, text); } catch (Throwable ignored) {}
    }

    private void fireClosed(String reason) {
        state = State.DISCONNECTED;
        try { listener.onClosed(reason); } catch (Throwable ignored) {}
    }
}