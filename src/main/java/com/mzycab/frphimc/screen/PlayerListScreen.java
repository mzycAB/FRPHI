package com.mzycab.frphimc.screen;

import com.mzycab.frphimc.client.FrphiManager;
import com.mzycab.frphimc.net.NetClient;

import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * FRPHI 玩家列表查看窗口（游戏里按 A + Tab 打开，用户点名）。
 * 显示房间类型、房间地址、每个玩家的 id / 名字 / IP / 身份标记。
 */
public class PlayerListScreen extends Screen {

    public PlayerListScreen() {
        super(Text.literal("FRPHI 玩家列表"));
    }

    @Override
    public void init() {
        this.addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> close())
                .dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(null);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        int cw = this.width / 2;
        FrphiManager m = FrphiManager.INSTANCE;

        int w = Math.min(320, this.width - 40);
        int x = (this.width - w) / 2;
        int y = 20;

        context.drawCenteredTextWithShadow(tr, Text.literal("FRPHI 玩家列表"), cw, y, 0xFFFFFF);
        y += 16;

        String kind = m.isHosting() ? "开房（MC 是房主）" : (m.isConnected() ? "已连到 FRPHI 房间" : "未进房间");
        context.drawCenteredTextWithShadow(tr,
                Text.literal(kind + " · " + m.getStatus()).formatted(Formatting.GRAY), cw, y, 0xFFFFFF);
        y += 14;

        List<NetClient.Player> ps = m.roomPlayers();
        if (ps.isEmpty()) {
            context.drawCenteredTextWithShadow(tr, Text.literal("（房间里还没有玩家）"), cw, y + 6, 0x707070);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int selfId = m.isHosting() ? 0 : m.getClient().getMyId();
        int rowH = 14;
        int bottom = this.height - 34;
        for (NetClient.Player p : ps) {
            if (y + rowH > bottom) break;
            context.fill(x, y, x + w, y + rowH - 1, 0x33FFFFFF);
            String id = "#" + p.id;
            context.drawTextWithShadow(tr, Text.literal(id), x + 5, y + 3, 0xFF8A93A0);
            context.drawTextWithShadow(tr, Text.literal(p.name), x + 34, y + 3,
                    p.id == selfId ? 0xFFFFFF66 : 0xFFFFFFFF);
            String ip = p.ip == null ? "" : p.ip;
            if (!ip.isEmpty()) {
                context.drawTextWithShadow(tr, Text.literal(ip),
                        x + w - 5 - tr.getWidth(ip) - 62, y + 3, 0xFF9AD0FF);
            }
            int phId = m.getPseudoHostId();
            String tag = (p.id == 0 && phId < 0) ? "房主" : "";
            if (p.id == phId) tag = "伪房主";
            if (p.id == selfId) tag = tag.isEmpty() ? "我" : tag + "·我";
            if (!tag.isEmpty()) {
                context.drawTextWithShadow(tr, Text.literal(tag),
                        x + w - 5 - tr.getWidth(tag), y + 3, 0xFFFFC24A);
            }
            y += rowH;
        }

        super.render(context, mouseX, mouseY, delta);
    }
}
