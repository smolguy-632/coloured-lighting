package com.realdiplad.colouredlighting.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.WorldRenderer;

import com.realdiplad.colouredlighting.client.shadow.ShadowManager;

/**
 * Flags chunks whose render mesh is about to be updated.
 *
 * Every block edit and every sky-light change funnels through
 * {@code WorldRenderer#scheduleChunkRender}, so hooking it catches the same
 * moments vanilla refreshes a chunk's light - and the coordinates arrive as
 * chunk coords for x/z, with y being the section index.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Inject(method = "scheduleChunkRender(IIIZ)V", at = @At("HEAD"))
	private void colouredLighting$onChunkRenderScheduled(int chunkX, int sectionY, int chunkZ,
			boolean urgent, CallbackInfo ci) {
		ShadowManager.markChunkDirty(chunkX, chunkZ);
	}
}
