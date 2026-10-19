package com.example.skyboundjumper

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Real perspective geometry, a depth buffer and a directional shadow map. */
class ClimbSurface3D(context: Context, world: ClimbWorld3D) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser { egl, display ->
            var selected: EGLConfig? = null
            // Two samples keeps curved silhouettes clean without the fill-rate cost
            // of 4x MSAA on entry-level mobile GPUs.
            for (samples in intArrayOf(2, 0)) {
                val spec = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                    EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE)
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, spec, configs, 1, count) && count[0] > 0) {
                    selected = configs[0]; break
                }
            }
            requireNotNull(selected) { "No OpenGL ES 2 window configuration" }
        }
        preserveEGLContextOnPause = true
        setRenderer(ClimbRenderer3D(world))
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}

private class ClimbRenderer3D(private val world: ClimbWorld3D) : GLSurfaceView.Renderer {
    private class Mesh(data: FloatArray) {
        val count = data.size / 6
        val buffer: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
    }
    private val box = Mesh(beveledBox())
    private val sphere = Mesh(sphereMesh())
    private val coin = Mesh(cylinderMesh())
    private val spike = Mesh(spikeMesh())
    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val vp = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProjection = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val eye = FloatArray(3)
    private var width = 1; private var height = 1
    private var program = 0; private var shadowProgram = 0
    private var framebuffer = 0; private var shadowTexture = 0
    private var shadowsAvailable = false
    private var shadowPass = false
    private var pos = 0; private var normal = 0
    private var uModel = 0; private var uVP = 0; private var uLight = 0
    private var uColor = 0; private var uScale = 0; private var uEye = 0
    private var uMaterial = 0; private var uAccent = 0
    private var uAccent2 = 0; private var uBall = 0; private var uRoll = 0
    private var uPattern = 0; private var uHasShadow = 0; private var uShadowSampler = 0
    private var uShape = 0; private var shadowShape = 0
    private var shadowModel = 0; private var shadowVP = 0; private var shadowPos = 0

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        shadowProgram = link(SHADOW_VERTEX, SHADOW_FRAGMENT)
        pos = glGetAttribLocation(program, "aPosition"); normal = glGetAttribLocation(program, "aNormal")
        uModel = glGetUniformLocation(program, "uModel"); uVP = glGetUniformLocation(program, "uVP")
        uLight = glGetUniformLocation(program, "uLightVP"); uColor = glGetUniformLocation(program, "uColor")
        uScale = glGetUniformLocation(program, "uInverseScale"); uEye = glGetUniformLocation(program, "uEye")
        uMaterial = glGetUniformLocation(program, "uMaterial"); uAccent = glGetUniformLocation(program, "uAccent")
        uAccent2 = glGetUniformLocation(program, "uAccent2"); uBall = glGetUniformLocation(program, "uBall")
        uRoll = glGetUniformLocation(program, "uRoll"); uPattern = glGetUniformLocation(program, "uPattern")
        uHasShadow = glGetUniformLocation(program, "uHasShadow")
        uShadowSampler = glGetUniformLocation(program, "uShadow")
        shadowModel = glGetUniformLocation(shadowProgram, "uModel")
        shadowVP = glGetUniformLocation(shadowProgram, "uVP")
        shadowPos = glGetAttribLocation(shadowProgram, "aPosition")
        uShape = glGetUniformLocation(program, "uShape")
        shadowShape = glGetUniformLocation(shadowProgram, "uShape")
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        createShadowMap()
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.perspectiveM(projection, 0, 55f, w.toFloat() / h, .2f, 180f)
    }

    override fun onDrawFrame(unused: GL10?) = synchronized(world) {
        val cx = world.cameraX; val cy = world.cameraY; val cz = world.cameraZ
        // Low chase view: the ball and upcoming blocks rise above the camera,
        // creating vertical scale without handheld-style shake.
        eye[0] = cx; eye[1] = cy + 3.8f + world.cameraBob; eye[2] = -cz + 14.2f
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], world.cameraAimX, cy + 2.0f, -cz - 10.8f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        // The shadow camera and material shader share exactly the same sun direction.
        Matrix.setLookAtM(lightView, 0, -21.6f, cy + 38.25f, -cz + 3.75f, 0f, cy, -cz - 12f, 0f, 1f, 0f)
        Matrix.orthoM(lightProjection, 0, -22f, 22f, -26f, 26f, 1f, 100f)
        Matrix.multiplyMM(lightVP, 0, lightProjection, 0, lightView, 0)
        if (shadowsAvailable) {
            shadowPass = true
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
            glClearColor(1f, 1f, 1f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glUseProgram(shadowProgram)
            glUniformMatrix4fv(shadowVP, 1, false, lightVP, 0)
            glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(2f, 3f)
            scene()
            glDisable(GL_POLYGON_OFFSET_FILL)
        }
        shadowPass = false
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        val backdrop = THEME_BACKDROP[world.cosmeticThemeTier.coerceIn(0, THEME_BACKDROP.lastIndex)]
        glClearColor(((backdrop shr 16) and 255) / 255f, ((backdrop shr 8) and 255) / 255f,
            (backdrop and 255) / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glUseProgram(program)
        glUniformMatrix4fv(uVP, 1, false, vp, 0)
        glUniformMatrix4fv(uLight, 1, false, lightVP, 0)
        glUniform3fv(uEye, 1, eye, 0)
        glUniform4f(uBall, world.x, world.y, -world.z, ClimbWorld3D.RADIUS)
        glUniform1f(uRoll, world.z / ClimbWorld3D.RADIUS * .314159f)
        applySkin(world.skinIndex)
        glUniform1f(uHasShadow, if (shadowsAvailable) 1f else 0f)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, shadowTexture)
        glUniform1i(uShadowSampler, 0)
        scene()
    }

    private fun scene() {
        val theme = world.cosmeticThemeTier.coerceIn(0, THEME_PLATFORM_LIGHT.lastIndex)
        // Distant limestone stacks occupy real space, producing parallax as the camera climbs.
        val section = floor(world.cameraZ / 18f).toInt()
        for (i in -2..6) {
            val index = section + i
            val z = index * 18f
            val level = z * .18f - 8f
            for (side in -1..1 step 2) {
                val offset = sin(index * 2.17f + side) * 3f
                // Layered quarry walls continue below the route instead of isolated pillars.
                for (course in 0..2) {
                    val cut = sin(index * 1.31f + course * 2f) * .32f
                    draw(box, side * (14f + offset + cut), level - 3f - course * 5.96f, z,
                        3.6f + cut, 2.94f, 8.9f,
                        if (course == 0) THEME_SCENERY[theme] else THEME_PLATFORM_DARK[theme])
                }
            }
        }
        for (b in world.blocks) {
            if (b.z < world.cameraZ - 17f || b.z > world.cameraZ + 88f) continue
            val color = if (b.kind == 1) THEME_MOVING[theme] else THEME_PLATFORM_LIGHT[theme]
            for (tile in 0 until b.tileCount) {
                val tileX = ClimbWorld3D.tileCenterX(b, tile)
                draw(box, tileX, b.y - b.thickness * .5f, b.z,
                    b.width * .5f, b.thickness * .5f, b.depth * .5f, color)
                // Every member of a row uses the exact same dimensions, material,
                // bevel and footing so multi-block choices read as one clean set.
                draw(box, tileX, b.y - b.thickness - .09f, b.z,
                    b.width * .46f, .11f, b.depth * .46f,
                    if (b.kind == 1) THEME_SCENERY[theme] else THEME_PLATFORM_DARK[theme])
            }
            // One solid mesh: visible top and collision plane both lie exactly at b.y.
            // Tiny shadow-map silhouettes shimmer as the chase camera advances.
            // Render the stable, fully lit geometry only in the colour pass.
            if (b.spike && !shadowPass) {
                val spikeScale = ClimbWorld3D.spikeScaleFor(b.id)
                val radius = ClimbWorld3D.SPIKE_RADIUS * spikeScale
                val height = ClimbWorld3D.SPIKE_HEIGHT * spikeScale
                val halfWidth = ClimbWorld3D.spikeHalfWidthFor(b.width, b.id)
                val halfDepth = ClimbWorld3D.spikeHalfDepthFor(b.depth, b.id)
                val centreX = ClimbWorld3D.tileCenterX(b, b.featureTile)
                val columns = ClimbWorld3D.spikeColumnCountFor(b.width, b.id)
                val rows = ClimbWorld3D.spikeRowCountFor(b.depth, b.id)
                val columnStep = halfWidth * 2f / (columns - 1)
                val rowStep = halfDepth * 2f / (rows - 1)
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val px = centreX - halfWidth + column * columnStep
                        val pz = b.z - halfDepth + row * rowStep
                        draw(spike, px, b.y + .012f, pz,
                            radius, height, radius, IRON)
                    }
                }
            }
            if (!shadowPass && b.coin) {
                // Keep the round face readable instead of spinning into a thin edge-on sliver.
                val coinAngle = 12f + sin(world.time * .85f + b.id * .7f) * 24f
                val itemX = ClimbWorld3D.tileCenterX(b, b.featureTile)
                draw(coin, itemX, b.y + .85f, b.z, .34f, .34f, .105f, GOLD, coinAngle, 1f)
            }
            if (!shadowPass && b.timeBonus) {
                // A dimensional clock token: inset ivory dial, warm metal rim and
                // raised hands. It is deliberately distinct from the coin economy.
                val floatY = b.y + .92f + sin(world.time * 2.1f + b.id) * .045f
                val itemX = ClimbWorld3D.tileCenterX(b, b.featureTile)
                draw(coin, itemX, floatY, b.z, .41f, .41f, .10f, GOLD, 4f, 1f)
                draw(coin, itemX, floatY, b.z - .10f, .29f, .29f, .035f, IVORY, 4f, 1f)
                draw(box, itemX, floatY + .075f, b.z - .145f, .027f, .105f, .018f, INK)
                draw(box, itemX + .075f, floatY, b.z - .147f, .085f, .026f, .018f, INK)
            }
        }
        if (!world.dead || shadowPass) {
            if (!world.protection || (world.time * 8f).toInt() % 5 != 0) {
                // Ease into the landing deformation instead of changing the ball's
                // silhouette on a single frame, which can read as an impact hitch.
                val squash = if (world.reduceMotion) 0f else {
                    val age = world.impactAge
                    .040f * (1f - exp(-age * 70f)) * exp(-age * 15f)
                }
                val r = ClimbWorld3D.RADIUS
                draw(sphere, world.x, world.y - r * squash, world.z, r * (1f + squash * .5f), r * (1f - squash),
                    r * (1f + squash * .5f), world.skinColor, -world.z / r * 18f, 3f)
            }
        }
        // The rival is visual-only. It shares the course and depth buffer but is
        // intentionally absent from every collision query, so both players can
        // occupy or pass through the same space without blocking one another.
        if (world.remoteVisible) {
            val r = ClimbWorld3D.RADIUS
            draw(sphere, world.remoteX, world.remoteY, world.remoteZ, r, r, r,
                SKIN_BASE[world.remoteSkin.coerceIn(0, 23)],
                -world.remoteZ / r * 18f, 3f, world.remoteSkin)
        }
    }

    private fun draw(mesh: Mesh, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        color: Int, rotation: Float = 0f, material: Float = 0f, skinOverride: Int = -1) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, -z)
        // Rotate the sphere material in its shader; impact compression stays world-vertical.
        if (rotation != 0f && material < 2.5f) Matrix.rotateM(model, 0, rotation, 0f, 1f, 0f)
        if (mesh === coin) Matrix.rotateM(model, 0, -14f, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        val location = if (shadowPass) shadowPos else pos
        val bevel = if (mesh === box) min(.13f, min(sx, min(sy, sz)) * .36f) else 0f
        glUniform4f(if (shadowPass) shadowShape else uShape, sx, sy, sz, bevel)
        glUniformMatrix4fv(if (shadowPass) shadowModel else uModel, 1, false, model, 0)
        mesh.buffer.position(0)
        glEnableVertexAttribArray(location)
        glVertexAttribPointer(location, 3, GL_FLOAT, false, 24, mesh.buffer)
        if (!shadowPass) {
            if (mesh === box) glUniform3f(uScale, 1f / sx, 1f / sy, 1f / sz)
            else glUniform3f(uScale, 1f / (sx * sx), 1f / (sy * sy), 1f / (sz * sz))
            val skin = if (skinOverride >= 0) skinOverride.coerceIn(0, 23)
                else world.skinIndex.coerceIn(0, 23)
            if (material > 2.5f) {
                applySkin(skin)
                glUniform1f(uRoll, -rotation * Math.PI.toFloat() / 180f)
            }
            uniformColor(uColor, if (material > 2.5f) SKIN_BASE[skin] else color)
            glUniform1f(uMaterial, material)
            mesh.buffer.position(3)
            glEnableVertexAttribArray(normal)
            glVertexAttribPointer(normal, 3, GL_FLOAT, false, 24, mesh.buffer)
        }
        glDrawArrays(GL_TRIANGLES, 0, mesh.count)
        glDisableVertexAttribArray(location)
        if (!shadowPass) glDisableVertexAttribArray(normal)
    }

    private fun applySkin(index: Int) {
        val skin = index.coerceIn(0, 23)
        uniformColor(uAccent, SKIN_ACCENT[skin])
        uniformColor(uAccent2, SKIN_ACCENT2[skin])
        glUniform2f(uPattern, SKIN_PATTERN[skin].toFloat(), SKIN_COUNT[skin].toFloat())
    }

    private fun uniformColor(location: Int, color: Int) {
        glUniform3f(location, ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f)
    }

    private fun createShadowMap() {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); shadowTexture = ids[0]
        glBindTexture(GL_TEXTURE_2D, shadowTexture)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, SHADOW_SIZE, SHADOW_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, shadowTexture, 0)
        glGenRenderbuffers(1, ids, 0)
        glBindRenderbuffer(GL_RENDERBUFFER, ids[0])
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, SHADOW_SIZE, SHADOW_SIZE)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, ids[0])
        shadowsAvailable = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = glCreateShader(type); glShaderSource(id, source); glCompileShader(id)
            val ok = IntArray(1); glGetShaderiv(id, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(id) }
            return id
        }
        val vs = shader(GL_VERTEX_SHADER, vertex); val fs = shader(GL_FRAGMENT_SHADER, fragment)
        val p = glCreateProgram(); glAttachShader(p, vs); glAttachShader(p, fs); glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { glGetProgramInfoLog(p) }
        glDeleteShader(vs); glDeleteShader(fs)
        return p
    }

    companion object {
        private const val SHADOW_SIZE = 1024
        private val STONE = 0xffb9ae92.toInt(); private val STONE_LIGHT = 0xffe2d9bd.toInt()
        private val STONE_DARK = 0xff7d8170.toInt(); private val MOSS_DARK = 0xff69755c.toInt()
        private val SAGE = 0xff73988a.toInt(); private val SAGE_LIGHT = 0xffb6c8ac.toInt()
        private val SAGE_DARK = 0xff475f4c.toInt()
        private val IRON = 0xff544b40.toInt()
        private val GOLD = 0xffeeb241.toInt(); private val GOLD_LIGHT = 0xffffdf86.toInt()
        private val IVORY = 0xfffff1c9.toInt(); private val INK = 0xff272116.toInt()
        // Cosmetic environments unlock with account level. They never change
        // collision, pace, dimensions, visibility range or route generation.
        private val THEME_BACKDROP = intArrayOf(0xffb0b3aa.toInt(), 0xffc6a07c.toInt(),
            0xff6f7860.toInt(), 0xff554b42.toInt())
        private val THEME_PLATFORM_LIGHT = intArrayOf(0xffe2d9bd.toInt(), 0xffe3bd8d.toInt(),
            0xffcad0a6.toInt(), 0xffc6b9a8.toInt())
        private val THEME_PLATFORM_DARK = intArrayOf(0xff7d8170.toInt(), 0xff8c5f40.toInt(),
            0xff59634b.toInt(), 0xff554b43.toInt())
        private val THEME_MOVING = intArrayOf(0xffb6c8ac.toInt(), 0xffd19a68.toInt(),
            0xffa9b783.toInt(), 0xffa89179.toInt())
        private val THEME_SCENERY = intArrayOf(0xff69755c.toInt(), 0xff76513c.toInt(),
            0xff46513d.toInt(), 0xff3d3732.toInt())
        // Same material colours and procedural patterns as the existing skin shop.
        private val SKIN_BASE = intArrayOf(0x403d3a,0xb5623f,0xcdb48a,0x5d6b3a,0x8d8a85,0x493020,0x1f7a5a,0x6f3614,
            0xece6dc,0xe6b3b0,0x8a5a0c,0x694324,0x6f0b1d,0x1b1311,0x131211,0x8f4d44,0x0a3a2d,0x151412,0xb26d05,0x240b05,0x0a0908,0x5f6266,0xded3b8,0x6d0d04)
        private val SKIN_ACCENT = intArrayOf(0x57534f,0x86401f,0xa5875a,0x7f9050,0x66635f,0xb58154,0x9be8c0,0xf2b988,
            0xffb4a0,0xfff3f0,0xf0b73c,0xd9a765,0xd1213f,0xff7a1a,0x2b2928,0xf8d3c3,0x5fd39a,0xb5aa99,0xfff1b8,0xff7a1a,0xf2b84b,0xffffff,0xd9a02d,0xff7a1a)
        private val SKIN_ACCENT2 = intArrayOf(0x57534f,0x86401f,0xa5875a,0x7f9050,0x66635f,0xd6aa77,0x0c4030,0xf2b988,
            0xa6efd0,0xa4505f,0xffe9a0,0xedd1a4,0xff8a9c,0xffd36b,0xd3243f,0xf8d3c3,0x072a20,0x554b40,0xfff1b8,0xffe08a,0xf2b84b,0xffffff,0xffe3a0,0xffd36b)
        private val SKIN_PATTERN = intArrayOf(0,0,1,2,1,4,3,4,5,3,6,4,6,7,8,4,9,3,4,10,11,4,11,12)
        private val SKIN_COUNT = intArrayOf(6,5,9,6,16,7,3,3,2,4,8,5,8,3,16,3,10,4,4,1,1,5,1,3)
        private const val VERTEX = """
            uniform mat4 uModel, uVP, uLightVP;
            uniform vec3 uInverseScale;
            uniform vec4 uShape;
            attribute vec3 aPosition, aNormal;
            varying vec3 vWorld, vNormal, vLocal;
            varying vec4 vShadow;
            void main() {
                vec3 p = aPosition;
                if (uShape.w > 0.0) p = sign(p) * (vec3(1.0) - (vec3(1.0) - abs(p)) * 10.0 * uShape.w / uShape.xyz);
                vec4 world = uModel * vec4(p, 1.0);
                vWorld = world.xyz; vLocal = aPosition;
                vec3 objectNormal = aNormal;
                if (uShape.w > 0.0) {
                    // Interpolated radial normals turn the chamfer strips and
                    // corner triangles into a visually continuous rounded edge.
                    vec3 radial = sign(aPosition) * max(abs(aPosition) - vec3(.899), vec3(0.0));
                    if (dot(radial, radial) > .00001) objectNormal = normalize(radial);
                }
                vNormal = normalize(mat3(uModel) * (objectNormal * uInverseScale));
                vShadow = uLightVP * world;
                gl_Position = uVP * world;
            }
        """
        private const val FRAGMENT = """
            precision highp float;
            uniform vec3 uColor, uEye, uAccent, uAccent2;
            uniform vec4 uBall;
            uniform vec2 uPattern;
            uniform float uMaterial, uHasShadow;
            uniform float uRoll;
            uniform sampler2D uShadow;
            varying vec3 vWorld, vNormal, vLocal;
            varying vec4 vShadow;
            float depth(vec2 uv) {
                return dot(texture2D(uShadow, uv), vec4(1.0, 1.0/255.0, 1.0/65025.0, 1.0/16581375.0));
            }
            void main() {
                vec3 n = normalize(vNormal);
                vec3 light = normalize(vec3(-.48, .85, .35));
                float diffuse = max(dot(n, light), 0.0);
                vec3 sc = vShadow.xyz / vShadow.w * .5 + .5;
                float shadow = 1.0;
                if (uHasShadow > .5 && sc.x > .001 && sc.x < .999 && sc.y > .001 && sc.y < .999 && sc.z < .999) {
                    float d = sc.z - max(.00035, .0012 * (1.0 - diffuse));
                    float sum = 0.0;
                    for (int ix = -1; ix <= 1; ix++) for (int iy = -1; iy <= 1; iy++)
                        sum += step(d, depth(sc.xy + vec2(float(ix),float(iy)) * .00065));
                    shadow = .28 + .72 * sum / 9.0;
                }
                vec3 base = uColor;
                if (uMaterial > 2.5) {
                    vec3 p = normalize(vLocal);
                    p.yz = mat2(cos(uRoll),-sin(uRoll),sin(uRoll),cos(uRoll))*p.yz;
                    float u = atan(p.x, p.z) / 6.28318 + .5;
                    float v = acos(clamp(p.y, -1.0, 1.0)) / 3.14159;
                    float k = uPattern.x, count = uPattern.y, a = 0.0, b = 0.0;
                    if (k < .5) a = 1.0 - smoothstep(.28,.36,abs(fract(u*count)-.5)*2.0);
                    else if (k < 1.5) a = 1.0-smoothstep(.16,.24,length(vec2(fract(u*count*2.0)-.5,fract(v*count)-.5)));
                    else if (k < 2.5) a = 1.0-smoothstep(.24,.3,fract(u*count+v*count*.7));
                    else if (k < 3.5) {
                        a=1.0-smoothstep(0.0,.12,abs(sin(u*6.283*count+2.5*sin(v*9.0+u*12.566))));
                        b=.7*(1.0-smoothstep(0.0,.08,abs(sin(v*6.283*count*1.5+3.0*sin(u*18.85)))));
                    } else if (k < 4.5) a=.5+.5*sin(u*6.283*count+v*3.0);
                    else if (k < 5.5) { a=.7*(.5+.5*sin(u*6.283*count+v*5.0)); b=.7*(.5+.5*sin(u*12.566*count+v*4.0+2.0)); }
                    else if (k < 6.5) { a=fract(sin(floor(u*count*2.0)*12.9+floor(v*count)*78.2)*43758.5); b=step(.8,a); }
                    else if (k < 7.5) { a=1.0-smoothstep(.02,.09,abs(sin(u*6.283*count+2.2*sin(v*7.0+u*12.566)))); b=step(.8,a); }
                    else if (k < 8.5) { a=mod(floor(u*count*2.0)+floor(v*count),2.0); b=smoothstep(.46,.49,abs(fract(u*2.0)-.5)); }
                    else if (k < 9.5) {
                        float d=length(vec2(fract(u*count*2.0+mod(floor(v*count),2.0)*.5)-.5,fract(v*count)-1.0));
                        a=smoothstep(.3,.7,d); b=1.0-smoothstep(.02,.06,abs(d-.5));
                    } else if (k < 10.5) {
                        float f=sin(u*18.85+sin(v*5.0)*2.0)+sin(v*8.0+u*12.566)+sin(u*31.4-v*4.0);
                        a=smoothstep(.4,1.4,f); b=smoothstep(1.4,2.4,f);
                    } else if (k < 11.5) {
                        float q=fract(u*2.0), d=min(q,1.0-q);
                        a=max(1.0-smoothstep(.004,.02,abs(v-.5)),max(1.0-smoothstep(.005,.025,d),.6*(1.0-smoothstep(.003,.012,abs(v-.32)))));
                    } else { a=.5+.5*sin(u*6.283*count+sin(v*9.0)*1.5); b=smoothstep(.6,1.0,.5+.5*sin(u*12.566*count-v*7.0)); }
                    base = mix(mix(base,uAccent,a),uAccent2,b);
                }
                // Fine procedural stone grain: fixed to world position, never screen noise.
                if (uMaterial < .5) {
                    float grain = sin(vWorld.x*43.0+sin(vWorld.z*29.0))*sin(vWorld.y*37.0+vWorld.z*31.0);
                    float strata = sin(vWorld.y*21.0+sin(vWorld.x*.8)*.4);
                    base *= .97 + .018*grain + .012*strata;
                    // Strong vertical material separation gives the top, wall and
                    // underside distinct values while preserving one solid mesh.
                    float vertical = smoothstep(.02, .96, vLocal.y*.5+.5);
                    float topFace = smoothstep(.72, .96, n.y);
                    base *= mix(.70, 1.03, vertical) * (1.0 + .07*topFace);
                }
                vec3 viewDir = normalize(uEye-vWorld);
                vec3 halfVector = normalize(light + viewDir);
                float shine = pow(max(dot(n, halfVector), 0.0), uMaterial > 2.5 ? 42.0 : 24.0);
                float hemisphere = .24 + .15 * (n.y*.5+.5);
                vec3 col = base * (hemisphere + .62 * diffuse * shadow);
                col += vec3(1.0, .95, .84) * shine * (uMaterial > 2.5 ? .22 : uMaterial > .5 ? .28 : .035) * shadow;
                if (uMaterial < .5) {
                    float bevelBand = clamp((1.0-max(max(abs(n.x),abs(n.y)),abs(n.z)))*3.1,0.0,1.0);
                    col += vec3(.20,.18,.13) * bevelBand * (.16 + .22*diffuse) * shadow;
                }
                if (uMaterial > .5 && uMaterial < 1.5) {
                    // Warm metallic reflections follow the actual rotating face normals.
                    vec3 reflected = reflect(-viewDir, n);
                    float sky = smoothstep(-.35, .85, reflected.y);
                    float fresnel = pow(1.0-max(dot(n,viewDir),0.0), 4.0);
                    vec3 environment = mix(vec3(.16,.09,.025), vec3(.95,.78,.39), sky);
                    col = col*.78 + environment*(.19+.16*fresnel);
                    col += vec3(1.0,.86,.51)*pow(max(dot(n,halfVector),0.0),72.0)*.45*shadow;
                }
                // Sphere-to-surface ambient occlusion strengthens and tightens near contact.
                float separation = uBall.y - vWorld.y - uBall.w;
                if (uMaterial < .5 && n.y > .8 && separation >= -.04 && separation < 3.0) {
                    float radius = uBall.w*.72 + max(separation,0.0)*.19;
                    vec2 delta = vWorld.xz-uBall.xz;
                    float contact = exp(-dot(delta,delta)/(radius*radius)) * .36 * exp(-max(separation,0.0)*1.7);
                    col *= 1.0-contact;
                }
                if (uMaterial > 2.5) {
                    float fresnel = pow(1.0-max(dot(n,viewDir),0.0),5.0);
                    col += vec3(.78,.80,.71)*fresnel*.11;
                }
                float distance = length(uEye - vWorld);
                float fog = 1.0 - exp(-max(0.0, distance - 20.0) * .017);
                col = mix(col, vec3(.69, .70, .65), clamp(fog, 0.0, .94));
                gl_FragColor = vec4(col, 1.0);
            }
        """
        private const val SHADOW_VERTEX = """
            uniform mat4 uModel, uVP;
            uniform vec4 uShape;
            attribute vec3 aPosition;
            void main() {
                vec3 p = aPosition;
                if (uShape.w > 0.0) p = sign(p) * (vec3(1.0) - (vec3(1.0)-abs(p))*10.0*uShape.w/uShape.xyz);
                gl_Position = uVP * uModel * vec4(p, 1.0);
            }
        """
        private const val SHADOW_FRAGMENT = """
            precision highp float;
            void main() {
                vec4 enc = fract(gl_FragCoord.z * vec4(1.0, 255.0, 65025.0, 16581375.0));
                enc -= enc.yzww * vec4(1.0/255.0, 1.0/255.0, 1.0/255.0, 0.0);
                gl_FragColor = enc;
            }
        """

        private fun triangle(out: MutableList<Float>, a: FloatArray, b: FloatArray, c: FloatArray) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.0001f)
            nx /= length; ny /= length; nz /= length
            for (p in arrayOf(a, b, c)) { out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(nx); out.add(ny); out.add(nz) }
        }

        private fun beveledBox(): FloatArray {
            val out = ArrayList<Float>()
            fun face(a: FloatArray, b: FloatArray, c: FloatArray) {
                val u = FloatArray(3) { b[it]-a[it] }; val v = FloatArray(3) { c[it]-a[it] }
                val n = floatArrayOf(u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
                val dot = n[0]*(a[0]+b[0]+c[0]) + n[1]*(a[1]+b[1]+c[1]) + n[2]*(a[2]+b[2]+c[2])
                if (dot < 0f) triangle(out,a,c,b) else triangle(out,a,b,c)
            }
            // Six faces, twelve bevel strips, eight triangular corners. The shader maps
            // .9 to a constant world-space inset, so large blocks don't get huge bevels.
            for (axis in 0..2) for (sign in intArrayOf(-1,1)) {
                val u = (axis+1)%3; val v = (axis+2)%3
                val points = arrayOf(floatArrayOf(-.9f,-.9f),floatArrayOf(.9f,-.9f),floatArrayOf(.9f,.9f),floatArrayOf(-.9f,.9f)).map {
                    FloatArray(3).apply { this[axis]=sign.toFloat(); this[u]=it[0]; this[v]=it[1] }
                }
                face(points[0],points[1],points[2]); face(points[0],points[2],points[3])
            }
            for (a in 0..2) for (b in a+1..2) for (sa in intArrayOf(-1,1)) for (sb in intArrayOf(-1,1)) {
                val c = 3-a-b
                fun p(f: Boolean, end: Float) = FloatArray(3).apply {
                    this[a]=sa*(if(f) 1f else .9f); this[b]=sb*(if(f) .9f else 1f); this[c]=end
                }
                val p0=p(true,-.9f); val p1=p(false,-.9f); val p2=p(false,.9f); val p3=p(true,.9f)
                face(p0,p1,p2); face(p0,p2,p3)
            }
            for (x in intArrayOf(-1,1)) for (y in intArrayOf(-1,1)) for (z in intArrayOf(-1,1)) {
                face(floatArrayOf(x.toFloat(),y*.9f,z*.9f),floatArrayOf(x*.9f,y.toFloat(),z*.9f),floatArrayOf(x*.9f,y*.9f,z.toFloat()))
            }
            return out.toFloatArray()
        }

        private fun sphereMesh(): FloatArray {
            val out = ArrayList<Float>()
            fun vertex(lat: Int, lon: Int): FloatArray {
                val a = Math.PI * lat / 32; val b = Math.PI * 2 * lon / 48
                return floatArrayOf((sin(a) * cos(b)).toFloat(), cos(a).toFloat(), (sin(a) * sin(b)).toFloat())
            }
            for (i in 0 until 32) for (j in 0 until 48) {
                val a = vertex(i, j); val b = vertex(i + 1, j); val c = vertex(i + 1, j + 1); val d = vertex(i, j + 1)
                for (p in arrayOf(a, b, c, a, c, d)) { out.addAll(p.toList()); out.addAll(p.toList()) }
            }
            return out.toFloatArray()
        }

        private fun cylinderMesh(): FloatArray {
            val out = ArrayList<Float>()
            // Closed solid: filled circular faces, shallow raised rim and a thick cylindrical band.
            val radii = floatArrayOf(0f, .52f, .72f, .82f, .91f, .97f, 1f, 1f, 1f, .97f, .91f, .82f, .72f, .52f, 0f)
            val depths = floatArrayOf(.90f, .90f, .89f, .96f, .98f, .88f, .66f, 0f, -.66f, -.88f, -.98f, -.96f, -.89f, -.90f, -.90f)
            fun point(r: Float, d: Float, angle: Double, tooth: Int): FloatArray {
                // Grooves sit inside the rim: the silhouette remains a true circle.
                val reed = if (r == 1f && d == 0f && tooth % 2 == 0) .012f else 0f
                return floatArrayOf((cos(angle) * (r - reed)).toFloat(), (sin(angle) * (r - reed)).toFloat(), d)
            }
            for (i in 0 until 160) {
                val a = i * Math.PI * 2 / 160; val b = (i + 1) * Math.PI * 2 / 160
                for (j in 0 until radii.lastIndex) {
                    val p = point(radii[j], depths[j], a, i)
                    val q = point(radii[j + 1], depths[j + 1], a, i)
                    val r = point(radii[j + 1], depths[j + 1], b, i + 1)
                    val s = point(radii[j], depths[j], b, i + 1)
                    if (radii[j + 1] > 0f) triangle(out, p, q, r)
                    if (radii[j] > 0f) triangle(out, p, r, s)
                }
            }
            // Embossed five-point seal on both faces, rotating with the whole coin.
            for (side in intArrayOf(-1, 1)) for (i in 0 until 10) {
                fun star(k: Int, depth: Float): FloatArray {
                    val angle = Math.PI * .5 + k * Math.PI / 5
                    val radius = if (k % 2 == 0) .52f else .25f
                    return floatArrayOf((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), depth * side)
                }
                val a = star(i, 1.02f); val b = star(i + 1, 1.02f)
                val c = star(i, .88f); val d = star(i + 1, .88f)
                if (side > 0) {
                    triangle(out, floatArrayOf(0f, 0f, 1.04f), a, b)
                    triangle(out, a, c, d); triangle(out, a, d, b)
                } else {
                    triangle(out, floatArrayOf(0f, 0f, -1.04f), b, a)
                    triangle(out, a, d, c); triangle(out, a, b, d)
                }
            }
            return out.toFloatArray()
        }

        private fun spikeMesh(): FloatArray {
            val out = ArrayList<Float>()
            val base = arrayOf(floatArrayOf(-1f, 0f, -1f), floatArrayOf(1f, 0f, -1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(-1f, 0f, 1f))
            for (i in 0..3) triangle(out, base[i], floatArrayOf(0f, 1f, 0f), base[(i + 1) % 4])
            return out.toFloatArray()
        }
    }
}
