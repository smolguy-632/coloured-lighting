package com.realdiplad.colouredlighting.client.shadow;

import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/**
 * Draws shadowed surfaces as dark, translucent quads after the world renders.
 *
 * This runs on Fabric's world-render event, after Sodium has drawn the terrain,
 * so there is no mixin into Sodium's chunk renderer.
 */
public final class ShadowRenderer {
	/** Lifts quads slightly off the surface to avoid z-fighting. */
	private static final double LIFT = 0.002;
	/** Shadow darkness, 0 to 255. */
	private static final int ALPHA = 110;

	private ShadowRenderer() {
	}

	public static void draw(WorldRenderContext context, List<SunShadowMap.ShadowCell> cells) {
		if (cells.isEmpty()) {
			return;
		}

		MatrixStack matrices = context.matrixStack();
		Vec3d cam = context.camera().getPos();

		matrices.push();
		matrices.translate(-cam.x, -cam.y, -cam.z);
		var matrix = matrices.peek().getPositionMatrix();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buffer = Tessellator.getInstance().getBuffer();
		buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

		for (SunShadowMap.ShadowCell cell : cells) {
			float x0 = cell.x();
			float x1 = cell.x() + 1.0F;
			float z0 = cell.z();
			float z1 = cell.z() + 1.0F;
			float y = (float) (cell.y() + LIFT);

			buffer.vertex(matrix, x0, y, z0).color(0, 0, 0, ALPHA).next();
			buffer.vertex(matrix, x0, y, z1).color(0, 0, 0, ALPHA).next();
			buffer.vertex(matrix, x1, y, z1).color(0, 0, 0, ALPHA).next();
			buffer.vertex(matrix, x1, y, z0).color(0, 0, 0, ALPHA).next();
		}

		BufferRenderer.drawWithGlobalProgram(buffer.end());

		RenderSystem.enableCull();
		RenderSystem.disableBlend();
		matrices.pop();
	}
}
