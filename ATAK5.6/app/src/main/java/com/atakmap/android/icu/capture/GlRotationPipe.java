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
 */
public class GlRotationPipe implements SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "ICU.GlRotationPipe";

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

    private final Surface  outputSurface;   // encoder input
    private final int      srcW, srcH;      // camera capture buffer size
    private volatile int   rotationDeg;     // live-updatable (same-aspect flips only; see setRotation)
    private final boolean  mirror;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program, texId;
    private int aPosition, aTexCoord, uRot, uStMatrix;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;            // camera draws here
    private FloatBuffer quad;
    private int vpW, vpH;                     // encoder surface (viewport) size
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
        this.outputSurface = encoderInput;
        this.srcW          = srcWidth;
        this.srcH          = srcHeight;
        this.rotationDeg   = ((rotationDegrees % 360) + 360) % 360;
        this.mirror        = mirror;
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

    /**
     * Change the rotation applied to subsequent frames without rebuilding the pipe. The next
     * {@link #drawFrame} picks up the new angle. Safe ONLY for a same-aspect change (a 180°
     * flip, e.g. 90↔270 or 0↔180): the encoder surface / GL viewport were sized to the
     * original aspect at start, so a portrait↔landscape swap needs a full capture restart, not
     * this. Callers gate on {@code EncoderConfig.isLandscapeRotation}.
     */
    public void setRotation(int degrees) {
        this.rotationDeg = ((degrees % 360) + 360) % 360;
    }

    private void drawFrame() {
        if (released || surfaceTexture == null) return;
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(stMatrix);
            if (!loggedSt) {
                loggedSt = true;
                Log.d(TAG, "stMatrix=" + java.util.Arrays.toString(stMatrix));
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            // Rotate in PIXEL space via an orthographic projection so a 90°/270° rotation
            // never distorts (rotating the quad directly in square NDC and mapping to a
            // non-square viewport stretches it). The unit quad is scaled to the source's
            // upright aspect, rotated, then projected into the viewport's pixel box; because
            // the encoder surface is sized to the rotated aspect, it fills exactly.
            // Negate the angle to match the preview's clockwise Matrix.postRotate.
            Matrix.orthoM(proj, 0, -vpW / 2f, vpW / 2f, -vpH / 2f, vpH / 2f, -1f, 1f);
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, -rotationDeg, 0f, 0f, 1f);
            if (mirror) Matrix.scaleM(model, 0, -1f, 1f, 1f);
            // Upright source is the camera buffer transposed (sensor is mounted 90°): the
            // long buffer edge becomes the tall edge. Half-extents for the unit quad.
            Matrix.scaleM(model, 0, srcH / 2f, srcW / 2f, 1f);
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
            EGLExt_setPresentationTime(surfaceTexture.getTimestamp());
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        } catch (Exception e) {
            Log.w(TAG, "drawFrame: " + e.getMessage());
        }
    }

    private void EGLExt_setPresentationTime(long nsecs) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs);
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

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);

        int[] surfAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfAttribs, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        // Viewport = the encoder input surface's actual size (already swapped for 90/270).
        int[] w = new int[1], h = new int[1];
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0);
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0);
        vpW = w[0]; vpH = h[0];
        GLES20.glViewport(0, 0, vpW, vpH);
        Log.d(TAG, "GL viewport " + vpW + "x" + vpH + " (src " + srcW + "x" + srcH
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
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
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
