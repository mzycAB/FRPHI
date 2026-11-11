package com.mzycab.frphimc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 模组配置（config/frphimc.json）：记住上次连接的地址/密码、HUD 与聊天转发开关。
 */
public final class Config {
    public String address = "";
    /** 地址是「跟着本机自动填」的（用户没自己改过）→ 本机 IP 变化时自动更新 */
    public boolean addressAuto = false;
    public String password = "";
    public boolean mute = false;
    public boolean hudEnabled = true;
    /** 开房间用：端口 + 房间密码（上次填的记住，下次进来不用重输） */
    public int hostPort = 25566;
    public String hostPassword = "";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Config load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Config c = GSON.fromJson(json, Config.class);
                if (c != null) return c;
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return new Config();
    }

    public void save() {
        try {
            Files.write(path(), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("frphimc.json");
    }
}