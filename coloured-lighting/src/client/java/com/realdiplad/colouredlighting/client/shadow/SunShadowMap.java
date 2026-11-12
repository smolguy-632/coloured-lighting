package com.realdiplad.colouredlighting.client.shadow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Works out which ground-level block faces are in the sun's shadow.
 *
 * This is pure block/voxel logic with no rendering and no Sodium hooks, so it
 * stays independent of Sodium's chunk pipeline. It is meant to be called
 * occasionally (not every frame): the caller decides how often.
 */
public final class SunShadowMap {
	/** Horizontal scan radius around the player, in blocks. */
	static final int RADIUS = 12;
	/** How far above and below the player to look for a walkable surface. */
	static final int VERTICAL_SCAN = 8;
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

	private volatile List<ShadowCell> cells = List.of();

	public List<ShadowCell> cells() {
		return cells;
	}

	public void clear() {
		cells = List.of();
	}

	/** Recalculates shadows around {@code origin}. Expensive-ish; call on a timer. */
	public void rebuild(ClientWorld world, Vec3d origin) {
		if (!world.getDimension().hasSkyLight()) {
			cells = List.of();
			return;
		}

		float skyAngle = world.getSkyAngle(1.0F);
		if (ShadowLengthTable.lengthFor(skyAngle) == 0) {
			// Night: nothing to shadow.
			cells = List.of();
			return;
		}

		// Direction from a surface toward the sun. Matches vanilla's sun path
		// (rotated about X, then Y), so the sun rises on +X.
		double angle = skyAngle * Math.PI * 2.0;
		double towardSunX = -Math.sin(angle);
		double towardSunY = Math.cos(angle);

		int px = (int) Math.floor(origin.x);
		int py = (int) Math.floor(origin.y);
		int pz = (int) Math.floor(origin.z);

		BlockPos.Mutable pos = new BlockPos.Mutable();
		int size = RADIUS * 2 + 1;
		boolean[] shadowed = new boolean[size * size];
		int[] surfaces = new int[size * size];
		Arrays.fill(surfaces, Integer.MIN_VALUE);

		for (int gx = 0; gx < size; gx++) {
			int x = px - RADIUS + gx;
			for (int gz = 0; gz < size; gz++) {
				int z = pz - RADIUS + gz;
				int surfaceY = findSurface(world, pos, x, py, z);
				if (surfaceY == Integer.MIN_VALUE) {
					continue;
				}
				int index = gx * size + gz;
				surfaces[index] = surfaceY;

				// Surface point is the top face of the block at surfaceY.
				double sx = x + 0.5;
				double sy = surfaceY + 1.0;
				double sz = z + 0.5;

				if (isShadowed(world, pos, sx, sy, sz, towardSunX, towardSunY)) {
					shadowed[index] = true;
				}
			}
		}

		cells = collectAnchored(px - RADIUS, pz - RADIUS, size, shadowed, surfaces);
	}

	/**
	 * Keeps only shadow cells that belong to a region anchored to a caster.
	 *
	 * A region is anchored when it touches a neighboring column that rises above
	 * the shadowed surface - the base of whatever block casts the shadow. This
	 * drops orphan patches thrown by elevated blocks (say a lone lintel), which
	 * otherwise read as a random floating square on the ground.
	 */
	private static List<ShadowCell> collectAnchored(int minX, int minZ, int size,
			boolean[] shadowed, int[] surfaces) {
		int total = size * size;
		boolean[] keep = new boolean[total];
		Deque<Integer> queue = new ArrayDeque<>();

		for (int index = 0; index < total; index++) {
			if (!shadowed[index] || keep[index]) {
				continue;
			}
			if (hasTallerNeighbor(index, size, surfaces)) {
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
					out.add(new ShadowCell(minX + gx, surfaces[index] + 1.0, minZ + gz));
				}
			}
		}
		return out;
	}

	/** True when any 4-adjacent column's surface rises above this cell's surface. */
	private static boolean hasTallerNeighbor(int index, int size, int[] surfaces) {
		int cx = index / size;
		int cz = index % size;
		int surfaceY = surfaces[index];
		for (int[] offset : NEIGHBORS) {
			int nx = cx + offset[0];
			int nz = cz + offset[1];
			if (nx < 0 || nx >= size || nz < 0 || nz >= size) {
				continue;
			}
			int neighbor = nx * size + nz;
			if (surfaces[neighbor] > surfaceY) {
				return true;
			}
		}
		return false;
	}

	/** Scans down from above the player for a solid block with air above it. */
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
}
