package com.mzycab.frphimc.mixin;

import com.mzycab.frphimc.client.FrphiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;

/** 按下 Tab 打开玩家列表时，在列表下方追加一块 FRPHI 房间玩家。 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void frphimc_appendPlayers(DrawContext context, int scaledWindowWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        FrphiManager.INSTANCE.renderPlayerOverlay((PlayerListHud) (Object) this, context, scaledWindowWidth);
    }
}