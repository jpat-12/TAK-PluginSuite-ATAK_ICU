package com.atakmap.android.icu.capture;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.atakmap.coremap.log.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL rotation stage that sits between the camera and the H.264 encoder so that the
 * <b>encoded pixels</b> are actually rotated — not just the local preview.
 *
 * <p>The camera renders into a {@link SurfaceTexture} (an external OES texture); on each
 * frame we draw that texture, rotated by the configured angle, into the encoder's input
 * {@link Surface} through a dedicated EGL context. Raw H.264 over RTSP carries no rotation
 * metadata, so pre-rotating here is the only way viewers see the right orientation.</p>
 *
 * <p>The preview path is untouched — the camera still targets the preview Surface directly
 * and the pane's TextureView matrix rotates that copy.</p>
 *
 * <p>The pipe can drive <b>additional output surfaces</b> from the same camera frame
 * (see {@link #addOutput}) — used by the high-quality record path, which runs a second
 * encoder at a different resolution. Each output has its own viewport (the frame is
 * scaled to fit it) and an optional fps cap: when recording wants a higher frame rate
 * than the stream, the camera runs at the higher rate and the stream's output drops
 * frames down to its configured rate here.</p>
 */
public class GlRotationPipe implements SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "ICU.GlRotationPipe";

    /** One render target: an encoder input surface with its own viewport and fps cap. */
    private static final class Out {
        final Surface surface;
        final long minGapNs;      // minimum ns between presented frames; 0 = every frame
        EGLSurface egl = EGL14.EGL_NO_SURFACE;
        int w, h;
        long lastNs;
        Out(Surface surface, int fpsLimit) {
            this.surface = surface;
            // 0.95 factor tolerates camera timestamp jitter — without it a 30fps source
            // capped at 30 would drop frames whenever an interval lands a hair short.
            this.minGapNs = fpsLimit > 0 ? (long) (0.95 * 1_000_000_000L / fpsLimit) : 0;
        }
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uRot;\n" +
            "uniform mat4 uStMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = uRot * aPosition;\n" +
            "  vTex = (uStMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 vTex;\n" +
            "void main() { gl_FragColor = texture2D(sTexture, vTex); }\n";

    // Full-screen quad (triangle strip): position xy, texcoord uv.
    private static final float[] QUAD = {
        //   x,    y,   u, v
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f,
    };

    private final int      srcW, srcH;      // camera capture buffer size
    private final int      rotationDeg;
    private final boolean  mirror;
    private final boolean  srcTransposed;   // sensor-mounted-90° source (see constructor)

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig  eglConfig;

    // outputs[0] is the primary (stream encoder) surface, created with the pipe; extras
    // come and go via addOutput/removeOutput. CopyOnWrite: drawFrame iterates on the GL
    // thread while add/remove mutate from the pipeline thread.
    private final java.util.List<Out> outputs = new java.util.concurrent.CopyOnWriteArrayList<>();

    private int program, texId;
    private int aPosition, aTexCoord, uRot, uStMatrix;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;            // camera draws here
    private FloatBuffer quad;
    private final float[] stMatrix = new float[16];
    private final float[] mvp   = new float[16];
    private final float[] proj  = new float[16];
    private final float[] model = new float[16];

    private HandlerThread thread;
    private Handler       handler;
    private volatile boolean released;
    private boolean loggedSt;   // one-shot ST-matrix log for diagnosing zoom/crop

    public GlRotationPipe(Surface encoderInput, int srcWidth, int srcHeight,
                          int rotationDegrees, boolean mirror) {
        this(encoderInput, srcWidth, srcHeight, rotationDegrees, mirror, 0, true);
    }

    public GlRotationPipe(Surface encoderInput, int srcWidth, int srcHeight,
                          int rotationDegrees, boolean mirror, int fpsLimit) {
        this(encoderInput, srcWidth, srcHeight, rotationDegrees, mirror, fpsLimit, true);
    }

    /** @param fpsLimit cap on the PRIMARY output's frame rate (0 = present every camera
     *                  frame) — used when the camera runs faster than the stream wants.
     *  @param srcTransposed true for camera sensors (buffer is mounted 90°, the upright
     *                  frame is the transpose — the pre-existing behavior); false for a
     *                  source that already delivers upright frames (a decoded network
     *                  camera stream), where the buffer is used as-is. */
    public GlRotationPipe(Surface encoderInput, int srcWidth, int srcHeight,
                          int rotationDegrees, boolean mirror, int fpsLimit,
                          boolean srcTransposed) {
        outputs.add(new Out(encoderInput, fpsLimit));
        this.srcW          = srcWidth;
        this.srcH          = srcHeight;
        this.rotationDeg   = ((rotationDegrees % 360) + 360) % 360;
        this.mirror        = mirror;
        this.srcTransposed = srcTransposed;
    }

    /**
     * Set up EGL + GL on a background thread and return the {@link Surface} the camera
     * should render into. Blocks until setup completes. Returns null on failure.
     */
    public Surface start() {
        thread  = new HandlerThread("ICU-GlRotate");
        thread.start();
        handler = new Handler(thread.getLooper());
        final Surface[] result = new Surface[1];
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        handler.post(() -> {
            try {
                initEgl();
                initGl();
                surfaceTexture = new SurfaceTexture(texId);
                surfaceTexture.setDefaultBufferSize(srcW, srcH);
                surfaceTexture.setOnFrameAvailableListener(GlRotationPipe.this, handler);
                inputSurface = new Surface(surfaceTexture);
                result[0] = inputSurface;
            } catch (Exception e) {
                Log.e(TAG, "GL setup failed: " + e.getMessage(), e);
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) { try { lock.wait(2000); } catch (InterruptedException ignored) { break; } }
        }
        return result[0];
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (released) return;
        handler.post(this::drawFrame);
    }

    private void drawFrame() {
        if (released || surfaceTexture == null) return;
        try {
            // updateTexImage needs a current context; the primary surface is always there.
            Out primary = outputs.get(0);
            EGL14.eglMakeCurrent(eglDisplay, primary.egl, primary.egl, eglContext);
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(stMatrix);
            if (!loggedSt) {
                loggedSt = true;
                Log.d(TAG, "stMatrix=" + java.util.Arrays.toString(stMatrix));
            }
            long ts = surfaceTexture.getTimestamp();

            for (Out o : outputs) {
                // Per-output frame-rate cap (primary only in practice): skip the frame if
                // it lands sooner than the output's interval since the last one presented.
                if (o.minGapNs > 0 && o.lastNs > 0 && ts - o.lastNs < o.minGapNs) continue;
                o.lastNs = ts;
                drawTo(o, ts);
            }
        } catch (Exception e) {
            Log.w(TAG, "drawFrame: " + e.getMessage());
        }
    }

    private void drawTo(Out o, long ts) {
        EGL14.eglMakeCurrent(eglDisplay, o.egl, o.egl, eglContext);
        GLES20.glViewport(0, 0, o.w, o.h);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        // Rotate in PIXEL space via an orthographic projection so a 90°/270° rotation
        // never distorts (rotating the quad directly in square NDC and mapping to a
        // non-square viewport stretches it). The unit quad is scaled to the source's
        // upright aspect, rotated, then projected into the viewport's pixel box. A
        // secondary output can be a different size than the source (the HQ-record
        // downscale/upscale case), so the quad is uniformly scaled to cover its box —
        // same aspect by construction, so no distortion, at most a rounding-pixel crop.
        Matrix.orthoM(proj, 0, -o.w / 2f, o.w / 2f, -o.h / 2f, o.h / 2f, -1f, 1f);
        Matrix.setIdentityM(model, 0);
        Matrix.rotateM(model, 0, -rotationDeg, 0f, 0f, 1f);
        if (mirror) Matrix.scaleM(model, 0, -1f, 1f, 1f);
        // Upright source: for a camera sensor (srcTransposed) the buffer is mounted 90°,
        // so upright = the transpose (long buffer edge becomes the tall edge); a network
        // camera's decoded frames are already upright and the buffer is used as-is.
        // Half-extents for the unit quad.
        float upW = srcTransposed ? srcH : srcW;
        float upH = srcTransposed ? srcW : srcH;
        float rotW = (rotationDeg == 90 || rotationDeg == 270) ? upH : upW;
        float rotH = (rotationDeg == 90 || rotationDeg == 270) ? upW : upH;
        float k = Math.max(o.w / rotW, o.h / rotH);
        Matrix.scaleM(model, 0, k * upW / 2f, k * upH / 2f, 1f);
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0);

        quad.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPosition);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        GLES20.glUniformMatrix4fv(uRot, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Present with the camera frame's timestamp so encoder PTS stays monotonic.
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, o.egl, ts);
        EGL14.eglSwapBuffers(eglDisplay, o.egl);
    }

    /**
     * Attach another output surface (a second encoder's input) mid-stream. Blocks until
     * the GL thread has created the EGL surface. Returns false on failure — the pipe
     * keeps running on its existing outputs either way.
     */
    public boolean addOutput(Surface surface) {
        if (released || handler == null) return false;
        final Out o = new Out(surface, 0);
        final boolean[] ok = new boolean[1];
        final boolean[] done = new boolean[1];
        final Object lock = new Object();
        handler.post(() -> {
            try {
                o.egl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, o.surface,
                        new int[]{ EGL14.EGL_NONE }, 0);
                int[] w = new int[1], h = new int[1];
                EGL14.eglQuerySurface(eglDisplay, o.egl, EGL14.EGL_WIDTH, w, 0);
                EGL14.eglQuerySurface(eglDisplay, o.egl, EGL14.EGL_HEIGHT, h, 0);
                o.w = w[0]; o.h = h[0];
                outputs.add(o);
                ok[0] = true;
                Log.d(TAG, "added output " + o.w + "x" + o.h
                        + " (" + outputs.size() + " total)");
            } catch (Exception e) {
                Log.w(TAG, "addOutput failed: " + e.getMessage());
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) { try { lock.wait(2000); } catch (InterruptedException ignored) { break; } }
        }
        return ok[0];
    }

    /** Detach an output added by {@link #addOutput}. Blocks so the caller can safely stop
     *  the encoder that owns {@code surface} afterwards. No-op for the primary output. */
    public void removeOutput(Surface surface) {
        if (handler == null) return;
        final boolean[] done = new boolean[1];
        final Object lock = new Object();
        handler.post(() -> {
            try {
                for (Out o : outputs) {
                    if (o.surface == surface && o != outputs.get(0)) {
                        outputs.remove(o);
                        Out primary = outputs.get(0);
                        EGL14.eglMakeCurrent(eglDisplay, primary.egl, primary.egl, eglContext);
                        EGL14.eglDestroySurface(eglDisplay, o.egl);
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "removeOutput: " + e.getMessage());
            } finally {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) { try { lock.wait(1000); } catch (InterruptedException ignored) { break; } }
        }
    }

    public void release() {
        released = true;
        if (handler != null) {
            final Object lock = new Object();
            final boolean[] done = new boolean[1];
            handler.post(() -> {
                try {
                    if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
                    if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
                    if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
                    releaseEgl();
                } catch (Exception ignored) {
                } finally {
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
            });
            synchronized (lock) {
                while (!done[0]) { try { lock.wait(1000); } catch (InterruptedException ignored) { break; } }
            }
        }
        if (thread != null) { thread.quitSafely(); thread = null; handler = null; }
    }

    // ── EGL / GL setup ────────────────────────────────────────────────────────

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0);
        eglConfig = configs[0];

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);

        // Primary output = the stream encoder's input surface. Its viewport is set per
        // frame in drawTo (each output has its own size).
        Out primary = outputs.get(0);
        int[] surfAttribs = { EGL14.EGL_NONE };
        primary.egl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, primary.surface, surfAttribs, 0);
        EGL14.eglMakeCurrent(eglDisplay, primary.egl, primary.egl, eglContext);

        int[] w = new int[1], h = new int[1];
        EGL14.eglQuerySurface(eglDisplay, primary.egl, EGL14.EGL_WIDTH, w, 0);
        EGL14.eglQuerySurface(eglDisplay, primary.egl, EGL14.EGL_HEIGHT, h, 0);
        primary.w = w[0]; primary.h = h[0];
        Log.d(TAG, "GL primary output " + primary.w + "x" + primary.h + " (src " + srcW + "x" + srcH
                + ", rot=" + rotationDeg + ")");
    }

    private void initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uRot      = GLES20.glGetUniformLocation(program, "uRot");
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        quad = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            for (Out o : outputs) {
                if (o.egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, o.egl);
                o.egl = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE)
            throw new RuntimeException("program link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] status = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE)
            throw new RuntimeException("shader compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
