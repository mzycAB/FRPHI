package com.mzycab.frphimc.screen;

import com.mzycab.frphimc.client.FrphiManager;
import com.mzycab.frphimc.net.QuickLan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 快速客户端：60 秒内不停搜局域网里广播 qroom 的机器（FRPHI 的「快速服务端」或本模组的「快速服务端」），
 * 发现一个就列一行，点一行 = 用广播里的一次性令牌直接进房。
 */
public class QuickScanScreen extends Screen {

    private static final int ROW_H = 16;
    private static final int WIN = QuickLan.WINDOW_SEC;

    private final Screen parent;
    private final List<QuickLan.Room> rooms = new CopyOnWriteArrayList<>();

    private long endAt;
    private int listX;
    private int listY;
    private int listW;
    private int hoverRow = -1;

    public QuickScanScreen(Screen parent) {
        super(Text.literal("快速客户端"));
        this.parent = parent;
    }

    @Override
    public void init() {
        this.endAt = System.currentTimeMillis() + WIN * 1000L;
        this.listW = Math.min(300, this.width - 40);
        this.listX = (this.width - this.listW) / 2;
        this.listY = 74;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> back())
                .dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());

        FrphiManager.INSTANCE.quickScan(r -> {
            // 网络线程 → 主线程
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc != null) mc.execute(() -> {
                if (!rooms.contains(r)) rooms.add(r);
            });
        }, all -> {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc != null) mc.execute(() -> {
                for (QuickLan.Room r : all) if (!rooms.contains(r)) rooms.add(r);
            });
        });
    }

    private void back() {
        if (this.client != null) this.client.setScreen(this.parent);
    }

    private int remainSec() {
        long left = endAt - System.currentTimeMillis();
        return (int) Math.max(0, (left + 999) / 1000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        int cw = this.width / 2;

        context.drawCenteredTextWithShadow(tr, Text.literal("快速客户端"), cw, 10, 0xFFFFFF);
        int left = remainSec();
        context.drawCenteredTextWithShadow(tr,
                left > 0
                        ? Text.literal("正在搜索局域网（还剩 " + left + " 秒）").formatted(Formatting.AQUA)
                        : Text.literal("搜索结束 · 找到 " + rooms.size() + " 个房间").formatted(Formatting.GRAY),
                cw, 26, 0xFFFFFF);
        context.drawCenteredTextWithShadow(tr,
                Text.literal("对方要开着「快速服务端」，同一 Wi-Fi").formatted(Formatting.GRAY), cw, 40, 0xFFFFFF);

        // 列表（超过可视高度的部分裁掉，不滚动）
        hoverRow = -1;
        int maxRows = Math.max(1, (this.height - 34 - listY) / ROW_H);
        if (rooms.isEmpty()) {
            context.drawCenteredTextWithShadow(tr, Text.literal("（还没搜到房间）"), cw, listY + 6, 0x606060);
        } else {
            for (int i = 0; i < rooms.size() && i < maxRows; i++) {
                QuickLan.Room r = rooms.get(i);
                int y = listY + i * ROW_H;
                boolean hover = mouseX >= listX && mouseX <= listX + listW && mouseY >= y && mouseY < y + ROW_H;
                if (hover) hoverRow = i;
                context.fill(listX, y, listX + listW, y + ROW_H - 1, hover ? 0x66FFFFFF : 0x33FFFFFF);
                String name = r.name.isEmpty() ? "FRPHI 房间" : r.name;
                context.drawTextWithShadow(tr, Text.literal(name), listX + 6, y + 4, 0xFFFFFFFF);
                String addr = r.addr();
                context.drawTextWithShadow(tr, Text.literal(addr),
                        listX + listW - 6 - tr.getWidth(addr), y + 4, 0xFF9AD0FF);
            }
        }

        if (!rooms.isEmpty()) {
            context.drawCenteredTextWithShadow(tr,
                    Text.literal("点一行直接进房").formatted(Formatting.DARK_GRAY), cw, this.height - 40, 0xFFFFFF);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hoverRow >= 0 && hoverRow < rooms.size()) {
            QuickLan.Room r = rooms.get(hoverRow);
            FrphiManager.INSTANCE.joinQuickRoom(r);
            back();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
