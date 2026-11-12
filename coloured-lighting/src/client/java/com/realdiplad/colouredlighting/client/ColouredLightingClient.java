package com.realdiplad.colouredlighting.client;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import com.realdiplad.colouredlighting.client.shadow.ShadowManager;
import com.realdiplad.colouredlighting.client.shadow.ShadowRenderer;

public class ColouredLightingClient implements ClientModInitializer {
	private static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.coloured-lighting.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.coloured-lighting"));

	private static boolean enabled = true;

	private final ShadowManager shadows = ShadowManager.get();

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_KEY.wasPressed()) {
				enabled = !enabled;
			}

			if (client.world == null || client.player == null) {
				shadows.clear();
				return;
			}

			if (enabled) {
				shadows.tick(client.world, client.options.getViewDistance().getValue());
			}
		});

		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
				shadows.onChunkLoad(chunk.getPos().x, chunk.getPos().z));
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
				shadows.onChunkUnload(chunk.getPos().x, chunk.getPos().z));

		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (enabled) {
				ShadowRenderer.draw(context, shadows.cells());
			}
		});
	}
}
