package com.mzycab.frphimc.mixin;

import com.mzycab.frphimc.client.FrphiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screen.ChatScreen;

/**
 * 游戏聊天框发送消息时，顺带转发一份给 FRPHI（原版发送不拦截、照常进行）。
 *
 * 注意：sendMessage 返回 boolean，@Inject 处理器必须配 CallbackInfoReturnable<Boolean>
 * （写 CallbackInfo 会在运行时 APPLY 阶段抛 InvalidInjectionException 导致整个游戏崩溃）。
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Inject(method = "sendMessage", at = @At("HEAD"))
    private void frphimc_forwardChat(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        FrphiManager.INSTANCE.onPlayerChat(chatText);
    }
}