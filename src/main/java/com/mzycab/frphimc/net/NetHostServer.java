package com.mzycab.frphimc.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FRPHI Coop 协议服务端（与 FRPHI 安卓端 Coop.kt 的 host() 行为一致，双向互通的另一半）。
 *
 * 协议：TCP + 每行一条 JSON（UTF-8）。
 *  - 收 join {t:join,pw,name} → 回 joinok{id,chart:"",meta:"",players} 或 joinerr{msg}（密码错 3 次断开）
 *  - 收 chat {t:chat,n,d} → 转发给其他客户端 + 本机 onChat 回调
 *  - 收 bye / 断线 → 移除玩家 + 广播 players
 *  - 心跳每 10s 广播 ping（客户端依赖它识别通道存活）
 *  - chart / start / stop / frame / confirm / ask 等「你写我打」消息原样转发（MC 只当中转站）
 *  - kick / file / chunk 一律忽略（对 FRPHI 客户端无害）
 *
 * 纯 Java、不依赖 Minecraft，方便用 NetClient（即 FRPHI 客户端的协议实现）做互通单测。
 */
public final class NetHostServer {

    /** 一个房间里最多几个 FRPHI 客户端（对齐 FRPHI Coop.MAX_CLIENTS=8） */
    public static final int MAX_CLIENTS = 8;
    /** 谱面文本上限（对齐 FRPHI Coop.MAX_CHART_CHARS = 8MB） */
    private static final int MAX_CHART_CHARS = 8 * 1024 * 1024;
    private static final int MAX_BAD_PW = 3;

    public interface HostListener {
        /** 有人加入/离开，或列表变化（都在服务线程上触发）。 */
        void onPlayers(List<NetClient.Player> players);
        /** FRPHI 玩家发来聊天。name=设备名。 */
        void onChat(String name, String text);
        /** 房间已关闭（本机主动 stop 时也会先触发一次，reason 说明）。 */
        void onClosed(String reason);
    }

    private final String hostName;
    private final HostListener listener;
    private volatile ServerSocket server;
    private volatile boolean running = false;
    private volatile int port = 25566;
    private volatile String password = "";
    /**
     * 房间当前谱面（由 FRPHI 客户端里的「伪房主」传上来；MC 自己不开谱）。
     * 收到 chart 时记下来并转发给其他客户端，新客户端 joinok 里直接带上。
     */
    private volatile String roomChart = "";
    /**
     * 伪房主的玩家 id（-1 = 还没有人传谱）。
     * ★ 有人传谱之后，MC 端就**只提供服务器、不再是房主**（用户点名）：
     * 关房 / 踢 MC 都由伪房主说了算，所以 closeroom / kickmc 只认这个 id。
     */
    private volatile int pseudoHostId = -1;
    private final CopyOnWriteArrayList<NetClient.Player> players = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ClientConn> conns = new CopyOnWriteArrayList<>();
    /**
     * 心跳线程池。★ 不能在 stop() 里 shutdown：这个实例会被复用（关房 → 再开房），
     * 关掉的池子再 schedule 会抛 RejectedExecutionException，表现就是「关房再开房客户端崩溃」。
     * 关房只取消那一条定时任务，池子留着；万一是真的被关了（外部 shutdown）就在 host() 里重建。
     */
    private ScheduledExecutorService heartbeats = newHeartbeats();
    private java.util.concurrent.ScheduledFuture<?> heartbeatTask;

    private static ScheduledExecutorService newHeartbeats() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "frphi-host-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public NetHostServer(String mcPlayerName, HostListener listener) {
        this.hostName = mcPlayerName;
        this.listener = listener;
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public String getPassword() { return password; }
    public List<NetClient.Player> getPlayers() { return Collections.unmodifiableList(players); }

    /** 伪房主的玩家 id（-1 = 还没有人传谱）；有它之后 MC 只提供服务器、不再算房主。 */
    public int getPseudoHostId() { return pseudoHostId; }

    /**
     * 开房间。返回 null = 成功，否则是给用户看的错误文案（端口被占等）。
     */
    public synchronized String host(int p, String pw) {
        if (running) return "已经在开房间";
        ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(p), 8);
        } catch (Exception e) {
            return "端口 " + p + " 被占用：" + e.getMessage();
        }
        server = ss;
        running = true;
        port = ss.getLocalPort();   // 端口 0 = 随机端口，取实际监听值
        password = pw == null ? "" : pw;
        roomChart = "";                                    // 新房间：谱面清空
        pseudoHostId = -1;                                 // 新房间：还没有伪房主
        players.clear();
        players.add(new NetClient.Player(0, hostName, localIp()));
        String pwDesc = password.isEmpty() ? "无密码" : password.length() + " 位密码";
        Thread accept = new Thread(() -> acceptLoop(ss), "frphi-host-accept");
        accept.setDaemon(true);
        accept.start();
        if (heartbeats.isShutdown()) heartbeats = newHeartbeats();     // 池子被关过 → 重建（防崩溃）
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = heartbeats.scheduleWithFixedDelay(() -> {
            if (!running) return;
            broadcast(ping());
        }, 10, 10, TimeUnit.SECONDS);
        return null;
    }

    private void acceptLoop(ServerSocket ss) {
        while (running && !ss.isClosed()) {
            try {
                Socket s = ss.accept();
                ClientConn c = new ClientConn(s);
                Thread t = new Thread(() -> serve(c), "frphi-host-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception ignored) {
                break;
            }
        }
    }

    private void serve(ClientConn c) {
        NetClient.Player who = null;
        int badPw = 0;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(c.socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = in.readLine()) != null) {
                Object parsed;
                try {
                    parsed = MiniJson.parse(line);
                } catch (Exception e) {
                    continue;
                }
                if (!(parsed instanceof Map)) continue;
                Map<?, ?> o = (Map<?, ?>) parsed;
                String t = str(o, "t");
                switch (t == null ? "" : t) {
                    case "join": {
                        if (conns.size() >= MAX_CLIENTS) {
                            send(c, "{\"t\":\"joinerr\",\"msg\":\"房间已满\"}");
                            continue;
                        }
                        String given = str(o, "pw", "");
                        if (!given.equals(password)) {
                            badPw++;
                            send(c, "{\"t\":\"joinerr\",\"msg\":\"密码错误\"}");
                            if (badPw >= MAX_BAD_PW) {
                                c.close();
                                return;
                            }
                            continue;
                        }
                        String name = str(o, "name", "设备");
                        String ip = c.remoteIp();
                        int id = (players.stream().mapToInt(p -> p.id).max().orElse(0)) + 1;
                        who = new NetClient.Player(id, name, ip);
                        players.add(who);
                        conns.add(c);
                        c.who = who;
                        send(c, "{\"t\":\"joinok\",\"id\":" + id + ",\"chart\":" + MiniJson.quote(roomChart)
                                + ",\"meta\":\"\",\"ph\":" + pseudoHostId
                                + ",\"players\":" + playersJson() + "}");
                        broadcast("{\"t\":\"players\",\"p\":" + playersJson() + "}");
                        firePlayers();
                        break;
                    }
                    case "chat": {
                        String n = str(o, "n", "设备");
                        String d = str(o, "d", "");
                        if (d.length() > 512) break;
                        forwardExcept(c, "{\"t\":\"chat\",\"n\":" + MiniJson.quote(n) + ",\"d\":" + MiniJson.quote(d) + "}");
                        fireChat(n, d);
                        break;
                    }
                    case "chart": {
                        // ★ 伪房主（房主是 MC）传谱：必须原样转发给其它客户端，
                        //   并且记下来 —— 之后进来的人 joinok 里直接带上这份谱面。
                        String d = str(o, "d", "");
                        if (d.isEmpty() || d.length() > MAX_CHART_CHARS) break;
                        roomChart = d;
                        // ★ 第一个传谱的人 = 伪房主（一次联机只有一个）：广播 ph，让所有客户端的
                        //   「房主」标签换成「伪房主」，之后关房/踢 MC 只认他。
                        if (who != null && pseudoHostId < 0) {
                            pseudoHostId = who.id;
                            broadcast("{\"t\":\"ph\",\"i\":" + pseudoHostId + "}");
                        }
                        forwardExcept(c, "{\"t\":\"chart\",\"d\":" + MiniJson.quote(d) + "}");
                        break;
                    }
                    case "bye":
                        c.close();
                        return;
                    // ★ 伪房主关房 / 踢出 MC 端：两者都是「这一局整体结束，全员下线」
                    //   closeroom = 优雅关房（先发 bye 再关）；kickmc = 直接关（用户点名：允许）。
                    //   只认伪房主（pseudoHostId），别人发来一律忽略。
                    case "closeroom":
                    case "kickmc": {
                        if (who == null) break;
                        if (who.id != pseudoHostId) break;      // -1 时谁都不匹配：没人传谱就不能关房
                        String reason = "kickmc".equals(t) ? "伪房主踢出了 MC 端（全员下线）" : "伪房主关闭了房间";
                        fireClosed(reason);
                        stop(reason);                       // stop 内部会先发 bye 再关连接
                        break;
                    }
                    // 「你写我打」相关：MC 只是房主的中转站，原样转给 FRPHI 客户端
                    // frame（框色）/confirm（打勾）**连发送者一起回传**：MC 房主不发 players 广播，
                    // 客户端靠这条回显才知道自己刚框了什么色（回显一次，不会来回翻）。
                    case "frame":
                    case "confirm": {
                        if (who == null) break;
                        broadcast(line);
                        break;
                    }
                    // ask（开始请求）/start/stop/pause/resume：只转给别人，别回给发起者（否则会重复弹窗）
                    case "ask":
                    case "start":
                    case "stop":
                    case "pause":
                    case "resume": {
                        if (who == null) break;
                        forwardExcept(c, line);
                        break;
                    }
                    default:
                        // chart 已在上面处理；kick/file/chunk/ping 一律忽略（对 FRPHI 客户端无害）
                        break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            conns.remove(c);
            NetClient.Player leaver = who;
            if (leaver != null) {
                players.removeIf(p -> p.id == leaver.id);
                if (running) {
                    broadcast("{\"t\":\"players\",\"p\":" + playersJson() + "}");
                    firePlayers();
                }
            }
            c.close();
        }
    }

    /** 发聊天给所有 FRPHI 客户端（MC 玩家自己在游戏里说的话）。 */
    public void sendHostChat(String text) {
        if (!running || text == null || text.isEmpty()) return;
        broadcast("{\"t\":\"chat\",\"n\":" + MiniJson.quote(hostName) + ",\"d\":" + MiniJson.quote(text) + "}");
    }

    /** 主动关闭房间。 */
    public synchronized void stop() {
        stop("房主关闭了房间");
    }

    /** 主动关闭房间（reason 会作为 bye 的消息发给所有 FRPHI 客户端）。 */
    public synchronized void stop(String reason) {
        if (!running) return;
        // ★ 必须先发 bye 再置 running=false：send() 见到 !running 就直接 return，
        //   顺序反了客户端就收不到「房间已关闭」，只能等断线（以前就是这样）。
        String msg = reason == null || reason.isEmpty() ? "房主关闭了房间" : reason;
        for (ClientConn c : conns) {
            try { send(c, "{\"t\":\"bye\",\"msg\":" + MiniJson.quote(msg) + "}"); } catch (Exception ignored) {}
        }
        running = false;
        // ★ 只取消心跳，不 shutdown 线程池（实例要复用，见 heartbeats 的注释）
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        try { server.close(); } catch (Exception ignored) {}
        server = null;
        for (ClientConn c : conns) c.close();
        conns.clear();
        players.clear();
        roomChart = "";
        pseudoHostId = -1;
    }

    // ---------------------------------------------------------------- 内部

    private final ConcurrentHashMap<ClientConn, OutputStreamWriter> writers = new ConcurrentHashMap<>();

    private void send(ClientConn c, String line) {
        if (!running) return;
        try {
            OutputStreamWriter w = writers.get(c);
            if (w == null) {
                try {
                    w = new OutputStreamWriter(c.socket.getOutputStream(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return;
                }
                writers.put(c, w);
            }
            synchronized (w) {
                w.write(line + "\n");
                w.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void broadcast(String line) {
        for (ClientConn c : conns) {
            try { send(c, line); } catch (Exception ignored) {}
        }
    }

    private void forwardExcept(ClientConn from, String line) {
        for (ClientConn c : conns) {
            if (c != from) {
                try { send(c, line); } catch (Exception ignored) {}
            }
        }
    }

    private String playersJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (NetClient.Player p : players) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(p.id)
              .append(",\"name\":").append(MiniJson.quote(p.name))
              .append(",\"ip\":").append(MiniJson.quote(p.ip))
              .append(",\"color\":0,\"ok\":true}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String ping() { return "{\"t\":\"ping\"}"; }

    private void firePlayers() {
        try { listener.onPlayers(new ArrayList<>(players)); } catch (Throwable ignored) {}
    }

    private void fireChat(String name, String text) {
        try { listener.onChat(name, text); } catch (Throwable ignored) {}
    }

    private void fireClosed(String reason) {
        try { listener.onClosed(reason); } catch (Throwable ignored) {}
    }

    private static String str(Map<?, ?> m, String key) { return str(m, key, ""); }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }

    /** 本机局域网 IPv4（优先 wlan/eth，拿给用户填 openfrp 或让手机直连）。 */
    public static String localIp() {
        String fallback = "";
        try {
            List<NetworkInterface> nifs = new ArrayList<>();
            var en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) nifs.add(en.nextElement());
            nifs.sort((a, b) -> {
                String na = a.getName() == null ? "" : a.getName();
                String nb = b.getName() == null ? "" : b.getName();
                int ra = score(na), rb = score(nb);
                return Integer.compare(ra, rb);
            });
            for (NetworkInterface nif : nifs) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                var addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip == null) continue;
                        if (fallback.isEmpty()) fallback = ip;
                        String n = nif.getName() == null ? "" : nif.getName();
                        if (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en") || n.startsWith("以太网")) return ip;
                        if (n.startsWith("wlan")) return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback.isEmpty() ? "127.0.0.1" : fallback;
    }

    private static int score(String name) {
        if (name.startsWith("wlan")) return 0;
        if (name.startsWith("eth")) return 1;
        if (name.startsWith("en")) return 2;
        if (name.contains("an")) return 3;
        return 9;
    }

    private static final class ClientConn {
        final Socket socket;
        volatile NetClient.Player who;

        ClientConn(Socket s) { this.socket = s; }

        String remoteIp() {
            InetAddress a = socket.getInetAddress();
            return a == null ? "?" : a.getHostAddress();
        }

        void close() {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}