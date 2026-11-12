package com.realdiplad.colouredlighting.client.shadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Works out which surface cells inside one chunk are in shadow.
 *
 * By day the sun sweeps across each row with a running maximum: a cell is
 * shadowed when some column further toward the sun rises above the cell's
 * surface after subtracting the distance slope. That is O(1) per column - and
 * because the sweep reads heightmaps of the chunks lying toward the sun,
 * shadows stretch across the whole render distance with no radius limit.
 *
 * Floating casters (air underneath, e.g. a plank stuck on a wall) are handled
 * as bands: they only block rays crossing their own height range, so the
 * ground under and beside them stays lit and their shadow starts further out.
 *
 * At night the light comes from torches, lanterns and the like: for every
 * surface cell near a light source an exact voxel ray is marched from the
 * light, and cells whose rays are blocked by terrain draw a shadow whose
 * strength falls off with distance.
 *
 * Finally every shadow is softened toward lit neighbours: light bounces off
 * lit ground back into the edges of shadows, so they fade out instead of
 * ending in a hard line.
 *
 * Columns are classified by their topmost block:
 * <ul>
 *   <li>full opaque blocks, and partial ones (slabs, stairs, fences, walls...)
 *       all block light fully;</li>
 *   <li>leaves cast translucent shadows (light filters through);</li>
 *   <li>glass and panes cast nothing - the sweep looks below them to the
 *       first solid block.</li>
 * </ul>
 * Columns whose top block is a light source (torch, lantern...) count as one
 * block taller so they cast their own sun shadow by day.
 */
public final class SunShadowMap {
	/** Chunk edge length in blocks. */
	static final int CHUNK_SIZE = 16;

	/** Interior floors tracked per column, plus how deep the column is scanned. */
	private static final int MAX_SURFACES = 3;
	private static final int MAX_DIG = 32;

	/** Full shadow strength, 0-255. */
	private static final int FULL_ALPHA = 110;
	/** Leaf shadows let light through, so they draw lighter. */
	private static final int LEAF_ALPHA = 50;

	/** Column tiers. */
	private static final int TIER_OPAQUE = 0;
	private static final int TIER_LEAF = 1;
	private static final int TIER_CLEAR = 2;

	/** Light sources found per chunk, shared so lights near chunk borders work. */
	private static final Map<Long, List<LightPos>> LIGHTS = new ConcurrentHashMap<>();

	/** A luminous block: position and light level. */
	private record LightPos(int x, int y, int z, int lum) {
	}

	private SunShadowMap() {
	}

	/**
	 * A single shadowed surface cell. x and z are block coords, y is the surface
	 * height and a is the shadow's alpha (0-255).
	 */
	public record ShadowCell(int x, double y, int z, int a) {
	}

	private static long pack(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	/** Forgets the light sources of an unloaded chunk. */
	public static void dropLights(int chunkX, int chunkZ) {
		LIGHTS.remove(pack(chunkX, chunkZ));
	}

	/** Forgets every light source (world change, shadows toggled off). */
	public static void clearLights() {
		LIGHTS.clear();
	}

	/**
	 * Computes the shadowed surface cells for the chunk at {@code (chunkX, chunkZ)}.
	 *
	 * @param dirX         x component of the direction from a surface toward the sun
	 * @param dirY         y component of that direction (positive while the sun is up)
	 * @param viewDistance max chunks to search toward the sun for distant casters
	 * @param night        true after sundown, when torch light takes over
	 */
	public static List<ShadowCell> computeChunk(ClientWorld world, int chunkX, int chunkZ,
			double dirX, double dirY, int viewDistance, boolean night) {
		int baseX = chunkX << 4;
		int baseZ = chunkZ << 4;
		int size = CHUNK_SIZE;
		int bottomY = world.getBottomY();
		// Rise of the sun ray per horizontal block, clamped so noon still leaves
		// short shadows instead of the ray going exactly vertical.
		double slope = Math.min(1.0, Math.max(1.0 / 24.0, Math.abs(dirY / dirX)));
		boolean sunEast = dirX > 0.0;

		WorldChunk chunk = world.getChunk(chunkX, chunkZ);
		if (chunk == null) {
			return List.of();
		}

		// Per column: up to MAX_SURFACES surface heights, plus the caster height
		// and tier of the topmost shadow-casting block. Floating casters (air
		// below) are kept separate: they only block rays passing through their
		// own height band, not everything below them.
		int[] surfaces = new int[size * size * MAX_SURFACES];
		int[] counts = new int[size * size];
		int[] caster = new int[size * size];
		int[] tier = new int[size * size];
		boolean[] floating = new boolean[size * size];
		int[] fbottom = new int[size * size];
		List<LightPos> lights = new ArrayList<>();
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
				int t = tierOf(state, world, cursor);
				while (t == TIER_CLEAR && cy > bottomY) {
					cy--;
					state = world.getBlockState(cursor.set(x, cy, z));
					t = tierOf(state, world, cursor);
				}
				if (t == TIER_CLEAR) {
					continue; // nothing solid anywhere in the column
				}
				if (tierOf(world.getBlockState(cursor.set(x, cy - 1, z)), world, cursor) == TIER_OPAQUE) {
					// Grounded: solid below, blocks everything under its top.
					caster[index] = cy + 1;
					tier[index] = t;
				} else {
					// Floating: walk down to its real bottom; it only blocks
					// rays crossing its own band.
					int by = cy;
					while (by > bottomY && cy - by < MAX_DIG
							&& tierOf(world.getBlockState(cursor.set(x, by - 1, z)), world, cursor) == TIER_OPAQUE) {
						by--;
					}
					floating[index] = true;
					fbottom[index] = by;
					caster[index] = cy + 1;
					tier[index] = t;
				}
				if (state.getLuminance() > 0) {
					lights.add(new LightPos(x, cy, z, state.getLuminance()));
				}

				// Surfaces: the block above the top, plus interior floors - any
				// see-through block (air, glass, torch...) resting on something
				// solid. Stops after a few surfaces or a small depth.
				int count = 0;
				int y = topBlock + 1;
				state = world.getBlockState(cursor.set(x, y, z));
				while (y > bottomY && count < MAX_SURFACES && topBlock - y <= MAX_DIG) {
					if (state.getLuminance() > 0) {
						lights.add(new LightPos(x, y, z, state.getLuminance()));
					}
					if (tierOf(state, world, cursor) != TIER_OPAQUE) {
						BlockState below = world.getBlockState(cursor.set(x, y - 1, z));
						if (tierOf(below, world, cursor) != TIER_CLEAR) {
							surfaces[index * MAX_SURFACES + count] = y;
							count++;
							if (state.getLuminance() > 0) {
								// A light source resting here casts its own sun shadow.
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
		LIGHTS.put(pack(chunkX, chunkZ), lights);

		if (night) {
			return applyBounce(torchShadows(world, chunkX, chunkZ, surfaces, counts, bottomY, baseX, baseZ));
		}

		List<ShadowCell> out = new ArrayList<>();
		for (int gz = 0; gz < size; gz++) {
			int z = baseZ + gz;
			double[] seed = seedFrom(world, chunkX, z, sunEast, slope, viewDistance, bottomY);
			double runOpaque = seed[0];
			double runLeaf = seed[1];
			// Floating casters met so far in this row: {bottom, top, originGx, alpha}.
			List<double[]> bands = new ArrayList<>();

			// Sweep from the far edge back toward the sun.
			for (int step = size - 1; step >= 0; step--) {
				int gx = sunEast ? step : size - 1 - step;
				int index = gx * size + gz;

				for (int s = 0; s < counts[index]; s++) {
					int h = surfaces[index * MAX_SURFACES + s];
					int alpha = 0;
					if (runOpaque >= h - 1.0E-6) {
						alpha = FULL_ALPHA;
					} else if (runLeaf >= h - 1.0E-6) {
						alpha = LEAF_ALPHA;
					}
					if (alpha == 0) {
						for (double[] band : bands) {
							int d = (int) Math.abs(band[2] - gx);
							if (d == 0) {
								continue;
							}
							// The ray to this cell crosses the caster's band?
							double low = band[0] - d * slope;
							double high = band[1] - d * slope;
							if (h >= low - 1.0E-6 && h < high + 1.0E-6) {
								alpha = (int) band[3];
								break;
							}
						}
					}
					if (alpha > 0) {
						out.add(new ShadowCell(baseX + gx, h, z, alpha));
					}
				}

				int c = caster[index];
				if (c > bottomY) {
					if (floating[index]) {
						bands.add(new double[]{fbottom[index], c, gx,
								tier[index] == TIER_LEAF ? LEAF_ALPHA : FULL_ALPHA});
					} else if (tier[index] == TIER_LEAF) {
						runLeaf = Math.max(runLeaf, c);
					} else {
						runOpaque = Math.max(runOpaque, c);
					}
				}
				runOpaque -= slope;
				runLeaf -= slope;
			}
		}
		return applyBounce(out);
	}

	/**
	 * Softens shadows toward lit neighbours: bounce light from lit ground
	 * brightens shadow edges, so enclosed shadow keeps full strength while
	 * edges fade and lone speckles all but vanish.
	 */
	private static List<ShadowCell> applyBounce(List<ShadowCell> cells) {
		int n = cells.size();
		if (n < 2) {
			return cells;
		}
		HashMap<Long, Integer> shadowed = new HashMap<>(n * 2);
		for (int i = 0; i < n; i++) {
			SunShadowMap.ShadowCell c = cells.get(i);
			shadowed.put(BlockPos.asLong(c.x(), (int) c.y(), c.z()), i);
		}
		List<ShadowCell> out = new ArrayList<>(n);
		for (SunShadowMap.ShadowCell c : cells) {
			int lit = 0;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					if (!shadowed.containsKey(BlockPos.asLong(c.x() + dx, (int) c.y(), c.z() + dz))) {
						lit++;
					}
				}
			}
			// (lit + 2) / 10: enclosed = 1.0, edge = ~0.6, lone speck = 0.2.
			int a = (int) (c.a() * (lit + 2) / 10.0);
			out.add(new ShadowCell(c.x(), c.y(), c.z(), a));
		}
		return out;
	}

	/**
	 * Night pass: for every surface cell, checks the straight rays from each
	 * nearby light source. A cell blocked from stronger light than reaches it
	 * draws a shadow, fainter the further it is from the light.
	 */
	private static List<ShadowCell> torchShadows(ClientWorld world, int chunkX, int chunkZ,
			int[] surfaces, int[] counts, int bottomY, int baseX, int baseZ) {
		List<LightPos> lights = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				List<LightPos> near = LIGHTS.get(pack(chunkX + dx, chunkZ + dz));
				if (near != null) {
					lights.addAll(near);
				}
			}
		}
		List<ShadowCell> out = new ArrayList<>();
		if (lights.isEmpty()) {
			return out;
		}
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int gx = 0; gx < CHUNK_SIZE; gx++) {
			int x = baseX + gx;
			for (int gz = 0; gz < CHUNK_SIZE; gz++) {
				int z = baseZ + gz;
				int index = gx * CHUNK_SIZE + gz;
				for (int s = 0; s < counts[index]; s++) {
					int h = surfaces[index * MAX_SURFACES + s];
					int bestLit = 0;
					int bestBlocked = 0;
					for (LightPos light : lights) {
						int level = light.lum() - (Math.abs(light.x() - x)
								+ Math.abs(light.y() - h) + Math.abs(light.z() - z));
						if (level <= 0) {
							continue;
						}
						if (rayBlocked(world, light, x + 0.5, h, z + 0.5, cursor)) {
							if (level > bestBlocked) {
								bestBlocked = level;
							}
						} else if (level > bestLit) {
							bestLit = level;
						}
					}
					if (bestBlocked > bestLit) {
						out.add(new ShadowCell(x, h, z, Math.min(FULL_ALPHA, bestBlocked * 8)));
					}
				}
			}
		}
		return out;
	}

	/**
	 * Marches an exact voxel ray (Amanatides-Woo) from a light to a surface
	 * point; true if opaque terrain interrupts. Voxel-exact, so rays that graze
	 * block edges cannot flicker between blocked and clear.
	 */
	private static boolean rayBlocked(ClientWorld world, LightPos light,
			double tx, double ty, double tz, BlockPos.Mutable cursor) {
		double sx = light.x() + 0.5;
		double sy = light.y() + 0.7;
		double sz = light.z() + 0.5;
		double dx = tx - sx;
		double dy = ty - sy;
		double dz = tz - sz;
		int bx = light.x();
		int by = light.y();
		int bz = light.z();
		int stepX = dx >= 0 ? 1 : -1;
		int stepY = dy >= 0 ? 1 : -1;
		int stepZ = dz >= 0 ? 1 : -1;
		double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
		double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
		double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
		double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY
				: (stepX > 0 ? bx + 1 - sx : sx - bx) * tDeltaX;
		double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY
				: (stepY > 0 ? by + 1 - sy : sy - by) * tDeltaY;
		double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY
				: (stepZ > 0 ? bz + 1 - sz : sz - bz) * tDeltaZ;
		while (true) {
			double t;
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				bx += stepX;
				t = tMaxX;
				tMaxX += tDeltaX;
			} else if (tMaxY <= tMaxZ) {
				by += stepY;
				t = tMaxY;
				tMaxY += tDeltaY;
			} else {
				bz += stepZ;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
			}
			if (t >= 1.0) {
				return false; // reached the surface point unblocked
			}
			if (world.getBlockState(cursor.set(bx, by, bz)).isOpaque()) {
				return true;
			}
		}
	}

	/**
	 * Walking toward the sun across loaded chunks, returns the best
	 * {@code height - distance * slope} found at the edge of this chunk for
	 * opaque and leaf casters - the running seeds the row sweep continues from.
	 * Stops at the render distance. Floating casters in other chunks are
	 * skipped: their band shadows only apply within their own chunk.
	 */
	private static double[] seedFrom(ClientWorld world, int chunkX, int z, boolean sunEast,
			double slope, int viewDistance, int bottomY) {
		double bestOpaque = Double.NEGATIVE_INFINITY;
		double bestLeaf = Double.NEGATIVE_INFINITY;
		int edge = (chunkX << 4) + (sunEast ? SunShadowMap.CHUNK_SIZE - 1 : 0);
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int k = 1; k <= viewDistance * SunShadowMap.CHUNK_SIZE; k++) {
			int x = sunEast ? edge + k : edge - k;
			if (Math.abs((x >> 4) - chunkX) > viewDistance) {
				break;
			}
			WorldChunk neighbor = world.getChunk(x >> 4, chunkZ(z));
			if (neighbor == null) {
				break;
			}
			int top = neighbor.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);
			BlockState state = world.getBlockState(cursor.set(x, top, z));
			int t = tierOf(state, world, cursor);
			while (t == TIER_CLEAR && top > bottomY) {
				top--;
				state = world.getBlockState(cursor.set(x, top, z));
				t = tierOf(state, world, cursor);
			}
			if (t != TIER_CLEAR
					&& tierOf(world.getBlockState(cursor.set(x, top - 1, z)), world, cursor) == TIER_OPAQUE) {
				double value = top + 1 - k * slope;
				if (t == TIER_LEAF) {
					if (value > bestLeaf) {
						bestLeaf = value;
					}
				} else if (value > bestOpaque) {
					bestOpaque = value;
				}
			}
			if (bestOpaque < bottomY && bestLeaf < bottomY && top <= bottomY) {
				// The seeds have decayed below the world and no caster is near.
				break;
			}
		}
		return new double[]{bestOpaque, bestLeaf};
	}

	private static int chunkZ(int z) {
		return z >> 4;
	}

	private static int tierOf(BlockState state, BlockView world, BlockPos pos) {
		if (state.isOpaque()) {
			return TIER_OPAQUE;
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return TIER_LEAF;
		}
		if (state.getBlock() instanceof TransparentBlock || state.getBlock() instanceof PaneBlock) {
			return TIER_CLEAR;
		}
		// Slabs, stairs, fences, walls and friends block light like full cubes.
		if (!state.getCollisionShape(world, pos).isEmpty()) {
			return TIER_OPAQUE;
		}
		return TIER_CLEAR; // buttons, rails, plants, carpets - no shadow
	}
}
