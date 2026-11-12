package com.realdiplad.colouredlighting.client.shadow;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Works out which surface cells inside one chunk are in the sun's shadow.
 *
 * Instead of marching a ray per cell, each row of columns is swept with a
 * running maximum: a cell is shadowed when some column further toward the sun
 * rises above the cell's surface after subtracting the distance slope. That is
 * O(1) per column - and because the sweep reads heightmaps of the chunks lying
 * toward the sun, shadows stretch across the whole render distance with no
 * radius limit.
 *
 * A column can expose several surfaces: the heightmap top plus interior floors
 * under overhangs (doorways, porches, glass roofs) within a small depth, so
 * those stay shadowed too.
 *
 * Columns are classified by their topmost block:
 * <ul>
 *   <li>opaque blocks cast full-strength shadows;</li>
 *   <li>leaves cast translucent shadows (light filters through);</li>
 *   <li>glass and other see-through blocks cast nothing - the sweep looks
 *       below them to the first solid block.</li>
 * </ul>
 * Columns whose top block is a light source (torch, lantern...) count as one
 * block taller so they cast their own shadow, day and night.
 */
public final class SunShadowMap {
	/** Chunk edge length in blocks. */
	static final int CHUNK_SIZE = 16;

	/** Interior floors tracked per column, plus how deep the column is scanned. */
	private static final int MAX_SURFACES = 3;
	private static final int MAX_DIG = 32;

	/** Full shadow strength, 0-255, day and night. */
	private static final int FULL_ALPHA = 110;
	private static final int NIGHT_FULL_ALPHA = 60;
	/** Leaf shadows let light through, so they draw lighter. */
	private static final int LEAF_ALPHA = 50;
	private static final int NIGHT_LEAF_ALPHA = 25;

	/** Column tiers. */
	private static final int TIER_OPAQUE = 0;
	private static final int TIER_LEAF = 1;
	private static final int TIER_CLEAR = 2;

	private SunShadowMap() {
	}

	/**
	 * A single shadowed surface cell. x and z are block coords, y is the surface
	 * height and a is the shadow's alpha (0-255).
	 */
	public record ShadowCell(int x, double y, int z, int a) {
	}

	/**
	 * Computes the shadowed surface cells for the chunk at {@code (chunkX, chunkZ)}.
	 *
	 * @param dirX         x component of the direction from a surface toward the sun (or moon)
	 * @param dirY         y component of that direction (positive while the light is up)
	 * @param viewDistance max chunks to search toward the light for distant casters
	 * @param night        true when the light source is the moon (dimmer shadows)
	 */
	public static List<ShadowCell> computeChunk(ClientWorld world, int chunkX, int chunkZ,
			double dirX, double dirY, int viewDistance, boolean night) {
		int baseX = chunkX << 4;
		int baseZ = chunkZ << 4;
		int size = CHUNK_SIZE;
		int bottomY = world.getBottomY();
		// Rise of the light ray per horizontal block, clamped so noon still leaves
		// short shadows instead of the ray going exactly vertical.
		double slope = Math.min(1.0, Math.max(1.0 / 24.0, Math.abs(dirY / dirX)));
		boolean lightEast = dirX > 0.0;
		int fullAlpha = night ? NIGHT_FULL_ALPHA : FULL_ALPHA;
		int leafAlpha = night ? NIGHT_LEAF_ALPHA : LEAF_ALPHA;

		WorldChunk chunk = world.getChunk(chunkX, chunkZ);
		if (chunk == null) {
			return List.of();
		}

		// Per column: up to MAX_SURFACES surface heights, plus the caster height
		// and tier of the topmost shadow-casting block.
		int[] surfaces = new int[size * size * MAX_SURFACES];
		int[] counts = new int[size * size];
		int[] caster = new int[size * size];
		int[] tier = new int[size * size];
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int gx = 0; gx < size; gx++) {
			int x = baseX + gx;
			for (int gz = 0; gz < size; gz++) {
				int z = baseZ + gz;
				int index = gx * size + gz;
				int topBlock = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);

				// Caster: the first block from the top that actually blocks light.
				int cy = topBlock;
				BlockState state = world.getBlockState(cursor.set(x, cy, z));
				int t = tierOf(state);
				while (t == TIER_CLEAR && cy > bottomY) {
					cy--;
					state = world.getBlockState(cursor.set(x, cy, z));
					t = tierOf(state);
				}
				if (t == TIER_CLEAR) {
					continue; // nothing solid anywhere in the column
				}
				caster[index] = cy + 1;
				tier[index] = t;

				// Surfaces: the block above the top, plus interior floors - any
				// see-through block (air, glass, torch...) resting on something
				// solid. Stops after a few surfaces or a small depth.
				int count = 0;
				int y = topBlock + 1;
				state = world.getBlockState(cursor.set(x, y, z));
				while (y > bottomY && count < MAX_SURFACES && topBlock - y <= MAX_DIG) {
					if (tierOf(state) != TIER_OPAQUE) {
						BlockState below = world.getBlockState(cursor.set(x, y - 1, z));
						if (tierOf(below) != TIER_CLEAR) {
							surfaces[index * MAX_SURFACES + count] = y;
							count++;
							if (state.getLuminance() > 0) {
								// A light source resting here casts its own shadow.
								caster[index] = Math.max(caster[index], y + 1);
							}
						}
					}
					y--;
					state = world.getBlockState(cursor.set(x, y, z));
				}
				counts[index] = count;
			}
		}

		List<ShadowCell> out = new ArrayList<>();
		for (int gz = 0; gz < size; gz++) {
			int z = baseZ + gz;
			double[] seed = seedFrom(world, chunkX, z, lightEast, slope, viewDistance, bottomY);
			double runOpaque = seed[0];
			double runLeaf = seed[1];

			// Sweep from the far edge back toward the light.
			for (int step = size - 1; step >= 0; step--) {
				int gx = lightEast ? step : size - 1 - step;
				int index = gx * size + gz;

				for (int s = 0; s < counts[index]; s++) {
					int h = surfaces[index * MAX_SURFACES + s];
					int alpha = 0;
					if (runOpaque >= h - 1.0E-6) {
						alpha = fullAlpha;
					} else if (runLeaf >= h - 1.0E-6) {
						alpha = leafAlpha;
					}
					if (alpha > 0) {
						out.add(new ShadowCell(baseX + gx, h, z, alpha));
					}
				}

				int c = caster[index];
				if (c > bottomY) {
					if (tier[index] == TIER_LEAF) {
						runLeaf = Math.max(runLeaf, c);
					} else {
						runOpaque = Math.max(runOpaque, c);
					}
				}
				runOpaque -= slope;
				runLeaf -= slope;
			}
		}
		return out;
	}

	/**
	 * Walking toward the light across loaded chunks, returns the best
	 * {@code height - distance * slope} found at the edge of this chunk for
	 * opaque and leaf casters - the running seeds the row sweep continues from.
	 * Stops at the render distance.
	 */
	private static double[] seedFrom(ClientWorld world, int chunkX, int z, boolean lightEast,
			double slope, int viewDistance, int bottomY) {
		double bestOpaque = Double.NEGATIVE_INFINITY;
		double bestLeaf = Double.NEGATIVE_INFINITY;
		int edge = (chunkX << 4) + (lightEast ? SunShadowMap.CHUNK_SIZE - 1 : 0);
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int k = 1; k <= viewDistance * SunShadowMap.CHUNK_SIZE; k++) {
			int x = lightEast ? edge + k : edge - k;
			if (Math.abs((x >> 4) - chunkX) > viewDistance) {
				break;
			}
			WorldChunk neighbor = world.getChunk(x >> 4, z >> 4);
			if (neighbor == null) {
				break;
			}
			int top = neighbor.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);
			BlockState state = world.getBlockState(cursor.set(x, top, z));
			int t = tierOf(state);
			while (t == TIER_CLEAR && top > bottomY) {
				top--;
				state = world.getBlockState(cursor.set(x, top, z));
				t = tierOf(state);
			}
			double value = top + 1 - k * slope;
			if (t == TIER_LEAF) {
				if (value > bestLeaf) {
					bestLeaf = value;
				}
			} else if (value > bestOpaque) {
				bestOpaque = value;
			}
			if (bestOpaque < bottomY && bestLeaf < bottomY && top <= bottomY) {
				// The seeds have decayed below the world and no caster is near.
				break;
			}
		}
		return new double[]{bestOpaque, bestLeaf};
	}

	private static int tierOf(BlockState state) {
		if (state.isOpaque()) {
			return TIER_OPAQUE;
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return TIER_LEAF;
		}
		return TIER_CLEAR;
	}
}
