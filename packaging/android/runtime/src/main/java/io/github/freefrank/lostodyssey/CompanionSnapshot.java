package io.github.freefrank.lostodyssey;

/** Owned data copied on the guest thread; no pointers or commands cross this boundary. */
final class CompanionSnapshot {
    final String title, status;
    final float[] positions;
    final String[] labels;

    // Called by JNI. Position zero is the player; the rest correspond to labels.
    CompanionSnapshot(String title, String status, float[] positions, String[] labels) {
        this.title = title == null ? "等待地图" : title;
        this.status = status == null ? "等待游戏数据" : status;
        boolean valid = positions != null && labels != null && labels.length <= 64
            && positions.length == 3 * (labels.length + 1);
        if (valid) for (float value : positions) {
            if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) valid = false;
        }
        this.positions = valid ? positions.clone() : new float[0];
        this.labels = valid ? labels.clone() : new String[0];
    }

    boolean hasPlayer() { return positions.length >= 3; }

    // Use one scale on both axes, with a minimum span so a lone point stays visible.
    float[] bounds() {
        if (!hasPlayer()) return new float[] {-256, -256, 512};
        float minX = positions[0], maxX = minX, minY = positions[1], maxY = minY;
        for (int i = 3; i < positions.length; i += 3) {
            minX = Math.min(minX, positions[i]); maxX = Math.max(maxX, positions[i]);
            minY = Math.min(minY, positions[i + 1]); maxY = Math.max(maxY, positions[i + 1]);
        }
        float span = Math.max(512, Math.max(maxX - minX, maxY - minY) * 1.15f);
        return new float[] {(minX + maxX) / 2 - span / 2, (minY + maxY) / 2 - span / 2, span};
    }

    static CompanionSnapshot waiting() { return new CompanionSnapshot(null, null, null, null); }
    static native void nativeSetEnabled(boolean enabled);
    static native CompanionSnapshot nativeRead();
}
