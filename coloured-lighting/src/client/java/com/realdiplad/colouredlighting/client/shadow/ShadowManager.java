package com.realdiplad.colouredlighting.client.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.world.ClientWorld;

/**
 * Caches per-chunk shadow cells and decides when to recompute them.
 *
 * A chunk's shadows go stale when:
 * <ul>
 *   <li>its render mesh is scheduled for an update (block edits, light
 *       changes) - detected by {@code WorldRendererMixin};</li>
 *   <li>the quantized sun-angle bucket advances - the same ticks on which
 *       vanilla refreshes sky light across all chunks.</li>
 * </ul>
 * After sundown the sun sweep is replaced by torch-light shadows: rays are
 * marched from every nearby light source and blocked cells draw a shadow.
 * A small per-tick budget keeps the cost spread out; remeshed chunks are
 * serviced first so block edits feel instant.
 */
public final class ShadowManager {
	/** Max chunks recomputed per client tick. */
	private static final int BUDGET_PER_TICK = 8;

	/** Sky angle quantized to 1/64th of a day, matching vanilla light updates. */
	private static final int BUCKETS = 64;

	private static final ShadowManager INSTANCE = new ShadowManager();

	/** One cached chunk column. Fields are volatile; the map is concurrent. */
	private static final class Entry {
		volatile List<SunShadowMap.ShadowCell> cells = List.of();
		volatile int bucket = Integer.MIN_VALUE;
		volatile boolean dirty = true;
	}

	private final Map<Long, Entry> chunks = new ConcurrentHashMap<>();
	private volatile List<SunShadowMap.ShadowCell> renderCells = List.of();

	private ShadowManager() {
	}

	public static ShadowManager get() {
		return INSTANCE;
	}

	private static long pack(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	/** Called from the WorldRenderer mixin whenever a chunk mesh update is scheduled. */
	public static void markChunkDirty(int chunkX, int chunkZ) {
		Entry entry = INSTANCE.chunks.get(pack(chunkX, chunkZ));
		if (entry != null) {
			entry.dirty = true;
		}
	}

	public void onChunkLoad(int chunkX, int chunkZ) {
		chunks.putIfAbsent(pack(chunkX, chunkZ), new Entry());
	}

	public void onChunkUnload(int chunkX, int chunkZ) {
		if (chunks.remove(pack(chunkX, chunkZ)) != null) {
			SunShadowMap.dropLights(chunkX, chunkZ);
			refreshRenderList();
		}
	}

	public void clear() {
		chunks.clear();
		SunShadowMap.clearLights();
		refreshRenderList();
	}

	public List<SunShadowMap.ShadowCell> cells() {
		return renderCells;
	}

	/** Runs each client tick: rebuilds up to {@link #BUDGET_PER_TICK} stale chunks. */
	public void tick(ClientWorld world, int viewDistance) {
		if (!world.getDimension().hasSkyLight()) {
			clear();
			return;
		}

		float skyAngle = world.getSkyAngle(1.0F);
		int bucket = currentBucket(skyAngle);

		double angle = skyAngle * Math.PI * 2.0;
		// At night the sun terms are unused - torch light takes over - but the
		// direction convention is kept so the day path stays identical.
		boolean night = Math.cos(angle) < 0.05;
		double towardX = -Math.sin(angle);
		double towardY = Math.cos(angle);

		int budget = BUDGET_PER_TICK;
		boolean changed = false;

		// Pass 1: freshly remeshed chunks (block edits, light changes).
		for (Map.Entry<Long, Entry> e : chunks.entrySet()) {
			if (budget <= 0) {
				break;
			}
			Entry entry = e.getValue();
			if (entry.dirty) {
				rebuild(world, entry, e.getKey(), viewDistance, bucket, towardX, towardY, night);
				budget--;
				changed = true;
			}
		}

		// Pass 2: everything else whose sun bucket drifted.
		if (budget > 0) {
			for (Map.Entry<Long, Entry> e : chunks.entrySet()) {
				if (budget <= 0) {
					break;
				}
				Entry entry = e.getValue();
				if (entry.bucket != bucket) {
					rebuild(world, entry, e.getKey(), viewDistance, bucket, towardX, towardY, night);
					budget--;
					changed = true;
				}
			}
		}

		if (changed) {
			refreshRenderList();
		}
	}

	private void rebuild(ClientWorld world, Entry entry, long key, int viewDistance, int bucket,
			double towardX, double towardY, boolean night) {
		int chunkX = (int) (key >> 32);
		int chunkZ = (int) (key & 0xFFFFFFFFL);
		entry.cells = SunShadowMap.computeChunk(world, chunkX, chunkZ, towardX, towardY, viewDistance, night);
		entry.bucket = bucket;
		entry.dirty = false;
	}

	private void refreshRenderList() {
		int size = 0;
		for (Entry entry : chunks.values()) {
			size += entry.cells.size();
		}
		if (size == 0) {
			renderCells = List.of();
			return;
		}
		List<SunShadowMap.ShadowCell> out = new ArrayList<>(size);
		for (Entry entry : chunks.values()) {
			out.addAll(entry.cells);
		}
		renderCells = List.copyOf(out);
	}

	private static int currentBucket(float skyAngle) {
		return Math.floorMod((int) Math.floor(skyAngle * BUCKETS), BUCKETS);
	}
}
