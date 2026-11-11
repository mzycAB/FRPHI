package com.mzycab.frphimc.net;

import com.mzycab.frphimc.net.MiniJson;
import com.mzycab.frphimc.net.NetClient;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 协议级验证：NetClient（MC 模组）与 FRPHI Coop 协议互通。 */
public class NetClientTest {

    private static final class Rec implements NetClient.Listener {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        int myId = -1;
        List<NetClient.Player> playersAtConnect;

        @Override
        public void onConnected(int myId, List<NetClient.Player> players) {
            this.myId = myId;
            this.playersAtConnect = players;
            events.add("connected:" + myId);
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

    private static String sendJoinAndAwait(FakeFrphiServer srv, NetClient c, Rec r, long sec) throws Exception {
        c.connect("127.0.0.1", srv.port, "1234", "Steve");
        assertTrue("joinok 未收到", r.connected.await(sec, TimeUnit.SECONDS));
        return srv.received.poll(1, TimeUnit.SECONDS);
    }

    @Test
    public void handshake_joinok_players() throws Exception {
        try (FakeFrphiServer srv = new FakeFrphiServer()) {
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            String joinLine = sendJoinAndAwait(srv, c, r, 5);
            assertNotNull("客户端没发 join", joinLine);
            Map<?, ?> j = (Map<?, ?>) MiniJson.parse(joinLine);
            assertEquals("join", j.get("t"));
            assertEquals("1234", j.get("pw"));
            assertEquals("Steve", j.get("name"));

            assertEquals(1, r.myId);
            assertNotNull(r.playersAtConnect);
            assertEquals(1, r.playersAtConnect.size());
            assertEquals(0, r.playersAtConnect.get(0).id);
            assertEquals("房主手机", r.playersAtConnect.get(0).name);
            assertTrue(r.events.contains("players:1") || r.events.get(0).startsWith("connected"));
        }
    }

    @Test
    public void playersBroadcast_updatesList() throws Exception {
        try (FakeFrphiServer srv = new FakeFrphiServer()) {
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            sendJoinAndAwait(srv, c, r, 5);
            srv.send("{\"t\":\"players\",\"p\":[{\"id\":0,\"name\":\"房主\",\"ip\":\"1.1.1.1\"},"
                    + "{\"id\":1,\"name\":\"Steve\",\"ip\":\"2.2.2.2\"}]}");
            long deadline = System.currentTimeMillis() + 3000;
            while ((c.getPlayers().size() < 2 || !r.events.contains("players:2"))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, c.getPlayers().size());
            assertEquals("Steve", c.getPlayers().get(1).name);
            assertTrue(r.events.contains("players:2"));
        }
    }

    @Test
    public void chat_incoming_shown() throws Exception {
        try (FakeFrphiServer srv = new FakeFrphiServer()) {
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            sendJoinAndAwait(srv, c, r, 5);
            srv.send("{\"t\":\"chat\",\"n\":\"房主手机\",\"d\":\"你好，MC\"}");
            long deadline = System.currentTimeMillis() + 3000;
            while (!r.events.stream().anyMatch(e -> e.startsWith("chat:")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("chat:房主手机:你好，MC", r.events.stream().filter(e -> e.startsWith("chat:")).findFirst().orElse(""));
        }
    }

    @Test
    public void chat_outgoing_sentAsLine() throws Exception {
        try (FakeFrphiServer srv = new FakeFrphiServer()) {
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            sendJoinAndAwait(srv, c, r, 5);
            c.sendChat("MC 大家好");
            String line = srv.received.poll(3, TimeUnit.SECONDS);
            assertNotNull("没收到 MC 发的聊天", line);
            Map<?, ?> m = (Map<?, ?>) MiniJson.parse(line);
            assertEquals("chat", m.get("t"));
            assertEquals("MC 大家好", m.get("d"));
        }
    }

    @Test
    public void joinerr_reports() throws Exception {
        try (FakeFrphiServer srv = new FakeFrphiServer()) {
            srv.badPassword = true;
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            c.connect("127.0.0.1", srv.port, "wrong", "Steve");
            assertTrue("joinerr 没收到", r.closed.await(5, TimeUnit.SECONDS));
            assertTrue(r.events.stream().anyMatch(e -> e.startsWith("closed:加入失败：密码错误")));
        }
    }

    @Test
    public void bye_closes() throws Exception {
        try (FakeFrphiServer srv = new FakeFrphiServer()) {
            Rec r = new Rec();
            NetClient c = new NetClient(r);
            sendJoinAndAwait(srv, c, r, 5);
            srv.send("{\"t\":\"bye\",\"msg\":\"房主关闭了房间\"}");
            assertTrue("bye 没触发关闭", r.closed.await(5, TimeUnit.SECONDS));
            assertTrue(r.events.stream().anyMatch(e -> e.startsWith("closed:房主关闭了房间")));
        }
    }

    @Test
    public void normalizeAddress_forms() {
        assertEquals("192.168.1.5:25566", NetClient.normalizeAddress("192.168.1.5:25566"));
        assertEquals("192.168.1.5:25566", NetClient.normalizeAddress("frphi://192.168.1.5:25566"));
        assertEquals("192.168.1.5:25566", NetClient.normalizeAddress("frphi:192.168.1.5:25566"));
        assertEquals("my.frphi.cn:9001", NetClient.normalizeAddress("  frphi://my.frphi.cn:9001  "));
        assertEquals("", NetClient.normalizeAddress("   "));
    }
}