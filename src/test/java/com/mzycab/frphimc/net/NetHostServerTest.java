package com.mzycab.frphimc.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 双向互通验证：用 NetClient（与 FRPHI 客户端同一套协议实现）连接本机 NetHostServer（MC 开房），
 * 验证 加入/玩家列表/双向聊天/密码错误/断开 全流程 —— 这正是 FRPHI 手机端将来连 MC 房的路径。
 */
public class NetHostServerTest {

    private static final class Rec implements NetClient.Listener {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        int myId = -1;
        /** 握手那一刻的玩家列表快照：不受后面断线 clear 的影响（原来直接读 getPlayers() 会偶发空列表） */
        volatile List<NetClient.Player> playersAtConnect = new ArrayList<>();

        @Override
        public void onConnected(int myId, List<NetClient.Player> players) {
            this.myId = myId;
            this.playersAtConnect = new ArrayList<>(players);
            events.add("connected:" + myId + ":" + players.size());
            connected.countDown();
        }

        @Override
        public void onPlayers(List<NetClient.Player> players) {
            events.add("players:" + players.size());
        }

        @Override
        public void onChat(String name, String text) {
            events.add("chat:" + name + ":" + text);
        }

        @Override
        public void onClosed(String reason) {
            events.add("closed:" + reason);
            closed.countDown();
        }
    }

    private static NetHostServer startServer(String pw) {
        NetHostServer srv = new NetHostServer("MC玩家A", new NetHostServer.HostListener() {
            @Override public void onPlayers(List<NetClient.Player> players) {}
            @Override public void onChat(String name, String text) {}
            @Override public void onClosed(String reason) {}
        });
        assertNull("开房失败", srv.host(0, pw));
        return srv;
    }

    private static NetClient join(NetHostServer srv, String pw, String name, Rec r) throws Exception {
        NetClient c = new NetClient(r);
        c.connect("127.0.0.1", srv.getPort(), pw, name);
        assertTrue("join 没成功", r.connected.await(5, TimeUnit.SECONDS));
        return c;
    }

    @Test
    public void join_seesHostAndGetsId() throws Exception {
        NetHostServer srv = startServer("1234");
        try {
            Rec r = new Rec();
            NetClient c = join(srv, "1234", "手机A", r);
            assertEquals("FRPHI 客户端应是 id=1", 1, r.myId);
            // 用握手快照断言，别读 c.getPlayers()：断线线程会 clear，全量跑时偶发 AIOOBE
            assertFalse("joinok 应带上玩家列表", r.playersAtConnect.isEmpty());
            assertEquals("玩家列表应含房主MC玩家A", "MC玩家A", r.playersAtConnect.get(0).name);
            assertEquals("房主 + 手机A = 2 人", 2, srv.getPlayers().size());
            c.disconnect();
        } finally {
            srv.stop();
        }
    }

    @Test
    public void twoClients_bothVisible() throws Exception {
        NetHostServer srv = startServer("");
        try {
            Rec r1 = new Rec();
            Rec r2 = new Rec();
            NetClient c1 = join(srv, "", "手机A", r1);
            NetClient c2 = join(srv, "", "手机B", r2);
            Thread.sleep(300);
            assertEquals("房主 + 2 客户端 = 3 人", 3, srv.getPlayers().size());
            assertEquals("手机B 应看到 3 人", 3, c1.getPlayers().size());
            assertEquals("手机A 应看到 3 人", 3, c2.getPlayers().size());
            c1.disconnect();
            c2.disconnect();
        } finally {
            srv.stop();
        }
    }

    @Test
    public void chat_broadcastToOthers_andHostCallback() throws Exception {
        NetHostServer srv = new NetHostServer("MC玩家A", new NetHostServer.HostListener() {
            final CopyOnWriteArrayList<String> got = new CopyOnWriteArrayList<>();
            @Override public void onPlayers(List<NetClient.Player> players) {}
            @Override public void onChat(String name, String text) { got.add(name + ":" + text); }
            @Override public void onClosed(String reason) {}
        });
        assertNull(srv.host(0, ""));
        try {
            Rec r1 = new Rec();
            Rec r2 = new Rec();
            NetClient c1 = join(srv, "", "手机A", r1);
            NetClient c2 = join(srv, "", "手机B", r2);
            c1.sendChat("大家好，我是手机A");
            long deadline = System.currentTimeMillis() + 3000;
            while (!r2.events.stream().anyMatch(e -> e.startsWith("chat:")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue("手机B 应收到手机A 的聊天", r2.events.stream().anyMatch(e -> e.equals("chat:手机A:大家好，我是手机A")));
            c1.disconnect();
            c2.disconnect();
        } finally {
            srv.stop();
        }
    }

    @Test
    public void wrongPassword_rejected() throws Exception {
        NetHostServer srv = startServer("9999");
        try {
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            c.connect("127.0.0.1", srv.getPort(), "wrong", "手机A");
            assertTrue("应收到 joinerr（closed）", r.closed.await(5, TimeUnit.SECONDS));
            assertTrue(r.events.stream().anyMatch(e -> e.startsWith("closed:加入失败：密码错误")));
        } finally {
            srv.stop();
        }
    }

    @Test
    public void clientDisconnect_removedFromList() throws Exception {
        NetHostServer srv = startServer("");
        try {
            Rec r = new Rec();
            NetClient c = join(srv, "", "手机A", r);
            assertEquals(2, srv.getPlayers().size());
            c.disconnect();
            long deadline = System.currentTimeMillis() + 4000;
            while (srv.getPlayers().size() > 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            System.out.println("[diag] after disconnect players=" + srv.getPlayers());
            assertEquals("断开后应只剩房主", 1, srv.getPlayers().size());
        } finally {
            srv.stop();
        }
    }

    @Test
    public void hostChat_reachesClients() throws Exception {
        NetHostServer srv = startServer("");
        try {
            Rec r = new Rec();
            NetClient c = join(srv, "", "手机A", r);
            srv.sendHostChat("MC 这边的话");
            long deadline = System.currentTimeMillis() + 3000;
            while (!r.events.stream().anyMatch(e -> e.startsWith("chat:")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue("手机A 应收到来自 MC 房主的聊天",
                    r.events.stream().anyMatch(e -> e.equals("chat:MC玩家A:MC 这边的话")));
            c.disconnect();
        } finally {
            srv.stop();
        }
    }

    // ---------------------------------------------------------------- 伪房主传谱

    private static java.io.BufferedReader reader(java.net.Socket s) throws Exception {
        return new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
    }

    private static void send(java.net.Socket s, String line) throws Exception {
        java.io.OutputStream o = s.getOutputStream();
        o.write((line + "\n").getBytes("UTF-8"));
        o.flush();
    }

    /** 一直读到某一行包含 [needle]；超时/断开返回 null */
    private static String readUntil(java.io.BufferedReader r, String needle, int ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            String line = r.readLine();
            if (line == null) return null;
            if (line.contains(needle)) return line;
        }
        return null;
    }

    /**
     * 「你写我打」的局控消息（ask 开始请求 / start 开始 / frame 框色）MC 服务端必须**原样转发**，
     * 否则 MC 当房主时另一端根本收不到开始请求，表现为"关窗就自己开始、别人不动"。
     */
    @Test
    public void coopControlMessages_relayed() throws Exception {
        NetHostServer srv = startServer("");
        java.net.Socket a = null, b = null;
        try {
            a = new java.net.Socket("127.0.0.1", srv.getPort());
            a.setSoTimeout(5000);
            java.io.BufferedReader ra = reader(a);
            send(a, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机A\"}");
            assertNotNull(readUntil(ra, "joinok", 3000));

            b = new java.net.Socket("127.0.0.1", srv.getPort());
            b.setSoTimeout(5000);
            java.io.BufferedReader rb = reader(b);
            send(b, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机B\"}");
            assertNotNull(readUntil(rb, "joinok", 3000));

            send(a, "{\"t\":\"ask\",\"delay\":1.0}");
            assertNotNull("ask 没有转发", readUntil(rb, "\"t\":\"ask\"", 4000));

            send(a, "{\"t\":\"start\",\"delay\":0.5}");
            assertNotNull("start 没有转发", readUntil(rb, "\"t\":\"start\"", 4000));

            send(a, "{\"t\":\"frame\",\"i\":1,\"c\":1}");
            assertNotNull("frame 没有转发", readUntil(rb, "\"t\":\"frame\"", 4000));
        } finally {
            for (java.net.Socket s : new java.net.Socket[]{a, b}) {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
            }
            srv.stop();
        }
    }

    /**
     * 伪房主传谱（房主是 MC）：服务端必须**把谱面转发给其它客户端**，
     * 并且**之后加入的人 joinok 里直接带这份谱面**（用户点名：「所有客户端谱子立即同步」）。
     */
    @Test
    public void chart_fromPseudoHost_relayedToOthers_andInJoinOk() throws Exception {
        NetHostServer srv = startServer("");
        java.net.Socket a = null, b = null, cSock = null;
        try {
            a = new java.net.Socket("127.0.0.1", srv.getPort());
            a.setSoTimeout(5000);
            java.io.BufferedReader ra = reader(a);
            send(a, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机A\"}");
            assertNotNull("手机A 没收到 joinok", readUntil(ra, "joinok", 3000));

            b = new java.net.Socket("127.0.0.1", srv.getPort());
            b.setSoTimeout(5000);
            java.io.BufferedReader rb = reader(b);
            send(b, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机B\"}");
            assertNotNull("手机B 没收到 joinok", readUntil(rb, "joinok", 3000));

            // A 传谱 → B 应收到转发（d 是 JSON 字符串，里面带我们的标记）
            send(a, "{\"t\":\"chart\",\"d\":\"PAYLOAD-ZZZ\"}");
            String got = readUntil(rb, "PAYLOAD-ZZZ", 4000);
            assertNotNull("手机B 应收到伪房主传的谱面", got);
            assertTrue("转发的必须是 chart 消息", got.contains("\"t\":\"chart\""));

            // 之后进来的人：joinok 里就得有这份谱面
            cSock = new java.net.Socket("127.0.0.1", srv.getPort());
            cSock.setSoTimeout(5000);
            java.io.BufferedReader rc = reader(cSock);
            send(cSock, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机C\"}");
            String okC = readUntil(rc, "joinok", 3000);
            assertNotNull("手机C 没收到 joinok", okC);
            assertTrue("joinok 应带上房间当前谱面", okC.contains("PAYLOAD-ZZZ"));
        } finally {
            for (java.net.Socket s : new java.net.Socket[]{a, b, cSock}) {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
            }
            srv.stop();
        }
    }

    /**
     * 伪房主被选中之后：MC 端**只提供服务器、不再算房主**（用户点名）。
     * ⇒ 传谱的人拿到 ph 广播，后进来的人 joinok 里也带 ph。
     */
    @Test
    public void chart_fromPseudoHost_broadcastsPh() throws Exception {
        NetHostServer srv = startServer("");
        java.net.Socket a = null, b = null, cSock = null;
        try {
            a = new java.net.Socket("127.0.0.1", srv.getPort());
            a.setSoTimeout(5000);
            java.io.BufferedReader ra = reader(a);
            send(a, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机A\"}");
            assertNotNull(readUntil(ra, "joinok", 3000));

            b = new java.net.Socket("127.0.0.1", srv.getPort());
            b.setSoTimeout(5000);
            java.io.BufferedReader rb = reader(b);
            send(b, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机B\"}");
            assertNotNull(readUntil(rb, "joinok", 3000));

            assertEquals("一开始没有伪房主", -1, srv.getPseudoHostId());
            send(a, "{\"t\":\"chart\",\"d\":\"PAYLOAD-ZZZ\"}");
            assertNotNull("手机A 应收到 ph 广播", readUntil(ra, "\"t\":\"ph\"", 4000));
            assertNotNull("手机B 应收到 ph 广播", readUntil(rb, "\"t\":\"ph\"", 4000));
            assertEquals("传谱的人 = 伪房主（id 1）", 1, srv.getPseudoHostId());

            cSock = new java.net.Socket("127.0.0.1", srv.getPort());
            cSock.setSoTimeout(5000);
            java.io.BufferedReader rc = reader(cSock);
            send(cSock, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机C\"}");
            String okC = readUntil(rc, "joinok", 3000);
            assertNotNull(okC);
            assertTrue("joinok 里的 ph 应是 1", okC.contains("\"ph\":1"));
        } finally {
            for (java.net.Socket s : new java.net.Socket[]{a, b, cSock}) {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
            }
            srv.stop();
        }
    }

    /**
     * 伪房主关房：其他人必须**收到 bye 并全部退出**，服务端自己也停（用户点名：
     * 「伪房主左滑蓝色项目关闭房间时，其他客户端（FRPHI 和 MC）应该都退出房间」）。
     */
    @Test
    public void pseudoHost_closeRoom_kicksEveryone() throws Exception {
        NetHostServer srv = startServer("");
        java.net.Socket a = null, b = null;
        try {
            a = new java.net.Socket("127.0.0.1", srv.getPort());
            a.setSoTimeout(5000);
            java.io.BufferedReader ra = reader(a);
            send(a, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机A\"}");
            assertNotNull(readUntil(ra, "joinok", 3000));

            b = new java.net.Socket("127.0.0.1", srv.getPort());
            b.setSoTimeout(5000);
            java.io.BufferedReader rb = reader(b);
            send(b, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机B\"}");
            assertNotNull(readUntil(rb, "joinok", 3000));

            send(a, "{\"t\":\"chart\",\"d\":\"PAYLOAD-ZZZ\"}");
            assertNotNull(readUntil(ra, "\"t\":\"ph\"", 4000));

            send(a, "{\"t\":\"closeroom\"}");
            String bye = readUntil(rb, "\"t\":\"bye\"", 4000);
            assertNotNull("伪房主关房后，其他人应收到 bye", bye);
            assertTrue("bye 里要说明是伪房主关的房", bye.contains("伪房主"));

            long deadline = System.currentTimeMillis() + 3000;
            while (srv.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertFalse("服务端应已关闭", srv.isRunning());
        } finally {
            for (java.net.Socket s : new java.net.Socket[]{a, b}) {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
            }
            srv.stop();
        }
    }

    /** 不是伪房主的人发 closeroom / kickmc 一律无效（权限只在伪房主手里）。 */
    @Test
    public void nonPseudoHost_cannotCloseRoom() throws Exception {
        NetHostServer srv = startServer("");
        java.net.Socket a = null, b = null;
        try {
            a = new java.net.Socket("127.0.0.1", srv.getPort());
            a.setSoTimeout(5000);
            java.io.BufferedReader ra = reader(a);
            send(a, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机A\"}");
            assertNotNull(readUntil(ra, "joinok", 3000));

            b = new java.net.Socket("127.0.0.1", srv.getPort());
            b.setSoTimeout(5000);
            java.io.BufferedReader rb = reader(b);
            send(b, "{\"t\":\"join\",\"pw\":\"\",\"name\":\"手机B\"}");
            assertNotNull(readUntil(rb, "joinok", 3000));

            // 房间里还没人传谱（pseudoHostId = -1）⇒ B 关不了
            send(b, "{\"t\":\"closeroom\"}");
            Thread.sleep(300);
            assertTrue("没人传谱时不该能关房", srv.isRunning());

            // A 传谱成为伪房主，B（id 2）仍然关不了
            send(a, "{\"t\":\"chart\",\"d\":\"PAYLOAD-ZZZ\"}");
            assertNotNull(readUntil(ra, "\"t\":\"ph\"", 4000));
            send(b, "{\"t\":\"kickmc\"}");
            Thread.sleep(300);
            assertTrue("只有伪房主能踢 MC", srv.isRunning());
        } finally {
            for (java.net.Socket s : new java.net.Socket[]{a, b}) {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
            }
            srv.stop();
        }
    }
}