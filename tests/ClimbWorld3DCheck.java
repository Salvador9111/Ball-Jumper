import com.example.skyboundjumper.ClimbWorld3D;

/** Run against the compiled pure Kotlin world; does not need an Android device. */
public final class ClimbWorld3DCheck {
    private static final float DT = 1f / 120f;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static ClimbWorld3D.Block next(ClimbWorld3D w) {
        ClimbWorld3D.Block best = null;
        for (ClimbWorld3D.Block b : w.getBlocks()) {
            if (b.getZ() > w.getZ() + .4f && (best == null || b.getZ() < best.getZ())) best = b;
        }
        return best;
    }
    private static float steer(ClimbWorld3D w) {
        ClimbWorld3D.Block target = next(w);
        if (target == null) return 0;
        // Aim for the clear left landing lane on hazard blocks. This intentionally
        // adapts to the platform width as late-game spikes grow.
        float desired = ClimbWorld3D.Companion.tileCenterX(target, target.getPathTile());
        return Math.max(-1f, Math.min(1f, (desired - w.getX()) * 1.5f - w.getVx() * .15f));
    }
    public static void main(String[] args) throws Exception {
        ClimbWorld3D w = new ClimbWorld3D(42L);
        for (ClimbWorld3D.Block block : w.getBlocks()) {
            if (block.getY() < ClimbWorld3D.DIFFICULTY_HEIGHT) {
                require(block.getKind() == 0 &&
                        Math.abs(block.getWidth() - ClimbWorld3D.START_BLOCK_WIDTH) < .001f,
                    "Platforms below 1,000 points must stay wide and static");
            }
        }
        ClimbWorld3D randomized = new ClimbWorld3D(77L);
        float firstRunX = randomized.block(4).getBaseX();
        randomized.reset(null);
        require(Math.abs(firstRunX - randomized.block(4).getBaseX()) > .001f,
            "Every reset must produce a newly randomized platform route");
        require(Math.abs(w.getPaceMultiplier()-ClimbWorld3D.START_PACE)<.0001f,
            "A new run must begin at the slightly faster starting pace");
        for (ClimbWorld3D.Block block : w.getBlocks()) {
            require(!block.getTimeBonus(), "Endless Run must never generate clock pickups");
        }
        ClimbWorld3D timed = new ClimbWorld3D(91L);
        timed.setTimeTrial(true);
        timed.reset(null);
        int previousClock = -1;
        int clocks = 0;
        for (ClimbWorld3D.Block block : timed.getBlocks()) {
            if (!block.getTimeBonus()) continue;
            require(block.getId() >= 10, "The first Time Trial clock must not arrive too early");
            require(!block.getCoin() && !block.getSpike(),
                "A clock pickup needs a clean, readable platform");
            if (previousClock >= 0) require(block.getId() - previousClock >= 16,
                "Clock pickups must retain their balance cooldown");
            previousClock = block.getId();
            clocks++;
        }
        require(clocks >= 1, "A Time Trial opening route must contain a useful clock pickup");
        require(ClimbWorld3D.Companion.spikeScaleFor(19) == 1f &&
            ClimbWorld3D.Companion.spikeScaleFor(250) > 1.2f &&
            ClimbWorld3D.Companion.spikeScaleFor(1000) <= 1.22f,
            "Spikes must grow gradually from a wide base and remain capped");
        for (int id : new int[] {19, 250, 1000}) {
            float width = ClimbWorld3D.MIN_BLOCK_WIDTH;
            float depth = 2.8f;
            float scale = ClimbWorld3D.Companion.spikeScaleFor(id);
            float radius = ClimbWorld3D.SPIKE_RADIUS * scale;
            float half = ClimbWorld3D.Companion.spikeHalfWidthFor(width, id);
            float halfDepth = ClimbWorld3D.Companion.spikeHalfDepthFor(depth, id);
            require(half + radius <= width * .5f,
                "Every rendered spike footprint must remain on its platform");
            require(halfDepth + radius <= depth * .5f,
                "Every rendered spike footprint must remain inside platform depth");
            int columns = ClimbWorld3D.Companion.spikeColumnCountFor(width, id);
            int rows = ClimbWorld3D.Companion.spikeRowCountFor(depth, id);
            float pitch = half * 2f / (columns - 1);
            float rowPitch = halfDepth * 2f / (rows - 1);
            require(pitch >= radius * 2f,
                "Adjacent spike meshes must not intersect or flicker");
            require(rowPitch >= radius * 2f,
                "Adjacent spike rows must not intersect or flicker");
        }
        boolean rose = false, fell = false;
        for (int i = 0; i < 360; i++) {
            float oldY = w.getY();
            w.update(DT, steer(w), 60, false);
            rose |= w.getY() > oldY; fell |= w.getY() < oldY;
        }
        require(rose && fell && w.getZ() > 12f && w.getLandings() >= 3,
            "A steered opening must jump forward, rise, descend and land");
        w.reset(null);
        for (int i = 0; i < 60; i++) w.update(DT, 0, 60, false);
        float initialPace=w.getPaceMultiplier();
        require(Math.abs(w.getY() - (ClimbWorld3D.RADIUS + ClimbWorld3D.LAUNCH*initialPace*.5f -
            .5f*ClimbWorld3D.GRAVITY*initialPace*initialPace*.25f)) < .001f,
            "Free flight must follow the analytic gravity parabola");
        w.reset(null);
        for (int i = 0; i < 50; i++) w.update(DT, 1, 60, false);
        require(w.getX() > .9f && w.getX() < 1.2f, "Default steering must move deliberately, not dart sideways");
        float releaseX = w.getX();
        for (int i = 0; i < 24; i++) w.update(DT, 0, 60, false);
        require(w.getVx() < .06f && w.getX() - releaseX < .16f, "Releasing steering must brake without excessive drift");
        for (int i = 0; i < 12; i++) w.update(DT, -1, 60, false);
        require(w.getVx() < -1.6f, "Opposite thumb must reverse steering promptly at the reduced speed");
        for (int i = 0; i < 2000 && !w.getDead(); i++) w.update(DT, 1, 60, false);
        require(w.getDead(), "Missing the route must end the run");
        float record = w.getMaxHeight();
        w.recover();
        require(!w.getDead() && w.getProtection() && w.getMaxHeight() == record, "Continue must restore safely without clearing progress");
        for (long seed = 0; seed < 24; seed++) {
            w = new ClimbWorld3D(seed);
            for (int step = 0; step < 18000 && !w.getDead(); step++)
                w.update(DT, steer(w), 60, false);
            require(!w.getDead() && w.getLandings() > 100, "Unreachable generated route, seed " + seed +
                " landing " + w.getLandings() + " spikes=" + w.getHitSpikes() + " pace=" + w.getPaceMultiplier());
            require(w.getPaceMultiplier() > 1.08f && w.getPaceMultiplier() <= ClimbWorld3D.MAX_PACE,
                "Pace must rise gradually and remain capped");
            require(w.getBlocks().length == 30, "World memory must remain bounded");
            for (ClimbWorld3D.Block b : w.getBlocks()) {
                require(b.getKind() == 0 || b.getKind() == 1, "Only permanent static/moving blocks may spawn");
                require(b.getWidth() >= ClimbWorld3D.MIN_BLOCK_WIDTH && b.getWidth() <= ClimbWorld3D.START_BLOCK_WIDTH,
                    "Platform widths must narrow within fair limits");
                require(!b.getSpike() || !b.getCoin(), "Coins must never overlap spikes");
                if (b.getSpike()) {
                    float scale = ClimbWorld3D.Companion.spikeScaleFor(b.getId());
                    float radius = ClimbWorld3D.SPIKE_RADIUS * scale;
                    float half = ClimbWorld3D.Companion.spikeHalfWidthFor(b.getWidth(), b.getId());
                    float halfDepth = ClimbWorld3D.Companion.spikeHalfDepthFor(b.getDepth(), b.getId());
                    require(b.getTileCount() > 1 && b.getFeatureTile() != b.getPathTile(),
                        "A spike row must always retain a separate safe route tile");
                    require(half + radius <= b.getWidth() * .5f,
                        "Spike bed must end inside the platform width");
                    require(halfDepth + radius <= b.getDepth() * .5f,
                        "Spike bed must end inside the platform depth");
                }
            }
            int landings = w.getLandings(), moving = w.getMovingLandings(), precise = w.getPreciseLandings();
            w.recover();
            require(w.getLandings() == landings && w.getMovingLandings() == moving && w.getPreciseLandings() == precise,
                "Continue must preserve every landing mission metric");
        }
        w = new ClimbWorld3D(73L);
        for (int i = 0; i < 1200 && !w.getDead(); i++) w.update(DT, steer(w), 60, false);
        require(w.getCoinEvents() > 0, "3D coins must be collectible");
        require(w.getMovingLandings() > 0 && w.getPreciseLandings() > 0, "Replacement achievement metrics must advance");
        ClimbWorld3D.Block spikeBlock = null;
        for (long seed = 0; seed < 100 && spikeBlock == null; seed++) {
            ClimbWorld3D candidate = new ClimbWorld3D(seed);
            for (ClimbWorld3D.Block block : candidate.getBlocks()) {
                if (block.getSpike()) { w = candidate; spikeBlock = block; break; }
            }
        }
        require(spikeBlock != null, "Generated routes must contain avoidable spike blocks");
        java.lang.reflect.Method hitsSpike = ClimbWorld3D.class.getDeclaredMethod(
            "hitsSpikeBed", ClimbWorld3D.Block.class, float.class, float.class);
        hitsSpike.setAccessible(true);
        float spikeX = ClimbWorld3D.Companion.tileCenterX(spikeBlock, spikeBlock.getFeatureTile());
        require((Boolean) hitsSpike.invoke(w, spikeBlock, spikeX, spikeBlock.getZ()),
            "The complete visible spike block must be lethal");
        float safeX = ClimbWorld3D.Companion.tileCenterX(spikeBlock, spikeBlock.getPathTile());
        require(!(Boolean) hitsSpike.invoke(w, spikeBlock, safeX, spikeBlock.getZ()),
            "The designated route tile beside the spike block must remain safe");
        w.reset(null);
        require(w.getLandings() == 0 && w.getMovingLandings() == 0 && w.getPreciseLandings() == 0 &&
            w.getPaceMultiplier() == ClimbWorld3D.START_PACE,
            "New run must reset landing counts and pace");
        System.out.println("PASS: analytic arcs, steering/braking/reversal, fall death, Continue metrics, 24 routes, permanent blocks, coins and restart.");
    }
}
