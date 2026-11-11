package com.mzycab.frphimc.client;

import com.mzycab.frphimc.net.NetClient;
import com.mzycab.frphimc.net.NetHostServer;
import com.mzycab.frphimc.net.QuickLan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;

/**
 * 模组全局状态：一条"连 FRPHI"客户端连接 + 一个"开房给 FRPHI 连"的服务端，
 * 玩家列表、聊天桥、Tab 列表追加绘制、命令。
 *
 * 回调从网络线程进来，一律切回游戏主线程再碰 UI。
 */
public final class FrphiManager {

    public static final FrphiManager INSTANCE = new FrphiManager();

    private final Config config = Config.load();
    private final NetClient client = new NetClient(new Listener());
    private final QuickLan quick = new QuickLan();
    private volatile NetHostServer host;   // 开房时才创建（那时游戏已就绪，能拿玩家名）
    private volatile String status = "未连接";
    private volatile long lastPlayersAt = 0;
    /** 快速服务端当前用的一次性令牌（空 = 不是快速模式） */
    private volatile String quickToken = "";

    private FrphiManager() {
    }

    public Config getConfig() { return config; }
    public NetClient getClient() { return client; }
    public NetHostServer getHost() { return host; }
    public String getStatus() { return status; }

    /** 是否在房间（连上 或 开着房）。 */
    public boolean isInRoom() { return client.isConnected() || isHosting(); }

    public boolean isHosting() { return host != null && host.isRunning(); }

    /** 房里的伪房主 id（-1 = 没有）；有伪房主之后 MC 只提供服务器，不再标「房主」。 */
    public int getPseudoHostId() { return host != null ? host.getPseudoHostId() : -1; }

    public boolean isConnected() { return client.isConnected(); }

    /** 当前可见玩家列表（连接模式 = 服务端房间玩家；开房模式 = 进我房间的 FRPHI 玩家）。 */
    public List<NetClient.Player> roomPlayers() {
        if (isHosting()) return host.getPlayers();
        return client.getPlayers();
    }

    public NetClient.Player self() {
        for (NetClient.Player p : roomPlayers()) {
            if (p.id == (isHosting() ? 0 : client.getMyId())) return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- 客户端模式：连 FRPHI

    /** 连接 FRPHI 房间。host 支持 "ip:port" 或 "frphi://ip:port"。 */
    public void connect(String hostInput, String password) {
        String addr = NetClient.normalizeAddress(hostInput);
        if (addr.isEmpty()) {
            status = "地址为空";
            return;
        }
        String h = addr;
        int port = 25566;
        int idx = addr.lastIndexOf(':');
        if (idx >= 0) {
            String hp = addr.substring(0, idx).trim();
            String pp = addr.substring(idx + 1).trim();
            if (!hp.isEmpty()) h = hp;
            try {
                if (!pp.isEmpty()) port = Integer.parseInt(pp);
            } catch (NumberFormatException ignored) {
            }
        }
        String username = currentName();
        status = "正在连接 " + h + ":" + port + " …";
        client.connect(h, port, password, username);
    }

    public void disconnect() {
        client.disconnect();
        status = "已断开";
        config.save();
    }

    // ---------------------------------------------------------------- 开房模式：FRPHI 连进来

    /** 开房间（MC 当服务器）。返回 null = 成功，否则错误文案。 */
    public String hostRoom(int port, String password) {
        if (host == null) {
            host = new NetHostServer(currentName(), new HostListener());
        }
        String err = host.host(port, password);
        if (err == null) {
            status = "房间已开启 " + NetHostServer.localIp() + ":" + port
                    + (password.isEmpty() ? "（无密码）" : "（" + password.length() + " 位密码）");
        } else {
            status = err;
        }
        return err;
    }

    public void closeRoom() {
        quick.stopBeacon();
        quickToken = "";
        if (host != null) {
            host.stop();
            status = "房间已关闭";
        }
        config.save();
    }

    // ---------------------------------------------------------------- 快速联机（局域网 UDP 发现）

    public QuickLan getQuick() { return quick; }

    public boolean isQuickHosting() { return quick.isBeaconing(); }

    /**
     * 快速服务端：开一间"随机令牌当密码"的房间 + 每 500ms 广播 qroom，
     * 这样 FRPHI 的「快速客户端」和本模组的「快速客户端」都能直接搜到。
     * 返回 null = 成功，否则是错误文案。
     */
    public String quickHost(int port) {
        if (isHosting()) closeRoom();
        String token = QuickLan.newToken();
        String err = hostRoom(port, token);
        if (err != null) return err;
        quickToken = token;
        quick.startBeacon(port, token, currentName());
        status = "快速房间已开启 " + NetHostServer.localIp() + ":" + port + "（60 秒内可被搜到）";
        config.save();
        return null;
    }

    /**
     * 快速客户端：后台扫 [QuickLan.WINDOW_SEC] 秒（用户点名 60 秒）。
     * 回调在网络线程上，调用方自行切回主线程。
     */
    public void quickScan(java.util.function.Consumer<QuickLan.Room> onFound,
                          java.util.function.Consumer<List<QuickLan.Room>> onDone) {
        Thread t = new Thread(() -> quick.scan(QuickLan.WINDOW_SEC, onFound, onDone), "frphimc-quick-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 用发现到的房间直接进房（令牌就是密码）。 */
    public void joinQuickRoom(QuickLan.Room r) {
        if (r == null) return;
        config.address = r.addr();
        config.save();
        connect(r.addr(), r.token);
    }

    // ---------------------------------------------------------------- 聊天

    /** MC 聊天框发了消息：转发给 FRPHI 房间（连接模式发给服务端，开房模式广播给所有 FRPHI 玩家）。 */
    public void onPlayerChat(String text) {
        if (!isInRoom() || config.mute) return;
        if (!text.isEmpty() && text.charAt(0) == '/') return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        if (isHosting()) {
            host.sendHostChat(trimmed);
        } else {
            client.sendChat(trimmed);
        }
        // 本机回显（灰色），确认已发出
        runOnMain(() -> printChat(Text.literal(trimmed).formatted(net.minecraft.util.Formatting.GRAY)));
    }

    /** FRPHI 消息显示到 MC 聊天栏。 */
    private void showFrphiChat(String name, String text) {
        printChat(Text.literal("")
                .append(Text.literal("[FRPHI] ").formatted(net.minecraft.util.Formatting.AQUA))
                .append(Text.literal(name + ": ").formatted(net.minecraft.util.Formatting.YELLOW))
                .append(Text.literal(text).formatted(net.minecraft.util.Formatting.WHITE)));
    }

    private void printChat(Text msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;
        ChatHud hud = mc.inGameHud.getChatHud();
        if (hud != null) hud.addMessage(msg);
    }

    // ---------------------------------------------------------------- Tab 玩家列表追加绘制

    /** PlayerListHud.render 尾部调用：Tab 列表下方追加 FRPHI 房间玩家区（连接/开房都可以）。 */
    public void renderPlayerOverlay(PlayerListHud hud, DrawContext context, int scaledWindowWidth) {
        if (!isInRoom() || !config.hudEnabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        List<NetClient.Player> ps = new ArrayList<>(roomPlayers());
        if (ps.isEmpty()) return;

        List<String> rows = new ArrayList<>();
        rows.add(isHosting() ? "FRPHIMC 房间（开房中 · " + ps.size() + " 人）"
                : "FRPHIMC 房间 (" + ps.size() + " 人)  " + client.getHostAddr());
        int selfId = isHosting() ? 0 : client.getMyId();
        int phId = getPseudoHostId();
        for (NetClient.Player p : ps) {
            String mark = (p.id == 0 && phId < 0) ? "房主" : "";
            if (p.id == phId) mark = "伪房主";
            if (p.id == selfId) mark = mark.isEmpty() ? "我" : "我·" + mark;
            rows.add("  " + p.name + (p.ip.isEmpty() ? "" : "  " + p.ip) + (mark.isEmpty() ? "" : "  [" + mark + "]"));
        }

        int rowH = 9;
        int w = 0;
        for (String r : rows) w = Math.max(w, tr.getWidth(r));
        w = Math.min(w + 16, scaledWindowWidth - 8);
        int h = rows.size() * rowH + 8;
        int screenH = context.getScaledWindowHeight();
        int x = (scaledWindowWidth - w) / 2;
        int y = screenH - h - 8;

        context.fill(x, y, x + w, y + h, 0xCC1B1B1B);
        context.fill(x, y, x + 2, y + h, 0xFF2D7FF9);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF2D7FF9);
        int ty = y + 5;
        for (int i = 0; i < rows.size(); i++) {
            int color = i == 0 ? 0xFFE5B94A : 0xFFFFFFFF;
            context.drawTextWithShadow(tr, rows.get(i), x + 8, ty, color);
            ty += rowH;
        }
    }

    // ---------------------------------------------------------------- 状态 / 工具

    private static String currentName() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getSession() != null) {
                String n = mc.getSession().getUsername();
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        return "MC";
    }

    private void runOnMain(Runnable r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        if (mc.isOnThread()) r.run(); else mc.execute(r);
    }

    /** NetClient 回调（连 FRPHI 时的远端消息）。 */
    private final class Listener implements NetClient.Listener {
        @Override
        public void onConnected(int myId, List<NetClient.Player> players) {
            runOnMain(() -> {
                status = "已连接 " + client.getHostAddr();
                lastPlayersAt = System.currentTimeMillis();
            });
        }

        @Override
        public void onPlayers(List<NetClient.Player> players) {
            runOnMain(() -> lastPlayersAt = System.currentTimeMillis());
        }

        @Override
        public void onChat(String name, String text) {
            runOnMain(() -> showFrphiChat(name, text));
        }

        @Override
        public void onClosed(String reason) {
            runOnMain(() -> {
                status = reason;
                printChat(Text.literal("FRPHIMC：").formatted(net.minecraft.util.Formatting.RED)
                        .append(Text.literal(reason)));
            });
        }
    }

    /** NetHostServer 回调（开房时 FRPHI 玩家的动作）。 */
    private final class HostListener implements NetHostServer.HostListener {
        @Override
        public void onPlayers(List<NetClient.Player> players) {
            runOnMain(() -> lastPlayersAt = System.currentTimeMillis());
        }

        @Override
        public void onChat(String name, String text) {
            runOnMain(() -> showFrphiChat(name, text));
        }

        @Override
        public void onClosed(String reason) {
            runOnMain(() -> {
                status = reason;
                // 伪房主关房 / 踢 MC：MC 玩家自己也该看到这一局结束了
                printChat(Text.literal("FRPHIMC：").formatted(net.minecraft.util.Formatting.RED)
                        .append(Text.literal(reason)));
            });
        }
    }
}