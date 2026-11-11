package com.mzycab.frphimc.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mzycab.frphimc.net.NetClient;
import com.mzycab.frphimc.screen.FrphiScreen;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

/** FRPHIMC 客户端入口：注册命令 + 多人游戏界面按钮 + A+Tab 玩家列表。 */
public final class FRPHIMCClient implements ClientModInitializer {

    /** A+Tab 只在"按下那一刻"触发一次（按住不放不重复开窗） */
    private boolean altTabHeld = false;

    @Override
    public void onInitializeClient() {
        addMultiplayerButton();
        addPlayerListHotkey();
        registerCommands();
    }

    /**
     * A + Tab = 打开 FRPHI 玩家列表窗口（用户点名，原来是 Tab + Alt）。
     * 用轮询而不是 KeyBinding：Tab 在原版里是"按住看玩家列表"，组合键更容易冲突。
     */
    private void addPlayerListHotkey() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long handle = client.getWindow().getHandle();
            boolean tab = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_TAB);
            boolean a = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_A);
            boolean down = tab && a;
            if (down && !this.altTabHeld) {
                this.altTabHeld = true;
                if (client.currentScreen == null) {
                    client.setScreen(new com.mzycab.frphimc.screen.PlayerListScreen());
                }
            } else if (!down) {
                this.altTabHeld = false;
            }
        });
    }

    /**
     * 联机（多人游戏）界面："FRPHI联机" **始终固定在右上角**（用户点名）。
     * 只认这个界面 + 屏幕自身尺寸，**和界面里其它任何按钮/布局都没有关系**：
     * 不遍历 children、不读「返回」的坐标或尺寸、不挪动任何已有按钮。
     */
    private void addMultiplayerButton() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof MultiplayerScreen)) return;
            int w = 74, h = 20;                 // 固定尺寸
            int x = scaledWidth - w - 4;         // 右上角：只用屏幕宽高，不看任何别的控件
            int y = 4;
            screen.addDrawableChild(
                    ButtonWidget.builder(Text.literal("FRPHI联机"), button ->
                                    MinecraftClient.getInstance().setScreen(new FrphiScreen(screen)))
                            .dimensions(x, y, w, h)
                            .build());
        });
    }

    /** /frphi 客户端命令：status / list / connect <地址> / disconnect / host [端口] / close / mute / hud。 */
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("frphi")
                    .then(ClientCommandManager.literal("status").executes(FrphiCommands::status))
                    .then(ClientCommandManager.literal("list").executes(FrphiCommands::list))
                    .then(ClientCommandManager.literal("connect")
                            .then(ClientCommandManager.argument("address", StringArgumentType.greedyString())
                                    .executes(FrphiCommands::connect)))
                    .then(ClientCommandManager.literal("disconnect").executes(FrphiCommands::disconnect))
                    .then(ClientCommandManager.literal("host")
                            .executes(ctx -> FrphiCommands.host(ctx, 25566))
                            .then(ClientCommandManager.argument("port", StringArgumentType.string())
                                    .executes(ctx -> {
                                        int p = 25566;
                                        try {
                                            p = Integer.parseInt(StringArgumentType.getString(ctx, "port"));
                                        } catch (NumberFormatException ignored) {
                                        }
                                        return FrphiCommands.host(ctx, p);
                                    })))
                    .then(ClientCommandManager.literal("close").executes(FrphiCommands::close))
                    .then(ClientCommandManager.literal("mute").executes(FrphiCommands::toggleMute))
                    .then(ClientCommandManager.literal("hud").executes(FrphiCommands::toggleHud)));
        });
    }

    private static final class FrphiCommands {
        static int status(CommandContext<FabricClientCommandSource> ctx) {
            FrphiManager m = FrphiManager.INSTANCE;
            ctx.getSource().sendFeedback(Text.literal("FRPHIMC: " + m.getStatus()));
            return 1;
        }

        static int list(CommandContext<FabricClientCommandSource> ctx) {
            FrphiManager m = FrphiManager.INSTANCE;
            List<NetClient.Player> ps = m.getClient().getPlayers();
            if (!m.isConnected() || ps.isEmpty()) {
                ctx.getSource().sendFeedback(Text.literal("FRPHIMC: 房间暂无玩家"));
                return 1;
            }
            StringBuilder sb = new StringBuilder("FRPHIMC 房间 (").append(ps.size()).append(" 人)：");
            int selfId = m.getClient().getMyId();
            int phId = m.getPseudoHostId();
            for (NetClient.Player p : ps) {
                sb.append(' ').append(p.name);
                if (p.id == 0 && phId < 0) sb.append("[房主]");
                if (p.id == phId) sb.append("[伪房主]");
                if (p.id == selfId) sb.append("[我]");
            }
            ctx.getSource().sendFeedback(Text.literal(sb.toString()));
            return 1;
        }

        static int connect(CommandContext<FabricClientCommandSource> ctx) {
            String addr = StringArgumentType.getString(ctx, "address");
            FrphiManager.INSTANCE.connect(addr, FrphiManager.INSTANCE.getConfig().password);
            return 1;
        }

        static int disconnect(CommandContext<FabricClientCommandSource> ctx) {
            FrphiManager.INSTANCE.disconnect();
            ctx.getSource().sendFeedback(Text.literal("FRPHIMC: 已断开"));
            return 1;
        }

        static int host(CommandContext<FabricClientCommandSource> ctx, int port) {
            FrphiManager.INSTANCE.hostRoom(port, FrphiManager.INSTANCE.getConfig().password);
            ctx.getSource().sendFeedback(Text.literal("FRPHIMC: " + FrphiManager.INSTANCE.getStatus()));
            return 1;
        }

        static int close(CommandContext<FabricClientCommandSource> ctx) {
            FrphiManager.INSTANCE.closeRoom();
            ctx.getSource().sendFeedback(Text.literal("FRPHIMC: 房间已关闭"));
            return 1;
        }

        static int toggleMute(CommandContext<FabricClientCommandSource> ctx) {
            Config cfg = FrphiManager.INSTANCE.getConfig();
            cfg.mute = !cfg.mute;
            cfg.save();
            ctx.getSource().sendFeedback(Text.literal("FRPHIMC: 聊天转发 " + (cfg.mute ? "关" : "开")));
            return 1;
        }

        static int toggleHud(CommandContext<FabricClientCommandSource> ctx) {
            Config cfg = FrphiManager.INSTANCE.getConfig();
            cfg.hudEnabled = !cfg.hudEnabled;
            cfg.save();
            ctx.getSource().sendFeedback(Text.literal("FRPHIMC: 玩家列表 " + (cfg.hudEnabled ? "开" : "关")));
            return 1;
        }
    }
}