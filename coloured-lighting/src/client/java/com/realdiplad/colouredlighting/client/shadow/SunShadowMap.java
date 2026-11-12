package com.realdiplad.colouredlighting.client.shadow;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Works out which ground-level cells inside one chunk are in the sun's shadow.
 *
 * Instead of marching a ray per cell, each row of columns is swept with a
 * running maximum: a cell is shadowed when some column further toward the sun
 * rises above the cell's surface after subtracting the distance slope. That is
 * O(1) per column - and because the sweep reads heightmaps of the chunks lying
 * toward the sun, shadows stretch across the whole render distance with no
 * radius limit. Columns whose topmost block is a light source (torch, lantern,
 * glowstone...) are treated as one block taller so they cast their own shadow.
 */
public final class SunShadowMap {
	/** Chunk edge length in blocks. */
	static final int CHUNK_SIZE = 16;

	private SunShadowMap() {
	}

	/** A single shadowed surface cell. x and z are block coords, y is the surface height. */
	public record ShadowCell(int x, double y, int z) {
	}

	/**
	 * Computes the shadowed surface cells for the chunk at {@code (chunkX, chunkZ)}.
	 *
	 * @param dirX        x component of the direction from a surface toward the sun
	 * @param dirY        y component of that direction (positive while the sun is up)
	 * @param viewDistance max chunks to search toward the sun for distant casters
	 */
	public static List<ShadowCell> computeChunk(ClientWorld world, int chunkX, int chunkZ,
			double dirX, double dirY, int viewDistance) {
		int baseX = chunkX << 4;
		int baseZ = chunkZ << 4;
		int size = CHUNK_SIZE;
		int bottomY = world.getBottomY();
		// Rise of the sun ray per horizontal block; the sun sits due east or west
		// in vanilla, so at midday the slope approaches vertical.
		double slope = Math.abs(dirX) < 1.0E-6 ? 1.0E9 : Math.abs(dirY / dirX);
		boolean sunEast = dirX > 0.0;

		WorldChunk chunk = world.getChunk(chunkX, chunkZ);
		if (chunk == null) {
			return List.of();
		}

		// Top-face height of every column in this chunk.
		int[] heights = new int[size * size];
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int gx = 0; gx < size; gx++) {
			int x = baseX + gx;
			for (int gz = 0; gz < size; gz++) {
				int h = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, baseZ + gz) + 1;
				if (h > bottomY) {
					// A light source resting on the surface casts its own shadow.
					cursor.set(x, h, baseZ + gz);
					if (world.getBlockState(cursor).getLuminance() > 0) {
						h += 1;
					}
				}
				heights[gx * size + gz] = h;
			}
		}

		List<ShadowCell> out = new ArrayList<>();
		for (int gz = 0; gz < size; gz++) {
			int z = baseZ + gz;
			double running = seedFrom(world, chunkX, z, sunEast, slope, viewDistance, bottomY);

			if (sunEast) {
				// Casters lie east: sweep from the eastern edge back west.
				for (int gx = size - 1; gx >= 0; gx--) {
					int h = heights[gx * size + gz];
					if (h > bottomY && running >= h - 1.0E-6) {
						out.add(new ShadowCell(baseX + gx, h, z));
					}
					running = Math.max(h, running) - slope;
				}
			} else {
				// Casters lie west: sweep from the western edge east.
				for (int gx = 0; gx < size; gx++) {
					int h = heights[gx * size + gz];
					if (h > bottomY && running >= h - 1.0E-6) {
						out.add(new ShadowCell(baseX + gx, h, z));
					}
					running = Math.max(h, running) - slope;
				}
			}
		}
		return out;
	}

	/**
	 * Walking toward the sun across loaded chunks, returns the best
	 * {@code height - distance * slope} found at the edge of this chunk - the
	 * running seed the row sweep continues from. Stops at the render distance.
	 */
	private static double seedFrom(ClientWorld world, int chunkX, int z, boolean sunEast,
			double slope, int viewDistance, int bottomY) {
		double best = Double.NEGATIVE_INFINITY;
		int edge = (chunkX << 4) + (sunEast ? SunShadowMap.CHUNK_SIZE - 1 : 0);
		for (int k = 1; k <= viewDistance * SunShadowMap.CHUNK_SIZE; k++) {
			int x = sunEast ? edge + k : edge - k;
			if (Math.abs((x >> 4) - chunkX) > viewDistance) {
				break;
			}
			WorldChunk neighbor = world.getChunk(x >> 4, z >> 4);
			if (neighbor == null) {
				break;
			}
			int h = neighbor.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z) + 1;
			double value = h - k * slope;
			if (value > best) {
				best = value;
			}
			if (best < bottomY && h <= bottomY) {
				// The seed has decayed below the world and no caster is near.
				break;
			}
		}
		return best;
	}
}
