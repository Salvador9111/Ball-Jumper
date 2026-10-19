# Skybound Jumper: 3D gameplay

The Play action now opens a native OpenGL ES 2.0 scene. Menus, settings,
shop, achievements and the game-over overlay retain their existing UI.

## World and camera

`ClimbWorld3D` owns metre-based X/Y/forward coordinates and a fixed 120 Hz
simulation. Forward distance is mapped to negative Z for rendering, so
screen-right and world-right agree. The perspective camera follows from
behind and above. Its vertical anchor follows the climb baseline, allowing
the ball to rise and fall visibly instead of locking it to a screen position.

Blocks have independently generated widths, depths, thicknesses and heights.
Consecutive positions have bounded lateral shifts. The camera does not define
collision coordinates. Landings sweep the ball's trajectory across a block's
top plane and test the actual X/Z intersection. Missing the route causes a fall.

`ClimbRenderer3D` renders triangle meshes with a depth buffer, bevel normals,
directional lighting, a mobile-tuned 1024-pixel filtered shadow map, analytic contact occlusion
and restrained distance fog. Layered quarry walls occupy world coordinates
and produce real parallax. Constant-width bevels maintain believable scale. Shop material
colours and patterns are applied to a rolling sphere mesh.

## Controls and progression

- Existing left/right buttons or touch zones steer during flight.
- Steering tracks the active finger, hands off when a thumb lifts, and uses padded button hit areas.
- Acceleration has inertia; release/reversal braking is faster to prevent unwanted drift.
- Ball radius is 0.42 m. Gravity is 22 m/s² with a 9.65 m/s launch: firmer arcs with comparable clearance.
- Default lateral speed is 3.44 m/s (sensitivity 60), with slower acceleration and stronger release braking.
- Lateral authority scales with the shorter late-game jump duration, preserving reachable side-to-side distance per jump while the stronger release brake prevents drift.
- Jump cadence begins at 1.07x, grows by 0.1% per landing after the opening five blocks and caps at 1.23x. Launch and gravity scale together to preserve arc height, while forward velocity is solved for the immediate next platform, preventing speed progression from skipping blocks.
- Platforms begin 4.4 m wide and narrow continuously by 0.006 m per generated block, stopping at a fair 3.05 m minimum.
- Spikes remain absent during the beginner-safe opening. From their first appearance, the cluster spans at least half the platform width and grows gradually with progress, capped at 1.22x its initial width and height. It leaves a deliberately tight lane on the left; rendering and collision use the same footprint. Spike platforms never carry coins.
- Spike-platform centre shifts are bounded more tightly than clear platforms, ensuring the reduced landing lane remains physically reachable.
- Moving green platforms use an uninterrupted top surface; the former pair of decorative lines has been removed.
- Spikes are 0.59 m tall; rendering and descending-contact checks share their dimensions.
- Coins use a solid 160-segment minted 3D mesh with completely filled, subtly crowned circular faces, rounded double bevels, a thick cylindrical band, internal edge grooves and a raised star on each face. A gentle angled turn keeps the round face visible while revealing thickness and metallic reflections. Coins intentionally do not enter the shadow pass, removing their ground shadows and reducing GPU work.
- The renderer caches shader uniform locations, uses 2x MSAA, and does not update menu animation during gameplay to avoid startup and per-frame driver overhead.
- Sensitivity adjusts lateral speed; vertical launch speed and gravity stay fixed.
- Power-ups and fragile platforms are removed. Static and moving blocks remain solid.
- Coins, missions, achievements and wallet persistence use the existing progression system.
- Former power-up challenges now track landings, moving blocks, precision and distance.
- Continue restores the ball to a safe block and gives
  five seconds of protection without clearing run totals.
- The ball compresses briefly after impact; reduced-motion mode suppresses this and camera bob.
- Back opens Settings and pauses the simulation. Backgrounding clears steering.

There are 30 reusable platform slots. Meshes and buffers are built once, and
world simulation and rendering use a shared lock. The renderer requests frames
with gameplay, rather than continuously rendering beneath the main menu.

## Verification

`tests/ClimbWorld3DCheck.java` runs against the compiled Kotlin world without
Android. It checks 3D arcs, lateral steering, missed landings, Continue,
procedural route reachability across 24 seeds, fixed pool size, permanent platforms,
analytic gravity, landing metrics and coin pickup. The APK additionally needs device checks for GL
shader compilation, camera framing and Android surface/lifecycle integration.
