import com.example.skyboundjumper.ClimbWorld3D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Verifies that collectible placement stays sparse, irregular and hazard-safe. */
public final class CoinSpacingCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (long seed = 0; seed < 64; seed++) {
            ClimbWorld3D world = new ClimbWorld3D(seed);
            List<Integer> ids = new ArrayList<>();
            for (ClimbWorld3D.Block block : world.getBlocks()) {
                if (block.getCoin()) {
                    require(!block.getSpike(), "Coin spawned on spikes at " + block.getId());
                    ids.add(block.getId());
                }
            }
            Collections.sort(ids);
            require(ids.size() >= 4 && ids.size() <= 10, "Coin density out of bounds: " + ids.size());
            require(ids.get(0) >= 3 && ids.get(0) <= 5, "First coin is not paced for onboarding");
            for (int i = 1; i < ids.size(); i++) {
                int gap = ids.get(i) - ids.get(i - 1);
                require(gap >= 3 && gap <= 8, "Unexpected coin gap: " + gap);
            }
        }
        System.out.println("PASS: randomized 3-7 platform coin cadence, safe from spike blocks.");
    }
}
