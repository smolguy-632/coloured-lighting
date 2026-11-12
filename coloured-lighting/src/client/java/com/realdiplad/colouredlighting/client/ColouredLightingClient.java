package com.realdiplad.colouredlighting.client;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import com.realdiplad.colouredlighting.client.shadow.ShadowRenderer;
import com.realdiplad.colouredlighting.client.shadow.SunShadowMap;

public class ColouredLightingClient implements ClientModInitializer {
	/** Shadows are recalculated every 100 ticks (5 seconds), not every frame. */
	private static final int REFRESH_TICKS = 100;

	private static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.coloured-lighting.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.coloured-lighting"));

	private static boolean enabled = true;
	private static int tickCounter = 0;

	private final SunShadowMap shadows = new SunShadowMap();

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

			if (enabled && tickCounter % REFRESH_TICKS == 0) {
				shadows.rebuild(client.world, client.player.getPos());
			}
			tickCounter++;
		});

		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (enabled) {
				ShadowRenderer.draw(context, shadows.cells());
			}
		});
	}
}
