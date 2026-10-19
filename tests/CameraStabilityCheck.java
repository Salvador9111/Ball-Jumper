import com.example.skyboundjumper.ClimbWorld3D;

/** Camera must remain laterally locked unless the player is actively steering. */
public final class CameraStabilityCheck {
    private static final float DT = 1f / 120f;
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ClimbWorld3D world = new ClimbWorld3D(719L);
        float idleEye = world.getCameraX();
        float idleAim = world.getCameraAimX();
        for (int i = 0; i < 80; i++) world.update(DT, 0f, 60, false);
        require(world.getCameraX() == idleEye, "Idle camera eye drifted");
        require(world.getCameraAimX() == idleAim, "Idle camera aim drifted");
        require(world.getCameraBob() == 0f, "Camera bob was not disabled");

        for (int i = 0; i < 45; i++) world.update(DT, 1f, 60, false);
        require(Math.abs(world.getCameraX() - idleEye) > .001f, "Steering did not move camera");
        float releasedEye = world.getCameraX();
        float releasedAim = world.getCameraAimX();
        for (int i = 0; i < 20; i++) world.update(DT, 0f, 60, false);
        require(world.getCameraX() == releasedEye, "Camera continued after release");
        require(world.getCameraAimX() == releasedAim, "Camera aim continued after release");
        System.out.println("PASS: idle/released lateral camera lock and zero shake.");
    }
}
