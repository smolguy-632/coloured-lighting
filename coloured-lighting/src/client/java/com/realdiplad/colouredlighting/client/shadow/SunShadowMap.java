package com.realdiplad.colouredlighting.client.shadow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Works out which ground-level cells inside one chunk are in the sun's shadow.
 *
 * This is pure block/voxel logic with no rendering and no Sodium hooks, so it
 * stays independent of Sodium's chunk pipeline. It is meant to be called once
 * per chunk, on demand - the manager decides when.
 */
public final class SunShadowMap {
	/** Chunk edge length in blocks. */
	static final int CHUNK_SIZE = 16;
	/** How far above and below the reference height to look for a walkable surface. */
	static final int VERTICAL_SCAN = 8;
	/** Vertical drift allowed before a cached chunk's shadows go stale. */
	static final int PY_MARGIN = 4;
	/** Tallest block height that can cast a shadow onto a surface. */
	static final int CASTER_HEIGHT = 16;
	/** Ray step size in blocks. */
	static final double STEP = 0.25;
	/** Upper bound on ray steps per surface, so low-sun rays stay cheap. */
	static final int MAX_STEPS = 160;

	/** 4-way neighborhood offsets for region flood-fill. */
	private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	/** A single shadowed surface cell. x and z are block coords, y is the surface height. */
	public record ShadowCell(int x, double y, int z) {
	}

	private SunShadowMap() {
	}

	/**
	 * Computes the shadowed surface cells for every column of the chunk at
	 * {@code (chunkX, chunkZ)}. {@code py} is the vertical center of the scan.
	 */
	public static List<ShadowCell> computeChunk(ClientWorld world, BlockPos.Mutable scratch,
			int chunkX, int chunkZ, int py, double towardSunX, double towardSunY) {
		int baseX = chunkX << 4;
		int baseZ = chunkZ << 4;
		int size = CHUNK_SIZE;
		boolean[] shadowed = new boolean[size * size];
		int[] surfaces = new int[size * size];
		Arrays.fill(surfaces, Integer.MIN_VALUE);

		for (int gx = 0; gx < size; gx++) {
			int x = baseX + gx;
			for (int gz = 0; gz < size; gz++) {
				int z = baseZ + gz;
				int surfaceY = findSurface(world, scratch, x, py, z);
				if (surfaceY == Integer.MIN_VALUE) {
					continue;
				}
				int index = gx * size + gz;
				surfaces[index] = surfaceY;

				// Surface point is the top face of the block at surfaceY.
				double sy = surfaceY + 1.0;
				if (isShadowed(world, scratch, x + 0.5, sy, z + 0.5, towardSunX, towardSunY)) {
					shadowed[index] = true;
				}
			}
		}

		return collectAnchored(world, scratch, baseX, baseZ, py, shadowed, surfaces);
	}

	/** Scans down from above the reference height for a solid block with air above it. */
	private static int findSurface(ClientWorld world, BlockPos.Mutable pos, int x, int py, int z) {
		for (int y = py + VERTICAL_SCAN; y >= py - VERTICAL_SCAN; y--) {
			pos.set(x, y, z);
			BlockState state = world.getBlockState(pos);
			if (state.getCollisionShape(world, pos).isEmpty()) {
				continue;
			}
			pos.set(x, y + 1, z);
			if (world.getBlockState(pos).isAir()) {
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Marches from the surface toward the sun and checks for any blocking block. */
	private static boolean isShadowed(ClientWorld world, BlockPos.Mutable pos,
			double sx, double sy, double sz, double dirX, double dirY) {
		double x = sx;
		double y = sy;
		double z = sz;
		double rise = 0.0;

		for (int step = 0; step < MAX_STEPS && rise < CASTER_HEIGHT; step++) {
			x += dirX * STEP;
			y += dirY * STEP;
			rise = y - sy;

			pos.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
			BlockState state = world.getBlockState(pos);
			if (!state.getCollisionShape(world, pos).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Keeps only shadow cells that belong to a region anchored to a caster.
	 *
	 * A region is anchored when it touches a neighboring column that rises above
	 * the shadowed surface - the base of whatever block casts the shadow. This
	 * drops orphan patches thrown by elevated blocks (say a lone lintel), which
	 * otherwise read as a random floating square on the ground.
	 */
	private static List<ShadowCell> collectAnchored(ClientWorld world, BlockPos.Mutable scratch,
			int baseX, int baseZ, int py, boolean[] shadowed, int[] surfaces) {
		int size = CHUNK_SIZE;
		int total = size * size;
		boolean[] keep = new boolean[total];
		Deque<Integer> queue = new ArrayDeque<>();

		for (int index = 0; index < total; index++) {
			if (!shadowed[index] || keep[index]) {
				continue;
			}
			if (hasTallerNeighbor(world, scratch, index, py, size, surfaces, baseX, baseZ)) {
				// Found an anchored region: flood-fill it from here.
				keep[index] = true;
				queue.add(index);
				while (!queue.isEmpty()) {
					int current = queue.remove();
					int cx = current / size;
					int cz = current % size;
					for (int[] offset : NEIGHBORS) {
						int nx = cx + offset[0];
						int nz = cz + offset[1];
						if (nx < 0 || nx >= size || nz < 0 || nz >= size) {
							continue;
						}
						int neighbor = nx * size + nz;
						if (shadowed[neighbor] && !keep[neighbor]) {
							keep[neighbor] = true;
							queue.add(neighbor);
						}
					}
				}
			}
		}

		List<ShadowCell> out = new ArrayList<>();
		for (int gx = 0; gx < size; gx++) {
			for (int gz = 0; gz < size; gz++) {
				int index = gx * size + gz;
				if (keep[index]) {
					out.add(new ShadowCell(baseX + gx, surfaces[index] + 1.0, baseZ + gz));
				}
			}
		}
		return out;
	}

	/** True when any 4-adjacent column's surface rises above this cell's surface. */
	private static boolean hasTallerNeighbor(ClientWorld world, BlockPos.Mutable scratch,
			int index, int py, int size, int[] surfaces, int baseX, int baseZ) {
		int cx = index / size;
		int cz = index % size;
		int surfaceY = surfaces[index];
		for (int[] offset : NEIGHBORS) {
			int nx = cx + offset[0];
			int nz = cz + offset[1];
			if (nx < 0 || nx >= size || nz < 0 || nz >= size) {
				// Neighboring column outside this chunk: ask the world directly.
				int neighborSurface = findSurface(world, scratch, baseX + nx, py, baseZ + nz);
				if (neighborSurface > surfaceY) {
					return true;
				}
				continue;
			}
			int neighbor = nx * size + nz;
			if (surfaces[neighbor] > surfaceY) {
				return true;
			}
		}
		return false;
	}
}
