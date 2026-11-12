package com.realdiplad.colouredlighting.client.shadow;

/**
 * Precomputed shadow lengths keyed by time of day.
 *
 * The table has one entry per 1/64th of a day. Each entry is the integer
 * horizontal shadow length (in blocks) that a 1-block-tall caster would throw
 * at that time. A value of 0 means the sun is below the horizon.
 * Looking this up is much cheaper than doing the trig every time.
 */
final class ShadowLengthTable {
	static final int ENTRIES = 64;
	static final int MAX_LENGTH = 24;

	private static final int[] LENGTHS = new int[ENTRIES];

	static {
		for (int i = 0; i < ENTRIES; i++) {
			double angle = (i + 0.5) / ENTRIES * Math.PI * 2.0;
			double sunHeight = Math.cos(angle);
			double sunAcross = Math.abs(Math.sin(angle));

			if (sunHeight < 0.05) {
				LENGTHS[i] = 0;
			} else {
				int length = (int) Math.ceil(sunAcross / sunHeight);
				LENGTHS[i] = Math.min(MAX_LENGTH, Math.max(1, length));
			}
		}
	}

	private ShadowLengthTable() {
	}

	/** @param skyAngle vanilla sky angle in [0, 1), where 0 is noon */
	static int lengthFor(float skyAngle) {
		int index = Math.floorMod((int) Math.floor(skyAngle * ENTRIES), ENTRIES);
		return LENGTHS[index];
	}
}
