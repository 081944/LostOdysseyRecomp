package io.github.freefrank.lostodyssey;

public final class CompanionSnapshotTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(!CompanionSnapshot.waiting().hasPlayer(), "unknown map has no player");
        check(!new CompanionSnapshot("", "", new float[] {0, 1, Float.NaN}, new String[0]).hasPlayer(), "NaN rejected");
        check(!new CompanionSnapshot("", "", new float[] {Float.MAX_VALUE, 0, 0}, new String[0]).hasPlayer(), "extreme position rejected");
        check(!new CompanionSnapshot("", "", new float[] {0, 1, 2}, new String[] {"missing"}).hasPlayer(), "mismatched POI rejected");
        float[] positions = {-100, -200, 30, 900, 500, 50};
        String[] labels = {"出口"};
        CompanionSnapshot snapshot = new CompanionSnapshot("中文地图", "", positions, labels);
        positions[0] = 9999; labels[0] = "changed";
        check(snapshot.positions[0] == -100 && snapshot.labels[0].equals("出口"), "snapshot owns its data");
        float[] bounds = snapshot.bounds();
        for (int p = 0; p < snapshot.positions.length; p += 3) {
            check(snapshot.positions[p] >= bounds[0] && snapshot.positions[p] <= bounds[0] + bounds[2], "X visible");
            check(snapshot.positions[p + 1] >= bounds[1] && snapshot.positions[p + 1] <= bounds[1] + bounds[2], "Y visible");
        }
        CompanionSnapshot alone = new CompanionSnapshot("", "", new float[] {10, 10, 0}, new String[0]);
        check(alone.hasPlayer() && alone.bounds()[2] >= 512, "lone player has usable scale");
        String[] many = new String[65];
        check(!new CompanionSnapshot("", "", new float[198], many).hasPlayer(), "payload is bounded");
        System.out.println("CompanionSnapshot checks passed");
    }
}
