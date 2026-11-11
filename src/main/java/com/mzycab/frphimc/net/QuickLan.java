package com.mzycab.frphimc.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 快速联机（局域网 UDP 广播发现）——和 FRPHI 安卓端 Coop.kt 的快速联机是同一套协议：
 *
 * <pre>
 * UDP 25567，广播到 255.255.255.255，JSON 一行：
 *   {"t":"qroom","port":25566,"pw":"一次性令牌","name":"设备名"}
 * </pre>
 *
 * 服务端每 500ms 发一次；客户端 bind 25567 收包，看到 qroom 就记下 ip:port + 令牌。
 * 令牌就是"进房凭据"（真密码不上网），FRPHI 服务端同时接受真密码和令牌。
 *
 * 本类刻意不依赖任何 Minecraft 类，方便纯 JVM 单测。
 */
public final class QuickLan {

    /** 和 FRPHI 的 Coop.QUICK_UDP_PORT 必须一致 */
    public static final int PORT = 25567;
    /** 快速服务端/客户端的时间窗（用户点名：60 秒） */
    public static final int WINDOW_SEC = 60;

    private static final SecureRandom RND = new SecureRandom();

    /** 发现到的房间 */
    public static final class Room {
        public final String ip;
        public final int port;
        public final String token;
        public final String name;
        public long seenAt;

        public Room(String ip, int port, String token, String name) {
            this.ip = ip;
            this.port = port;
            this.token = token;
            this.name = name;
            this.seenAt = System.currentTimeMillis();
        }

        public String addr() {
            return ip + ":" + port;
        }

        @Override
        public String toString() {
            return name.isEmpty() ? addr() : name + " (" + addr() + ")";
        }
    }

    private volatile boolean beaconOn;
    private DatagramSocket beaconSock;

    /** 生成一次性令牌（10 位，去掉容易看错的 0/o/1/l） */
    public static String newToken() {
        final String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(abc.charAt(RND.nextInt(abc.length())));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 服务端广播

    /** 开始广播"我这儿有快速房间"。token 同时是进房凭据。 */
    public synchronized void startBeacon(int port, String token, String name) {
        stopBeacon();
        DatagramSocket s;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
        } catch (Exception e) {
            return;
        }
        beaconSock = s;
        beaconOn = true;
        final byte[] payload = ("{\"t\":\"qroom\",\"port\":" + port
                + ",\"pw\":" + MiniJson.quote(token == null ? "" : token)
                + ",\"name\":" + MiniJson.quote(name == null ? "" : name) + "}")
                .getBytes(StandardCharsets.UTF_8);
        Thread t = new Thread(() -> {
            while (beaconOn && !s.isClosed()) {
                try {
                    s.send(new DatagramPacket(payload, payload.length,
                            InetAddress.getByName("255.255.255.255"), PORT));
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "frphimc-quick-beacon");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stopBeacon() {
        beaconOn = false;
        try {
            if (beaconSock != null) beaconSock.close();
        } catch (Exception ignored) {
        }
        beaconSock = null;
    }

    public boolean isBeaconing() {
        return beaconOn;
    }

    // ---------------------------------------------------------------- 客户端扫描

    /**
     * 在 [seconds] 秒内不停搜索局域网里的快速房间（一次收 300ms，循环到超时）。
     * 每发现一个新房间就回调 [onFound]（按 ip:port 去重），结束时回调 [onDone]。
     * **必须在后台线程调用**（会阻塞到超时）。
     */
    public void scan(int seconds, Consumer<Room> onFound, Consumer<List<Room>> onDone) {
        Map<String, Room> found = new LinkedHashMap<>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(PORT));
            sock.setSoTimeout(300);
            byte[] buf = new byte[512];
            long end = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
            while (System.currentTimeMillis() < end) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(pkt);
                } catch (Exception e) {
                    continue;               // 超时 / 被打断：继续等下一条
                }
                String txt = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                Object o;
                try {
                    o = MiniJson.parse(txt);
                } catch (Exception e) {
                    continue;
                }
                if (!"qroom".equals(MiniJson.optStr(o, "t", ""))) continue;
                String ip = pkt.getAddress() == null ? "" : pkt.getAddress().getHostAddress();
                if (ip.isEmpty()) continue;
                int port = MiniJson.optInt(o, "port", 25566);
                String key = ip + ":" + port;
                Room r = found.get(key);
                if (r == null) {
                    r = new Room(ip, port, MiniJson.optStr(o, "pw", ""), MiniJson.optStr(o, "name", ""));
                    found.put(key, r);
                    if (onFound != null) onFound.accept(r);
                } else {
                    r.seenAt = System.currentTimeMillis();
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (sock != null) sock.close();
            } catch (Exception ignored) {
            }
        }
        if (onDone != null) onDone.accept(new ArrayList<>(found.values()));
    }
}
