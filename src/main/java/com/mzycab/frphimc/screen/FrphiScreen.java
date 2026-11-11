package com.mzycab.frphimc.screen;

import com.mzycab.frphimc.client.Config;
import com.mzycab.frphimc.client.FrphiManager;
import com.mzycab.frphimc.net.NetClient;
import com.mzycab.frphimc.net.NetHostServer;

import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * FRPHIMC 联机界面（多人游戏界面点「FRPHIMC 联机」打开）。
 *
 * 排版（用户点名重写）：左右两栏，各自「字段标签 → 输入框 → 按钮」，
 * 标签一律画在输入框**正上方**（老版把「地址」画在框里面、密码没有标签、状态行压玩家列表）。
 * （左右两栏的栏标题和顶部副标题已按用户要求删除。）
 *
 *   左栏                       右栏
 *   ├ 地址                     ├ 端口 / 房间密码
 *   ├ 房间密码                 ├ [开房间 / 关闭房间]
 *   ├ [连接 / 断开]            └ [快速服务端]
 *   └ [快速客户端]
 *   下面统一：状态行 → 地址提示 → 玩家列表
 */
public class FrphiScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget addressBox;
    private TextFieldWidget passwordBox;
    private TextFieldWidget hostPortBox;
    private TextFieldWidget hostPwBox;
    private ButtonWidget connectBtn;
    private ButtonWidget hostBtn;
    private ButtonWidget quickServerBtn;
    private TextFieldWidget activeField = null;

    /** 布局（init 算一次，render 用同一套，保证文字和框严格对齐） */
    private int colW;
    private int lx;
    private int rx;
    private int y0;

    private static final int ROW_H = 20;
    private static final int GAP = 12;

    public FrphiScreen(Screen parent) {
        super(Text.literal("FRPHIMC 联机"));
        this.parent = parent;
    }

    @Override
    public void init() {
        Config cfg = FrphiManager.INSTANCE.getConfig();

        colW = Math.min(220, (this.width - 40 - GAP) / 2);
        int totalW = colW * 2 + GAP;
        lx = (this.width - totalW) / 2;
        rx = lx + colW + GAP;
        y0 = 32;                                   // 两栏标题的 y

        // ---------------- 左栏：连 FRPHI ----------------
        int ly = y0 + 12;
        this.addressBox = new TextFieldWidget(this.textRenderer, lx, ly, colW, ROW_H, Text.literal("地址"));
        this.addressBox.setMaxLength(128);
        this.addressBox.setPlaceholder(Text.literal("IP:端口 / frphi://…"));
        // 默认地址跟着本机走（用户点名）：没填过就填本机 IP:端口；本机 IP 变了也跟着换
        if (cfg.address == null || cfg.address.trim().isEmpty()) {
            cfg.address = localDefaultAddress(cfg);
            cfg.addressAuto = true;
        } else if (cfg.addressAuto) {
            String auto = localDefaultAddress(cfg);
            if (!auto.equals(cfg.address)) cfg.address = auto;
        }
        this.addressBox.setText(cfg.address);
        this.addressBox.setChangedListener(s -> {
            cfg.address = s;
            cfg.addressAuto = false;          // 自己改过就不再自动跟随
        });
        this.addDrawableChild(this.addressBox);

        int py = ly + ROW_H + 18;
        this.passwordBox = new TextFieldWidget(this.textRenderer, lx, py, colW, ROW_H, Text.literal("密码"));
        this.passwordBox.setMaxLength(8);
        this.passwordBox.setPlaceholder(Text.literal("房间密码（可留空）"));
        this.passwordBox.setText(cfg.password);
        this.passwordBox.setChangedListener(s -> cfg.password = s);
        this.addDrawableChild(this.passwordBox);

        int lby = py + ROW_H + 8;
        this.connectBtn = ButtonWidget.builder(Text.literal("连接"), b -> onConnectPressed())
                .dimensions(lx, lby, colW, ROW_H).build();
        this.addDrawableChild(this.connectBtn);

        ButtonWidget quickClientBtn = ButtonWidget.builder(Text.literal("快速客户端"), b -> {
                    if (this.client != null) this.client.setScreen(new QuickScanScreen(this));
                })
                .dimensions(lx, lby + ROW_H + 4, colW, ROW_H).build();
        this.addDrawableChild(quickClientBtn);

        // ---------------- 右栏：开房间 ----------------
        int ry = y0 + 12;
        int portW = Math.min(64, colW / 3);
        int pwW = colW - portW - 6;
        this.hostPortBox = new TextFieldWidget(this.textRenderer, rx, ry, portW, ROW_H, Text.literal("端口"));
        this.hostPortBox.setMaxLength(5);
        this.hostPortBox.setText(cfg.hostPort <= 0 ? "25566" : String.valueOf(cfg.hostPort));
        this.hostPortBox.setChangedListener(s -> {
            try {
                cfg.hostPort = Integer.parseInt(s.trim());
            } catch (Exception ignored) {
            }
        });
        this.addDrawableChild(this.hostPortBox);

        this.hostPwBox = new TextFieldWidget(this.textRenderer, rx + portW + 6, ry, pwW, ROW_H, Text.literal("房间密码"));
        this.hostPwBox.setMaxLength(8);
        this.hostPwBox.setPlaceholder(Text.literal("密码（可留空）"));
        this.hostPwBox.setText(cfg.hostPassword);
        this.hostPwBox.setChangedListener(s -> cfg.hostPassword = s);
        this.addDrawableChild(this.hostPwBox);

        int rby = ry + ROW_H + 18 + ROW_H + 8;
        this.hostBtn = ButtonWidget.builder(Text.literal("开房间"), b -> onHostPressed())
                .dimensions(rx, rby, colW, ROW_H).build();
        this.addDrawableChild(this.hostBtn);

        this.quickServerBtn = ButtonWidget.builder(Text.literal("快速服务端"), b -> {
                    FrphiManager m = FrphiManager.INSTANCE;
                    if (m.isHosting()) {
                        m.closeRoom();
                        return;
                    }
                    m.quickHost(parsePort(this.hostPortBox.getText()));
                    m.getConfig().save();
                })
                .dimensions(rx, rby + ROW_H + 4, colW, ROW_H).build();
        this.addDrawableChild(this.quickServerBtn);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> close())
                .dimensions(this.width / 2 - 60, this.height - 28, 120, ROW_H).build());

        this.setInitialFocus(this.addressBox);
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p > 0 && p <= 65535) return p;
        } catch (Exception ignored) {
        }
        return 25566;
    }

    /** 本机默认地址：本机 IP + 当前端口（用户点名「mc 的默认地址跟随本机」） */
    private static String localDefaultAddress(Config cfg) {
        int port = cfg.hostPort > 0 ? cfg.hostPort : 25566;
        String ip = NetHostServer.localIp();
        if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
        return ip + ":" + port;
    }

    private void onConnectPressed() {
        FrphiManager m = FrphiManager.INSTANCE;
        if (m.isConnected()) {
            m.disconnect();
            return;
        }
        String addr = this.addressBox.getText();
        m.connect(addr, this.passwordBox.getText());
        Config cfg = m.getConfig();
        cfg.address = NetClient.normalizeAddress(addr);
        cfg.password = this.passwordBox.getText();
        cfg.save();
    }

    private void onHostPressed() {
        FrphiManager m = FrphiManager.INSTANCE;
        if (m.isHosting()) {
            m.closeRoom();
            return;
        }
        m.hostRoom(parsePort(this.hostPortBox.getText()), this.hostPwBox.getText());
        m.getConfig().save();
    }

    @Override
    public void tick() {
        FrphiManager m = FrphiManager.INSTANCE;
        if (this.connectBtn != null) {
            this.connectBtn.setMessage(Text.literal(m.isConnected() ? "断开" : "连接"));
        }
        if (this.hostBtn != null) {
            this.hostBtn.setMessage(Text.literal(m.isHosting() ? "关闭房间" : "开房间"));
        }
        if (this.quickServerBtn != null) {
            this.quickServerBtn.setMessage(Text.literal(m.isQuickHosting() ? "关闭快速" : "快速服务端"));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.activeField != null && this.activeField.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.activeField != null && this.activeField.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.activeField = null;
        for (TextFieldWidget f : new TextFieldWidget[]{this.addressBox, this.passwordBox,
                this.hostPortBox, this.hostPwBox}) {
            if (f != null && f.mouseClicked(mouseX, mouseY, button)) {
                this.activeField = f;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 统一的小灰标签（画在输入框正上方） */
    private void label(DrawContext ctx, String s, int x, int y) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), x, y, 0xFF9AA5B1);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        int cw = this.width / 2;
        FrphiManager m = FrphiManager.INSTANCE;

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("FRPHIMC 联机"), cw, 12, 0xFFFFFF);

        // 字段标签
        int portW = Math.min(64, colW / 3);
        label(context, "地址", lx, y0 + 2);
        label(context, "房间密码", lx, y0 + 12 + ROW_H + 8);
        label(context, "端口", rx, y0 + 2);
        label(context, "房间密码", rx + portW + 6, y0 + 2);

        super.render(context, mouseX, mouseY, delta);

        // ---------------- 状态 + 提示 ----------------
        int iy = y0 + 12 + ROW_H + 18 + ROW_H + 8 + ROW_H + 4 + ROW_H + 16;
        context.fill(lx, iy - 6, rx + colW, iy - 5, 0x40FFFFFF);

        int sc = m.isInRoom() ? 0xFF55FF55 : 0xFFE0E0E0;
        context.drawTextWithShadow(this.textRenderer, Text.literal("状态：" + m.getStatus()), lx, iy, sc);

        int hy = iy + 12;
        if (m.isHosting()) {
            String addr = NetHostServer.localIp() + ":" + m.getHost().getPort();
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("FRPHI 填：").formatted(Formatting.GREEN)
                            .append(Text.literal(addr).formatted(Formatting.YELLOW)),
                    lx, hy, 0xFFFFFF);
            hy += 11;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("同 Wi-Fi 直连；跨网用 openfrp 映射的地址"), lx, hy, 0x808080);
            hy += 11;
        }

        // ---------------- 玩家列表 ----------------
        List<NetClient.Player> ps = m.roomPlayers();
        if (!ps.isEmpty()) {
            int bottom = this.height - 34;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("玩家 " + ps.size() + " 人").formatted(Formatting.GOLD), lx, hy, 0xFFFFFF);
            hy += 11;
            int selfId = m.isHosting() ? 0 : m.getClient().getMyId();
            int phId = m.getPseudoHostId();
            for (NetClient.Player p : ps) {
                if (hy + 9 > bottom) break;
                StringBuilder sb = new StringBuilder("· ").append(p.name);
                if (!p.ip.isEmpty()) sb.append("  ").append(p.ip);
                // 有伪房主之后 MC 端只是服务器，不再算房主（用户点名）
                if (p.id == 0 && phId < 0) sb.append("  [房主]");
                if (p.id == phId) sb.append("  [伪房主]");
                if (p.id == selfId) sb.append("  [我]");
                context.drawTextWithShadow(this.textRenderer, Text.literal(sb.toString()), lx + 4, hy, 0xFFAAE0FF);
                hy += 10;
            }
        }
    }

    @Override
    public void close() {
        FrphiManager.INSTANCE.getConfig().save();
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
