package com.calypso.darkroom;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.util.List;

/**
 * Safelit darkroom, seen over a rabbit's shoulder. It works the developer tray;
 * finished photomicrographs hang to dry. Flat shapes with line-art outlines -
 * no gradients, bitmaps, permissions or network. Touch agitates the tray.
 */
public class DarkroomWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new DarkroomEngine();
    }

    // ---- palette ----------------------------------------------------------
    static final int BG        = 0xFF140A0C;
    static final int WALL      = 0xFF1C0E10;
    static final int BG_GLOW   = 0xFF241012;
    static final int FLOOR     = 0xFF120809;
    static final int TABLE     = 0xFF2A1416;
    static final int TABLE_TOP = 0xFF3C1E1F;
    static final int TABLE_LIP = 0xFF4A2523;
    static final int GEAR      = 0xFF35191A;
    static final int GEAR_EDGE = 0xFF5A2B27;
    static final int TRAY      = 0xFF4E2523;
    static final int TRAY_RIM  = 0xFF7A3A31;
    static final int DEV       = 0xFF67302A;
    static final int RABBIT    = 0xFFD6B4A2;   // cream, but under red light
    static final int OUTLINE   = 0xFF3B2622;
    static final int PINK      = 0xFFDE8F8B;
    static final int INK       = 0xFF140A0C;
    static final int PRINT     = 0xFFCFAC96;   // paper, same
    static final int LAMP      = 0xFFC33A2E;
    static final int LAMP_HOT  = 0xFFE85A3C;
    static final int LINE      = 0xFF4A2422;

    class DarkroomEngine extends Engine implements SensorEventListener {
        private final Handler handler = new Handler();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path path2 = new Path();
        private final Matrix mtx = new Matrix();
        private boolean visible;
        private int w, h;
        private long t0 = SystemClock.uptimeMillis();

        private SensorManager sensors;
        private Sensor motionSensor;
        private Sensor gravitySensor;
        private PowerManager.WakeLock brightLock;
        /** Smoothed linear-acceleration magnitude; rises when the device moves. */
        private float motion = 0f;
        private long lastBrighten = 0L;
        /** Movement needed to count as a pickup, in m/s^2 once smoothed. */
        private static final float PICKUP_THRESHOLD = 0.85f;
        /** Screen is held bright this long after a pickup. */
        private static final long  BRIGHT_MS = 9000L;
        /** 0..1, briefly raised on pickup so the rabbit looks up. */
        private float alert = 0f;

        private AudioManager audio;
        /** 0..1, ramps up while music is playing - drives the bob. */
        private float groove = 0f;
        private boolean musicOn = false;
        private long lastMusicPoll = 0L;
        /** Consecutive positive polls; stops short blips starting a dance. */
        private int musicStreak = 0;

        private float lastFrameSec = 0f;
        /** Body+head silhouette never changes, so it is unioned once, not per frame. */
        private final Path torso = new Path();
        /** Full silhouette incl. ears, rebuilt only when an ear angle actually moves. */
        private final Path silhouette = new Path();
        private float cachedEarF = Float.NaN, cachedEarN = Float.NaN;
        private boolean torsoBuilt = false;
        // frame-rate instrumentation
        private static final int SPECIMENS = 7;

        // ---- prints on the drying line: a conveyor, entering above the rabbit
        private static final int   MAX_PRINTS    = 6;
        private static final float PRINT_SPACING = 78f;
        private static final float PRINT_ENTRY_X = 252f;   // where the line begins
        private static final float PRINT_EXIT_X  = 545f;   // fully off the right edge
        private final float[] prX      = new float[MAX_PRINTS];
        private final float[] prTarget = new float[MAX_PRINTS];
        private final int[]   prKind   = new int[MAX_PRINTS];
        private final float[] prFade   = new float[MAX_PRINTS];
        private int prCount = 0;

        // Phases are ACCUMULATED (phase += dt*freq), never computed as t*freq.
        // With a varying freq the latter makes phase jump by t*df each frame,
        // which at large t is a violent vibration.
        private float stirPhase = 0f, ripplePhase = 0f, sloshPhase = 0f, ballPhase = 0f;

        private float agitate = 0f;
        private float develop = 0f;
        private int printsHung = 0;
        /** 0..1 as the newest print rises onto the line and fades in. */
        private float hangAnim = 1f;

        // ~50fps. lockCanvas/unlockCanvasAndPost costs ~8.5ms on this device and the
        // scene draws in ~8.6ms, so 60fps is not reachable; target what is, and
        // leave a few ms idle rather than thrashing.
        private static final int FRAME_MS = 16;
        // the lockscreen clock owns the top-left; keep the scene clear of it
        private static final float LINE_Y   = 152f;
        private static final float TBL_FAR  = 424f;   // table surface, far edge
        private static final float TBL_NEAR = 478f;   // table surface, near edge
        private static final float TBL_LIP  = 502f;   // bottom of the front apron
        private static final float FLOOR_Y  = 596f;
        private static final float RAB_X    = 142f;
        // the safelight hangs from above the panel, so its pivot and hanger
        // are off screen and only the cord shows
        private static final float LAMP_PIVX = 452f, LAMP_PIVY = -46f;

        // ---- hanging things -------------------------------------------
        // The lamp, the disco ball and the prints swing from their anchors and
        // follow whichever way the device leans.
        // things on strings: print pins are handled with the prints themselves
        private float lampAng, lampVel, discoAng, discoVel, lamp2Ang, lamp2Vel;
        private final float[] prAng = new float[8], prVel = new float[8];

        private float gx = 0f, gy = 1f;     // unit gravity, screen axes
        // Nothing swings unless the device is being held up and the phone is
        // unlocked. Otherwise it is in a pocket or face down on a bench, and
        // waving the whole room about is just noise.
        private android.app.KeyguardManager keyguard;
        private boolean roomAwake = false;
        private long lastLockPoll = 0;

        /** Sideways acceleration, smoothed: a moved device shoves the bobs. */
        private float stirX = 0f;

        private boolean shouldSwing() {
            long now = SystemClock.uptimeMillis();
            if (now - lastLockPoll > 700L) {          // binder call, so poll it
                lastLockPoll = now;
                if (keyguard == null) {
                    keyguard = (android.app.KeyguardManager)
                            getSystemService(Context.KEYGUARD_SERVICE);
                }
                roomAwake = keyguard == null || !keyguard.isKeyguardLocked();
            }
            boolean upright = gy > 0.72f && Math.abs(gx) < 0.6f;
            return roomAwake && upright;
        }


        /** One physics step. Bodies fall and bounce; hung things swing. */
        private void stepPhysics(float dt) {
            if (dt > 0.05f) dt = 0.05f;             // never integrate a long frame

            // pendulums keep swinging even at rest, so they are always stepped
            // Stiffer restoring force with less damping: they answer a tilt
            // sooner and then carry the swing, which reads as weight rather
            // than as something light being pushed around.
            float down = (float) Math.atan2(gx, gy);    // where "hanging" points
            // gravity pulls them plumb, movement shoves them: swinging the
            // device sideways throws every bob the other way, like real ones
            lampVel += -30.0f * (float) Math.sin(lampAng - down) * dt;
            lampVel += -stirX * 0.85f * dt;
            lampVel *= (1f - 1.60f * dt);
            lampAng += lampVel * dt;
            discoVel += -28.0f * (float) Math.sin(discoAng - down) * dt;
            discoVel += -stirX * 0.62f * dt;        // heaviest, shoved least
            discoVel *= (1f - 1.45f * dt);
            discoAng += discoVel * dt;
            lamp2Ang = 0f;      // the spot is fixed; only the ball swings
            for (int i = 0; i < prAng.length; i++) {
                // each sheet hangs a little differently: its own stiffness and
                // damping, so they never swing in lockstep
                float stiff = 34.0f + (i % 5) * 3.6f;
                float damp = 1.80f + (i % 3) * 0.30f;
                prVel[i] += -stiff * (float) Math.sin(prAng[i] - down) * dt;
                prVel[i] += -stirX * (1.15f + (i % 4) * 0.16f) * dt;   // paper, shoved most
                prVel[i] *= (1f - damp * dt);
                prAng[i] += prVel[i] * dt;
            }

        }

        private final Runnable tick = new Runnable() {
            public void run() { draw(); }
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensors != null) {
                // linear acceleration already has gravity removed, so its
                // magnitude is movement directly
                motionSensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
                if (motionSensor == null) {
                    motionSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                }
                // tilt: which way is down, so the room can pour that way
                gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (gravitySensor == null) {
                    gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                }
            }
            for (int i = 0; i < 2; i++) {          // start with a small backlog
                hangPrint((i + 1) % 3);
                prX[prCount - 1] = prTarget[prCount - 1];
                prFade[prCount - 1] = 1f;
            }
            audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                brightLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                                            "darkroom:pickup");
                brightLock.setReferenceCounted(false);
            }
        }

        @Override public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];

            if (e.sensor == gravitySensor && e.sensor.getType() == Sensor.TYPE_GRAVITY) {
                // The vector points up in the world; down on screen is +y.
                // Lying flat there is no in-plane component at all - gravity is
                // straight through the glass - so fade back to plain screen-down
                // rather than letting sensor noise decide which way things fall.
                float px = x / SensorManager.GRAVITY_EARTH;
                float py = y / SensorManager.GRAVITY_EARTH;
                float inPlane = (float) Math.sqrt(px * px + py * py);
                if (inPlane < 0.35f) {
                    float k = inPlane / 0.35f;          // 0 flat, 1 upright
                    px = px * k;
                    py = py * k + (1f - k);             // ...toward straight down
                }
                gx = px;
                gy = py;
                return;
            }

            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mag = Math.abs(mag - SensorManager.GRAVITY_EARTH);   // crude fallback
            }
            motion = motion * 0.80f + mag * 0.20f;
            stirX = stirX * 0.55f + x * 0.45f;

            long now = SystemClock.uptimeMillis();


            if (motion > PICKUP_THRESHOLD && now - lastBrighten > 5000L) {
                lastBrighten = now;
                alert = 1f;
                if (brightLock != null) {
                    // timed acquire so it can never be leaked
                    brightLock.acquire(BRIGHT_MS);
                }
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) { }

        @Override public void onDestroy() {
            super.onDestroy();
            handler.removeCallbacks(tick);
            if (sensors != null) sensors.unregisterListener(this);
            if (brightLock != null && brightLock.isHeld()) brightLock.release();
        }

        @Override public void onVisibilityChanged(boolean vis) {
            visible = vis;
            if (sensors != null && motionSensor != null) {
                if (vis) {
                    sensors.registerListener(this, motionSensor,
                                             SensorManager.SENSOR_DELAY_NORMAL);
                    if (gravitySensor != null) {
                        sensors.registerListener(this, gravitySensor,
                                                 SensorManager.SENSOR_DELAY_GAME);
                    }
                } else {
                    sensors.unregisterListener(this);
                    motion = 0f;
                }
            }
            if (vis) draw(); else handler.removeCallbacks(tick);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int fmt, int width, int height) {
            super.onSurfaceChanged(holder, fmt, width, height);
            w = width; h = height;
            draw();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
            handler.removeCallbacks(tick);
        }

        @Override public void onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                // map screen -> scene coords (inverse of the render transform)
                float s = Math.min(w / 480f, h / 640f);
                float sx = (e.getX() - (w - 480f * s) / 2f) / s;
                float sy = (e.getY() - (h - 640f * s) / 2f) / s;
                if (sx > 28 && sx < 108 && sy > 394 && sy < 472) {
                    // tap on the desk timer opens the timer app
                    Intent i = new Intent(DarkroomWallpaper.this, TimerActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); } catch (Exception ignored) {}
                    return;
                }
                agitate = Math.min(1f, agitate + 0.55f);
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                agitate = Math.min(1f, agitate + 0.04f);   // a drag keeps it going, gently
            }
        }

        /**
         * True only for actual music. isMusicActive() fires for anything on the
         * music stream - timer beeps, notification blips, app sonification - so
         * inspect the active playbacks and require MEDIA usage with MUSIC content.
         * A short sound also has to persist across two polls before it counts.
         */
        private boolean realMusicPlaying() {
            if (audio == null) return false;
            boolean found = false;
            try {
                List<AudioPlaybackConfiguration> cfgs = audio.getActivePlaybackConfigurations();
                for (int i = 0; i < cfgs.size(); i++) {
                    AudioAttributes at = cfgs.get(i).getAudioAttributes();
                    if (at == null) continue;
                    if (at.getUsage() == AudioAttributes.USAGE_MEDIA
                     && at.getContentType() == AudioAttributes.CONTENT_TYPE_MUSIC) {
                        found = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                found = audio.isMusicActive();      // fall back if the API is unavailable
            }
            musicStreak = found ? Math.min(4, musicStreak + 1) : 0;
            return musicStreak >= 2;                // ~800ms of sustained music
        }

        private void draw() {
            long t0ns = System.nanoTime();
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) render(c);
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }


            handler.removeCallbacks(tick);
            if (visible) {
                long spentMs = (System.nanoTime() - t0ns) / 1000000L;
                handler.postDelayed(tick, Math.max(1L, FRAME_MS - spentMs));
            }
        }

        private void render(Canvas c) {
            float t = (SystemClock.uptimeMillis() - t0) / 1000f;
            // Everything below advances per SECOND, not per frame, so the frame
            // rate can change without altering how fast anything happens.
            float dt = (lastFrameSec == 0f) ? 0.033f : Math.min(0.2f, t - lastFrameSec);
            lastFrameSec = t;

            agitate *= (float) Math.exp(-dt / 2.33f);
            alert   *= (float) Math.exp(-dt / 1.85f);

            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - lastMusicPoll > 400L) {
                lastMusicPoll = nowMs;
                musicOn = realMusicPlaying();
            }
            groove += ((musicOn ? 1f : 0f) - groove) * (1f - (float) Math.exp(-dt * 0.91f));

            stepPhysics(dt);
            stirPhase   += dt * (1.4f + 2.6f * agitate);
            ripplePhase += dt * (0.5f + 1.1f * agitate);
            sloshPhase  += dt * (3.0f + 3.0f * agitate);
            ballPhase   += dt * 1.7f;

            // nobody is printing while the room is on the floor
            develop += 0.0242f * dt * (1f + 1.4f * agitate);
            if (develop >= 1f) {
                develop = 0f;
                printsHung++;
                hangPrint(printsHung % SPECIMENS);
            }

            // ease each print along the line and fade the newest one in
            for (int i = 0; i < prCount; i++) {
                prX[i] += (prTarget[i] - prX[i]) * (1f - (float) Math.exp(-dt * 3.2f));
                prFade[i] = Math.min(1f, prFade[i] + dt * 0.8f);
            }

            float s = Math.min(w / 480f, h / 640f);
            float ox = (w - 480f * s) / 2f, oy = (h - 640f * s) / 2f;

            // A cached full-screen bitmap was tried here and measured SLOWER:
            // the static shapes are cheap, and a 1.2 MB software blit per frame
            // is not. Wallpapers get a software canvas, so just redraw them.
            // No full-screen clear: wall + table + floor cover the frame between
            // them. Only the letterbox (non-480x640 panels) needs painting.
            if (ox > 0.5f || oy > 0.5f) c.drawColor(BG);
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);

            drawWall(c);
            drawShelf(c);
            drawDryingLine(c, t);
               drawTable(c);     
            drawEnlarger(c);  
            drawTrays(c, t);
               drawDeskTimer(c); 
              drawRabbit(c, t); 
            drawRoomLight(c, t, dt);
            drawTimerGlow(c);
            drawSafelight(c, t);
            drawDiscoLamp(c);
            drawDiscoBall(c);
            drawGrain(c, t);

            c.restore();
        }

        // ---- helpers ------------------------------------------------------

        private void fillPath(Canvas c, int fill, int stroke, float sw) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawPath(path, p);
            if (sw > 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setColor(stroke);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        private void box(Canvas c, float l, float tp, float r, float b, int fill, int stroke) {
            RectF bx = new RectF(l, tp, r, b);
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawRect(bx, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f);
            p.setColor(stroke);
            c.drawRect(bx, p);
            p.setStyle(Paint.Style.FILL);
        }

        /** Cream shape with a warm outline - the base unit of the rabbit. */
        private void capsule(Canvas c, float l, float tp, float r, float b, float rad) {
            RectF bx = new RectF(l, tp, r, b);
            p.setStyle(Paint.Style.FILL);
            p.setColor(RABBIT);
            c.drawRoundRect(bx, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f);
            p.setColor(OUTLINE);
            c.drawRoundRect(bx, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        /** Build a closed polygon into `pt` from flat x,y pairs. */
        private void polyInto(Path pt, float[] v) {
            pt.reset();
            pt.moveTo(v[0], v[1]);
            for (int i = 2; i < v.length; i += 2) pt.lineTo(v[i], v[i + 1]);
            pt.close();
        }

        /** Regular n-gon; `rot` in radians orients the first vertex. */
        private void ngonInto(Path pt, float cx, float cy, float r, int n, float rot) {
            pt.reset();
            for (int i = 0; i < n; i++) {
                double a = rot + i * 2 * Math.PI / n;
                float x = cx + r * (float) Math.cos(a);
                float y = cy + r * (float) Math.sin(a);
                if (i == 0) pt.moveTo(x, y); else pt.lineTo(x, y);
            }
            pt.close();
        }

        /** Fill+stroke an arbitrary path (fillPath only handles the shared one). */
        private void drawShape(Canvas c, Path pt, int fill, int stroke, float sw) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawPath(pt, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(stroke);
            c.drawPath(pt, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void trapezoid(float cx, float cy, float fw, float nw, float hh) {
            path.reset();
            path.moveTo(cx - fw, cy - hh);
            path.lineTo(cx + fw, cy - hh);
            path.lineTo(cx + nw, cy + hh);
            path.lineTo(cx - nw, cy + hh);
            path.close();
        }

        // ---- room ---------------------------------------------------------

        private void drawWall(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(WALL);
            c.drawRect(0, 0, 480, TBL_FAR, p);
            c.save();
            c.rotate((float) Math.toDegrees(lampAng), LAMP_PIVX, LAMP_PIVY);   // halo follows
            p.setColor(BG_GLOW);
            c.drawCircle(452, 120, 116, p);
            p.setColor(blend(BG_GLOW, LAMP, 0.22f));
            c.drawCircle(452, 120, 60, p);
            c.restore();
            p.setColor(BG);
            c.drawRect(0, TBL_FAR, 480, FLOOR_Y, p);   // under-table, table draws over
            p.setColor(FLOOR);
            c.drawRect(0, FLOOR_Y, 480, 640, p);
        }

        /** Chemistry shelf on the left wall - bottles as plain blocks. */
        /** The plank is bolted to the wall; only its contents come off. */
        private void drawShelf(Canvas c) {
            box(c, 6, 300, 112, 307, GEAR, GEAR_EDGE);       // plank
            box(c, 22, 262, 42, 300, GEAR, GEAR_EDGE);
            box(c, 50, 250, 74, 300, GEAR, GEAR_EDGE);
            box(c, 70, 272, 84, 300, GEAR, GEAR_EDGE);
            box(c, 88, 258, 106, 300, GEAR, GEAR_EDGE);
            box(c, 16, 236, 26, 262, GEAR, GEAR_EDGE);
        }

        /**
         * A finished print is pegged up above the rabbit; everything already on
         * the line shuffles one place along, and anything past the right edge is
         * dropped.
         */
        private void hangPrint(int kind) {
            for (int i = 0; i < prCount; i++) prTarget[i] += PRINT_SPACING;

            int keep = 0;                       // compact out anything off-screen
            for (int i = 0; i < prCount; i++) {
                if (prTarget[i] < PRINT_EXIT_X) {
                    prX[keep] = prX[i]; prTarget[keep] = prTarget[i];
                    prKind[keep] = prKind[i]; prFade[keep] = prFade[i];
                    keep++;
                }
            }
            prCount = keep;

            if (prCount < MAX_PRINTS) {
                prX[prCount]      = PRINT_ENTRY_X;
                prTarget[prCount] = PRINT_ENTRY_X;
                prKind[prCount]   = kind;
                prFade[prCount]   = 0f;         // fades and rises into place
                prCount++;
            }
        }

        private void drawDryingLine(Canvas c, float t) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(LINE);
            path.reset();
            path.moveTo(0, LINE_Y);
            path.quadTo(240, LINE_Y + 26, 480, LINE_Y - 2);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);

            for (int i = 0; i < prCount; i++) {
                float px = prX[i];
                float fade = prFade[i];
                if (fade <= 0.02f) continue;
                float py   = LINE_Y + 10 + 7 * (float) Math.sin(px * 0.021f);
                float rise = (1f - fade) * 26f;          // drifts up into place
                float sway = (float) Math.toDegrees(prAng[i % prAng.length])
                           + 2.4f * (float) Math.sin(t * 0.9 + i * 1.7);
                c.save();
                c.translate(px, py + rise);
                c.rotate(sway);
                p.setColor(withAlpha(PRINT, (int) (255 * fade)));
                c.drawRect(-26, 0, 26, 52, p);
                drawSpecimen(c, 0, 26, 17, prKind[i], 1f, 0.55f * fade);
                p.setColor(withAlpha(LINE, (int) (255 * fade)));
                c.drawRect(-4, -5, 4, 3, p);
                c.restore();
            }
        }

        /** A real table: receding top surface, front apron, two legs. */
        private void drawTable(Canvas c) {
            path.reset();
            path.moveTo(36, TBL_FAR);
            path.lineTo(444, TBL_FAR);
            path.lineTo(480, TBL_NEAR);
            path.lineTo(0, TBL_NEAR);
            path.close();
            fillPath(c, TABLE_TOP, TABLE_LIP, 2f);
            box(c, 0, TBL_NEAR, 480, TBL_LIP, TABLE, TABLE_LIP);
            box(c, 26, TBL_LIP, 52, FLOOR_Y, TABLE, TABLE_LIP);
            box(c, 424, TBL_LIP, 450, FLOOR_Y, TABLE, TABLE_LIP);
        }

        /**
         * A little Gralab-style interval timer on the left end of the bench.
         * Its hands track TimerActivity.sceneEndAt (same process): the second
         * hand sweeps the remaining seconds, the minute hand the remaining
         * minutes; idle they rest at twelve. Tapping it opens the timer.
         */
        private void drawDeskTimer(Canvas c) {
            float L = 34, R = 102, T = 400, B = 464, cx = 68, cy = 428, r = 21f;
            // feet
            box(c, L + 4, B, L + 12, B + 3, INK, INK);
            box(c, R - 12, B, R - 4, B + 3, INK, INK);
            // case
            box(c, L, T, R, B, GEAR, GEAR_EDGE);
            // dial: black face, red outline - reads like a safelight instrument
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(TRAY_RIM);
            c.drawCircle(cx, cy, r, p);
            // quarter marks
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(i * 90);
                c.drawLine((float) (cx + Math.cos(a) * (r - 4.5f)), (float) (cy + Math.sin(a) * (r - 4.5f)),
                           (float) (cx + Math.cos(a) * (r - 1.5f)), (float) (cy + Math.sin(a) * (r - 1.5f)), p);
            }
            // hands from the running timer, if any
            long end = TimerActivity.sceneEndAt;
            long remain = end > 0 ? Math.max(0, (end - System.currentTimeMillis()) / 1000) : 0;
            int min = (int) ((remain / 60) % 60), sec = (int) (remain % 60);
            double ma = Math.toRadians((min + sec / 60.0) * 6 - 90);
            double sa = Math.toRadians(sec * 6 - 90);
            // luminous hands: they pick up a glow as the room goes dark, the
            // way a real darkroom timer's paint does
            float glow = groove;
            p.setColor(withAlpha(0xFF8CE0B0, (int) (70 * glow)));
            p.setStrokeWidth(6.5f);
            c.drawLine(cx, cy, (float) (cx + Math.cos(ma) * r * 0.55f),
                       (float) (cy + Math.sin(ma) * r * 0.55f), p);
            c.drawLine(cx, cy, (float) (cx + Math.cos(sa) * r * 0.85f),
                       (float) (cy + Math.sin(sa) * r * 0.85f), p);
            p.setColor(blend(LAMP, 0xFFBFF5D2, glow * 0.8f));
            p.setStrokeWidth(2.4f);
            c.drawLine(cx, cy, (float) (cx + Math.cos(ma) * r * 0.55f),
                       (float) (cy + Math.sin(ma) * r * 0.55f), p);
            p.setColor(blend(LAMP_HOT, 0xFFD8FFE6, glow * 0.8f));
            p.setStrokeWidth(1.3f);
            c.drawLine(cx, cy, (float) (cx + Math.cos(sa) * r * 0.85f),
                       (float) (cy + Math.sin(sa) * r * 0.85f), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            c.drawCircle(cx, cy, 1.6f, p);
            // twin toggle switches
            p.setColor(PINK);
            c.drawCircle(L + 8, B - 5, 2.2f, p);
            c.drawCircle(R - 8, B - 5, 2.2f, p);
        }

        /** Enlarger standing on the bench - column, lamphouse, lens, baseboard. */
        private void drawEnlarger(Canvas c) {
            trapezoid(406, 452, 42, 50, 15);
            fillPath(c, GEAR, GEAR_EDGE, 1.8f);
            box(c, 418, 246, 432, 440, GEAR, GEAR_EDGE);     // column
            box(c, 356, 246, 436, 284, GEAR, GEAR_EDGE);     // lamphouse
            path.reset();
            path.moveTo(372, 284); path.lineTo(414, 284);
            path.lineTo(404, 308); path.lineTo(382, 308);
            path.close();
            fillPath(c, GEAR, GEAR_EDGE, 1.8f);              // lens cone
            box(c, 356, 288, 372, 295, GEAR, GEAR_EDGE);     // carrier slot
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(LAMP, 70));
            c.drawRect(384, 303, 402, 308, p);
        }

        /** Three baths in a row. The first is the developer, and holds the print. */
        private void drawTrays(Canvas c, float t) {
            drawBath(c, t, 224, 452, true); 
            drawBath(c, t, 286, 452, false);
            drawBath(c, t, 342, 452, false);
        }

        private void drawBath(Canvas c, float t, float cx, float cy, boolean active) {
            trapezoid(cx, cy, 26, 31, 14);
            fillPath(c, TRAY, TRAY_RIM, 2f);
            trapezoid(cx, cy + 1, 21, 26, 10);
            p.setStyle(Paint.Style.FILL);
            p.setColor(DEV);
            c.drawPath(path, p);
            if (!active) return;

            float slosh = 1.6f * agitate * (float) Math.sin(sloshPhase);
            c.save();
            c.translate(slosh, 0);
            trapezoid(cx, cy + 2, 15, 19, 7);
            p.setColor(blend(DEV, PRINT, Math.min(1f, develop * 1.6f)));
            c.drawPath(path, p);
            if (develop > 0.15f) {
                c.save();
                c.translate(cx, cy + 2);
                c.scale(1f, 0.52f);
                drawSpecimen(c, 0, 0, 13, printsHung % SPECIMENS, (develop - 0.15f) / 0.85f, 1f);
                c.restore();
            }
            c.restore();

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f);
            int rings = agitate > 0.05f ? 4 : 2;
            for (int i = 0; i < rings; i++) {
                float ph = (ripplePhase + i / (float) rings) % 1f;
                int a = (int) ((30 + 95 * agitate) * (1f - ph));
                p.setColor(withAlpha(LAMP_HOT, a));
                float rx = 7 + 22 * ph, ry = (7 + 22 * ph) * 0.36f;
                c.drawOval(new RectF(cx - rx, cy - ry + 2, cx + rx, cy + ry + 2), p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        private void drawSpecimen(Canvas c, float cx, float cy, float r, int kind,
                                  float reveal, float alphaMul) {
            if (reveal <= 0f) return;
            int a = (int) (215 * Math.min(1f, reveal) * alphaMul);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f);
            p.setColor(withAlpha(INK, a));

            if (kind == 0) {            // pennate diatom
                c.drawOval(new RectF(cx - r, cy - r * 0.46f, cx + r, cy + r * 0.46f), p);
                for (int i = 1; i < 7; i++) {
                    float x = cx - r + (2 * r) * i / 7f;
                    float hh = r * 0.40f * (float) Math.sin(Math.PI * i / 7.0);
                    c.drawLine(x, cy - hh, x, cy + hh, p);
                }
            } else if (kind == 1) {     // radiolarian
                c.drawCircle(cx, cy, r * 0.62f, p);
                c.drawCircle(cx, cy, r * 0.30f, p);
                for (int i = 0; i < 12; i++) {
                    double ang = i * Math.PI / 6.0;
                    c.drawLine(cx + (float) Math.cos(ang) * r * 0.62f,
                               cy + (float) Math.sin(ang) * r * 0.62f,
                               cx + (float) Math.cos(ang) * r * 0.95f,
                               cy + (float) Math.sin(ang) * r * 0.95f, p);
                }
            } else if (kind == 2) {     // pollen grain
                c.drawCircle(cx, cy, r * 0.66f, p);
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 9; i++) {
                    double ang = i * (2 * Math.PI / 9.0) + 0.3;
                    float rr = r * (i % 2 == 0 ? 0.40f : 0.62f);
                    c.drawCircle(cx + (float) Math.cos(ang) * rr,
                                 cy + (float) Math.sin(ang) * rr, 1.6f, p);
                }
                p.setStyle(Paint.Style.STROKE);
            } else if (kind == 3) {     // amoeba - the only irregular one in the set
                final float[] lobe = {0.94f, 0.68f, 0.88f, 0.62f, 0.96f, 0.70f, 0.84f, 0.64f};
                path.reset();
                for (int i = 0; i <= lobe.length; i++) {
                    int i0 = i % lobe.length, i1 = (i + 1) % lobe.length;
                    double a0 = i0 * Math.PI / 4.0, a1 = i1 * Math.PI / 4.0;
                    float x0 = cx + (float) Math.cos(a0) * r * lobe[i0];
                    float y0 = cy + (float) Math.sin(a0) * r * lobe[i0];
                    float x1 = cx + (float) Math.cos(a1) * r * lobe[i1];
                    float y1 = cy + (float) Math.sin(a1) * r * lobe[i1];
                    if (i == 0) path.moveTo((x0 + x1) / 2f, (y0 + y1) / 2f);
                    else path.quadTo(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f);
                }
                path.close();
                c.drawPath(path, p);
                c.drawCircle(cx - r * 0.18f, cy + r * 0.10f, r * 0.26f, p);   // nucleus
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + r * 0.34f, cy - r * 0.30f, 1.8f, p);        // vacuole
                c.drawCircle(cx + r * 0.10f, cy + r * 0.44f, 1.4f, p);
                p.setStyle(Paint.Style.STROKE);
            } else if (kind == 4) {     // volvox colony
                c.drawCircle(cx, cy, r * 0.88f, p);
                c.drawCircle(cx, cy, r * 0.24f, p);
                for (int i = 0; i < 10; i++) {
                    double ang = i * (2 * Math.PI / 10.0) + 0.2;
                    c.drawCircle(cx + (float) Math.cos(ang) * r * 0.54f,
                                 cy + (float) Math.sin(ang) * r * 0.54f, r * 0.15f, p);
                }
            } else if (kind == 5) {     // foraminifera - spiral of chambers
                double ang = 0.6;
                for (int i = 0; i < 6; i++) {
                    ang += 1.02;
                    float d  = r * 0.13f * (i + 1.4f);
                    float rr = r * 0.13f * (1f + i * 0.22f);
                    c.drawCircle(cx + (float) Math.cos(ang) * d,
                                 cy + (float) Math.sin(ang) * d, rr, p);
                }
            } else {                    // fern frond
                c.drawLine(cx, cy + r * 0.88f, cx, cy - r * 0.88f, p);
                for (int i = 0; i < 6; i++) {
                    float y   = cy + r * 0.72f - i * (r * 0.29f);
                    float len = r * 0.58f * (1f - i * 0.13f);
                    c.drawLine(cx, y, cx - len, y - len * 0.55f, p);
                    c.drawLine(cx, y, cx + len, y - len * 0.55f, p);
                }
            }
            p.setStyle(Paint.Style.FILL);
        }

        /**
         * The rabbit from behind - we are over its shoulder. No face to render,
         * so the ears and the cotton tail carry all the character.
         */
        private void drawRabbit(Canvas c, float t) {
            // Sway is a small ROTATION about the base, not a slide along x -
            // sliding reads as floating. Breathing is a vertical swell about the
            // same pivot, so the feet never lift.
            // Bob to the music. The sway pivot is between the feet, so rotating
            // swings the head furthest while the feet stay planted - which reads
            // as nodding along rather than sliding about.
            float beat    = (float) Math.sin(t * 8.5f);
            float lean    = 1.5f * (float) Math.sin(t * 0.55f) + groove * 4.2f * beat;
            float breathe = 0.010f * (float) Math.sin(t * 1.05f)
                          + groove * 0.022f * Math.abs(beat);   // squash on the beat
            float stir = (2f + 22f * agitate) * (float) Math.sin(stirPhase);
            float earTwitch = 2.5f * (float) Math.sin(t * 0.55f) + 7f * peak(t, 8.1f)
                            - 14f * alert                       // ears snap up on pickup
                            + groove * 7f * (float) Math.sin(t * 8.5f + 0.6f);  // ears lag the beat

            final float PIVX = -6f, PIVY = 594f;   // between the feet

            c.save();
            c.translate(RAB_X, 0);

            // far foot is on the far side, so it sits behind the body and tail.
            // Planted: drawn outside the swaying transform.
            polyInto(path2, new float[]{-44,570, -62,584, -66,596, -36,596});
            drawShape(c, path2, RABBIT, OUTLINE, 2.4f);

            // ---- everything above the ankles sways ----
            c.save();
            c.rotate(lean, PIVX, PIVY);
            c.scale(1f, 1f + breathe, PIVX, PIVY);


            // nose then muzzle, both behind the head
            p.setStyle(Paint.Style.FILL);
            p.setColor(PINK);
            c.drawCircle(84, 419, 7, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f);
            p.setColor(OUTLINE);
            c.drawCircle(84, 419, 7, p);
            p.setStyle(Paint.Style.FILL);
            ngonInto(path2, 68, 416, 18, 8, (float) (Math.PI / 8));
            drawShape(c, path2, RABBIT, OUTLINE, 2.4f);

            // body, head and both ears as one faceted silhouette
            if (!torsoBuilt) {
                polyInto(torso, new float[]{-24,430, -40,470, -56,548, -42,596,
                                             30,596,  48,546,  42,468,  26,430});
                ngonInto(path2, 18, 396, 57, 8, (float) (Math.PI / 8));
                torso.op(path2, Path.Op.UNION);
                torsoBuilt = true;
            }

            // Path.op is expensive and the ears move less than a degree between
            // frames, so quantise the angles and only rebuild when they change.
            float earF = Math.round(-19f - earTwitch * 0.5f);
            float earN = Math.round(21f + earTwitch);
            if (earF != cachedEarF || earN != cachedEarN) {
                silhouette.set(torso);
                polyInto(path2, new float[]{-10,22, -6,-56, 6,-56, 10,22});
                mtx.setRotate(earF);
                mtx.postTranslate(10, 350);
                path2.transform(mtx);
                silhouette.op(path2, Path.Op.UNION);

                polyInto(path2, new float[]{-12,28, -7,-62, 7,-62, 12,28});
                mtx.setRotate(earN);
                mtx.postTranslate(45, 342);
                path2.transform(mtx);
                silhouette.op(path2, Path.Op.UNION);

                cachedEarF = earF; cachedEarN = earN;
            }

            // Everything of him blocks the mirror light except the far ear,
            // which the spots play across. Same silhouette he is drawn from,
            // minus that ear.
            rabM.setTranslate(RAB_X, 0);
            rabM.preRotate(lean, PIVX, PIVY);
            rabM.preScale(1f, 1f + breathe, PIVX, PIVY);
            path2.set(torso);
            polyInto(maskTmp, new float[]{-12,28, -7,-62, 7,-62, 12,28});
            mtx.setRotate(earN);
            mtx.postTranslate(45, 342);
            maskTmp.transform(mtx);
            path2.op(maskTmp, Path.Op.UNION);           // near ear only
            rabMask.reset();
            path2.transform(rabM, rabMask);
            drawShape(c, silhouette, RABBIT, OUTLINE, 2.4f);

            // near arm; tongs reach so the developing sheet stays visible
            c.save();
            c.translate(36, 456);
            c.rotate(6 + stir * 0.3f);
            polyInto(path2, new float[]{-11,-11, 12,-9, 33,-4, 31,8, 10,9, -11,11});
            drawShape(c, path2, RABBIT, OUTLINE, 2.4f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(OUTLINE);
            c.drawLine(29, -1, 58, -8, p);
            c.drawLine(30, 7, 59, 0, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
            c.restore();

            // cotton tail
            ngonInto(path2, -50, 552, 20, 8, 0f);
            drawShape(c, path2, RABBIT, OUTLINE, 2.2f);

            c.restore();   // end of the swaying group

            // near foot is in front of the body, also planted
            polyInto(path2, new float[]{30,568, 60,576, 66,596, 26,596});
            drawShape(c, path2, RABBIT, OUTLINE, 2.4f);

            c.restore();
        }

        /**
         * Lowers from the ceiling while music plays and retracts when it stops.
         * The spin is faked the classic way - meridian ellipses whose width
         * tracks cos(phase), so they sweep across the face like a turning ball.
         */
        private void drawDiscoBall(Canvas c) {
            if (groove < 0.02f) return;
            final float bx = 300f, rr = 34f;
            float by = -62f + groove * 146f;          // hidden above -> hanging higher
            int a = (int) (255 * Math.min(1f, groove * 1.6f));

            c.save();
            c.rotate((float) Math.toDegrees(discoAng), bx, 0);   // hangs from the cord
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(withAlpha(LINE, a));
            c.drawLine(bx, 0, bx, by - rr, p);        // cord

            ngonInto(path2, bx, by, rr, 8, (float) (Math.PI / 8));
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(GEAR, a));
            c.drawPath(path2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(withAlpha(GEAR_EDGE, a));
            c.drawPath(path2, p);

            c.save();
            c.clipPath(path2);                        // keep facets inside the ball
            p.setStrokeWidth(1.4f);
            p.setColor(withAlpha(LAMP, (int) (a * 0.75f)));
            for (int i = -1; i <= 1; i++) {           // latitudes
                float y = by + i * rr * 0.46f;
                c.drawLine(bx - rr, y, bx + rr, y, p);
            }
            for (int i = 0; i < 4; i++) {             // meridians, sweeping = spin
                float ph = ballPhase + i * (float) Math.PI / 4f;
                float wx = rr * Math.abs((float) Math.cos(ph));
                c.drawOval(new RectF(bx - wx, by - rr, bx + wx, by + rr), p);
            }
            // a couple of facets catching the light as it turns
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                float ph = ballPhase * 1.3f + i * 2.1f;
                float fx = bx + (float) Math.cos(ph) * rr * 0.55f;
                float fy = by + (float) Math.sin(ph * 0.7f) * rr * 0.45f;
                int fa = (int) (a * (0.35f + 0.65f * Math.abs(Math.cos(ph))));
                p.setColor(withAlpha(LAMP_HOT, fa));
                c.drawCircle(fx, fy, 4.0f, p);
            }
            c.restore();

            // beams sweeping the room
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            for (int i = 0; i < 6; i++) {
                double ang = ballPhase * 0.6 + i * Math.PI / 3.0;
                p.setColor(withAlpha(LAMP_HOT, (int) (a * 0.16f)));
                c.drawLine(bx + (float) Math.cos(ang) * rr,
                           by + (float) Math.sin(ang) * rr,
                           bx + (float) Math.cos(ang) * rr * 4.4f,
                           by + (float) Math.sin(ang) * rr * 4.4f, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }

        /** 0..1 spike that fires briefly every `period` seconds. */
        private float peak(float t, float period) {
            float ph = (t % period) / period;
            return ph < 0.06f ? (float) Math.sin(ph / 0.06f * Math.PI) : 0f;
        }

        /**
         * The room is lit by one swinging lamp. Everything is dropped into
         * near-darkness, then the beam is added back on top: three nested
         * wedges with a long vertical falloff, so the edge fades instead of
         * ending, and dust drifting through the light.
         */
        private final Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader coneShade;
        // Darkness is the screen minus the beam. Filling three huge clipped
        // rects per frame cost 18 ms; a prebuilt path per band costs a fill of
        // the actual dark area, and only rebuilds when the lamp has moved
        // enough to matter.
        private final Path[] darkBand = {new Path(), new Path()};
        private final Path bandTmp = new Path();
        // no antialiasing on the big dark fills: the edge is already feathered
        // by the bands themselves, and AA on a full-screen path is expensive
        private final Paint dimPaint = new Paint();
        private float cachedBandAng = 999f;
        // the rabbit's head and ears in scene coords, so the disco projection
        // can be clipped by it instead of painting across his face
        private final Path rabMask = new Path();
        private final Path maskTmp = new Path();
        // the enlarger stands in the light too
        private final Path gearMask = new Path();
        private boolean gearMaskBuilt = false;
        private final Matrix rabM = new Matrix();

        /** Wedge from the bulb, widening as it falls. */
        private void conePath(float spread, float reach) {
            path.reset();
            path.moveTo(452 - 22, 99);
            path.lineTo(452 + 22, 99);
            path.lineTo(452 + spread, reach);
            path.lineTo(452 - spread, reach);
            path.close();
        }

        private void drawRoomLight(Canvas c, float t, float dt) {
            // Darkness is painted around the beam rather than light being
            // painted into it: everything the cone covers keeps its own
            // colour, so objects standing in it are genuinely lit while the
            // rest of the room is crushed toward black.
            float dark = 0.045f + 0.34f * groove;   // working light vs disco

            final float[] spread = {410, 230};
            final float[] level  = {1.0f, 0.45f};

            float qAng = Math.round(lampAng * 120f) / 120f;   // ~0.5 degree steps
            if (qAng != cachedBandAng) {
                cachedBandAng = qAng;
                mtx.setRotate((float) Math.toDegrees(qAng), LAMP_PIVX, LAMP_PIVY);
                for (int i = 0; i < spread.length; i++) {
                    conePath(spread[i], 900);
                    path.transform(mtx);
                    bandTmp.reset();
                    bandTmp.addRect(0, 0, 480, 640, Path.Direction.CW);
                    bandTmp.op(path, Path.Op.DIFFERENCE);     // screen minus beam
                    darkBand[i].set(bandTmp);
                }
            }

            dimPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < darkBand.length; i++) {
                dimPaint.setColor(withAlpha(0x050203, (int) (255 * dark * level[i])));
                c.drawPath(darkBand[i], dimPaint);
            }

            c.save();
            c.rotate((float) Math.toDegrees(lampAng), LAMP_PIVX, LAMP_PIVY);

            // a breath of warm haze in the beam itself, no more than before
            if (coneShade == null) {
                coneShade = new LinearGradient(452, 99, 452, 780,
                        new int[] {0x40FF7A4A, 0x22E85A3C, 0x0EC33A2E, 0x00000000},
                        new float[] {0f, 0.30f, 0.66f, 1f}, Shader.TileMode.CLAMP);
            }
            lp.setShader(coneShade);
            lp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            lp.setAlpha(80);  conePath(360, 780); c.drawPath(path, lp);

            lp.setShader(null);
            lp.setXfermode(null);
            c.restore();

            drawDiscoLight(c, t);
        }

        /** The luminous hands, re-struck over the darkness so they cut through. */
        private void drawTimerGlow(Canvas c) {
            if (groove < 0.02f) return;             // only once the lights drop
            float cx = 68, cy = 428, r = 21f;
            long end = TimerActivity.sceneEndAt;
            long remain = end > 0 ? Math.max(0, (end - System.currentTimeMillis()) / 1000) : 0;
            int min = (int) ((remain / 60) % 60), sec = (int) (remain % 60);
            double ma = Math.toRadians((min + sec / 60.0) * 6 - 90);
            double sa = Math.toRadians(sec * 6 - 90);
            float glow = groove;

            lp.setShader(null);
            lp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            lp.setStyle(Paint.Style.STROKE);
            lp.setStrokeCap(Paint.Cap.ROUND);

            lp.setColor(withAlpha(0xFF6FE3A0, (int) (130 * glow)));
            lp.setStrokeWidth(7f);
            c.drawLine(cx, cy, (float) (cx + Math.cos(ma) * r * 0.55f),
                       (float) (cy + Math.sin(ma) * r * 0.55f), lp);
            c.drawLine(cx, cy, (float) (cx + Math.cos(sa) * r * 0.85f),
                       (float) (cy + Math.sin(sa) * r * 0.85f), lp);

            lp.setColor(withAlpha(0xFFD8FFE6, (int) (230 * glow)));
            lp.setStrokeWidth(2.4f);
            c.drawLine(cx, cy, (float) (cx + Math.cos(ma) * r * 0.55f),
                       (float) (cy + Math.sin(ma) * r * 0.55f), lp);
            lp.setStrokeWidth(1.3f);
            c.drawLine(cx, cy, (float) (cx + Math.cos(sa) * r * 0.85f),
                       (float) (cy + Math.sin(sa) * r * 0.85f), lp);

            lp.setStyle(Paint.Style.FILL);
            lp.setXfermode(null);
        }

        // Where the second lamp hangs and what it aims at. Both descend with
        // the ball, so the rig arrives together.
        private float lamp2X() { return 176f; }
        private float lamp2Y() { return -70f + groove * 150f; }
        private float ballX()  { return 300f; }
        private float ballY()  { return -62f + groove * 146f; }

        /** Where a hung thing actually is once it has swung. */
        private float swungX(float x, float y, float ang) {
            return x - y * (float) Math.sin(ang);
        }

        private float swungY(float y, float ang) {
            return y * (float) Math.cos(ang);
        }

        /**
         * Which way the lamp is pointing: aimed at the ball where it rests,
         * plus however far it has swung. The beam that is drawn and the beam
         * the trace uses are the same vector, so what you see is what lights.
         */
        private float lampAim() {
            float dx = ballX() - lamp2X(), dy = ballY() - lamp2Y();
            return (float) Math.atan2(dy, dx) + lamp2Ang;
        }

        /** A small art light on a stem, lowered in and aimed at the ball. */
        private void drawDiscoLamp(Canvas c) {
            if (groove < 0.02f) return;
            int a = (int) (255 * Math.min(1f, groove * 1.6f));
            float lx = lamp2X(), ly = lamp2Y();

            c.save();
            c.rotate((float) Math.toDegrees(lamp2Ang), lx, 0);

            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(LINE, a));
            c.drawRect(lx - 2, 0, lx + 2, ly - 6, p);       // stem
            c.drawCircle(lx, ly - 6, 5, p);                 // yoke

            // barrel pointing at the ball
            float dx = ballX() - lx, dy = ballY() - ly;
            float aim = (float) Math.toDegrees(Math.atan2(dy, dx));
            c.save();
            c.rotate(aim, lx, ly);
            p.setColor(withAlpha(GEAR, a));
            c.drawRect(lx - 9, ly - 7, lx + 17, ly + 7, p);     // body
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f);
            p.setColor(withAlpha(GEAR_EDGE, a));
            c.drawRect(lx - 9, ly - 7, lx + 17, ly + 7, p);
            c.drawLine(lx + 6, ly - 7, lx + 6, ly + 7, p);     // barrel ring
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(LAMP_HOT, a));                 // lens
            c.drawRect(lx + 17, ly - 5, lx + 20, ly + 5, p);
            c.restore();

            // the throw, as three nested wedges so the edge falls off softly
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float aimR = (float) Math.atan2(dy, dx);
            float ux = (float) Math.cos(aimR), uy = (float) Math.sin(aimR);
            float px = -uy, py = ux;
            // a gradient down the beam so it thins out rather than stopping
            // at a flat line, and nested wedges so the sides are soft too
            float ex = lx + ux * (len + 40f), ey = ly + uy * (len + 40f);
            Shader throwShade = new LinearGradient(lx, ly, ex, ey,
                    new int[] {withAlpha(LAMP_HOT, (int) (a * 0.26f)),
                               withAlpha(LAMP_HOT, (int) (a * 0.13f)),
                               withAlpha(LAMP_HOT, 0)},
                    new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            lp.setShader(throwShade);
            lp.setXfermode(null);
            for (int b = 0; b < 3; b++) {
                float wide = 22f + b * 22f;
                lp.setAlpha(150 - b * 45);
                path.reset();
                path.moveTo(lx + ux * 20 + px * 5, ly + uy * 20 + py * 5);
                path.lineTo(lx + ux * 20 - px * 5, ly + uy * 20 - py * 5);
                path.lineTo(lx + ux * (len + 40f) - px * wide,
                            ly + uy * (len + 40f) - py * wide);
                path.lineTo(lx + ux * (len + 40f) + px * wide,
                            ly + uy * (len + 40f) + py * wide);
                path.close();
                c.drawPath(path, lp);
            }
            lp.setShader(null);
            lp.setAlpha(255);
            c.restore();
        }

        /**
         * Mirror-ball light, actually traced: the ball is a sphere of small
         * facets, each one reflecting the second lamp. A facet is lit when its
         * normal faces the lamp; the reflected ray is thrown into the room and
         * drawn where it lands. The spots sweep and wink out at the terminator
         * the way real ones do, and nothing about it can seam or jump because
         * it is all continuous rotation.
         */
        private static final int LATS = 26, LONS = 60;   // ~1560 facets
        // beam half-angles: full brightness inside, fading to nothing outside
        private static final float COS_INNER = 0.955f;   // about 17 degrees
        private static final float COS_OUTER = 0.855f;   // about 31 degrees
        private final float[] cosLat = new float[LATS], sinLat = new float[LATS];
        private final float[] cosLon = new float[LONS], sinLon = new float[LONS];
        private boolean latBuilt = false;

        private void buildGearMask() {
            gearMask.reset();
            gearMask.addRect(418, 246, 432, 440, Path.Direction.CW);      // column
            gearMask.addRect(356, 246, 436, 284, Path.Direction.CW);      // lamphouse
            polyInto(maskTmp, new float[]{372,284, 414,284, 404,308, 382,308});
            gearMask.op(maskTmp, Path.Op.UNION);                          // lens cone
            polyInto(maskTmp, new float[]{385,437, 427,437, 447,467, 365,467});
            gearMask.op(maskTmp, Path.Op.UNION);                          // baseboard
            gearMaskBuilt = true;
        }

        private void drawDiscoLight(Canvas c, float t) {
            if (groove < 0.02f) return;
            // both of them swing, and both ends of the light path matter
            float bx = swungX(ballX(), ballY(), discoAng);
            float by = swungY(ballY(), discoAng);
            if (!gearMaskBuilt) buildGearMask();

            // Where the lamp actually hangs, in three dimensions: it sits in
            // front of the ball, which is what throws the reflections wide.
            float lx = swungX(lamp2X(), lamp2Y(), lamp2Ang);
            float ly = swungY(lamp2Y(), lamp2Ang);
            final float LZ = 150f, R = 34f, WALL = 300f;
            // Beam axis in three dimensions. At rest it points exactly at the
            // ball centre; the lamp's swing rotates it in the plane of the
            // screen, which is what walks the reflections off the ball.
            float aim = lampAim();
            float flat = (float) Math.hypot(ballX() - lamp2X(), ballY() - lamp2Y());
            float axx = (float) Math.cos(aim) * flat;
            float axy = (float) Math.sin(aim) * flat;
            float axz = -LZ;
            float an = (float) Math.sqrt(axx * axx + axy * axy + axz * axz);
            axx /= an; axy /= an; axz /= an;

            lp.setShader(null);
            lp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            c.save();
            c.clipRect(0, 0, 480, TBL_NEAR);        // nothing lands on the floor
            c.clipOutPath(rabMask);                 // he stands in front of it
            c.clipOutPath(gearMask);                // so does the enlarger

            if (!latBuilt) {
                for (int i = 0; i < LATS; i++) {
                    double lat = Math.toRadians(-72 + i * (144.0 / (LATS - 1)));
                    cosLat[i] = (float) Math.cos(lat);
                    sinLat[i] = (float) Math.sin(lat);
                }
                latBuilt = true;
            }
            for (int k = 0; k < LONS; k++) {          // spin is common to all rows
                double lon = k * (Math.PI * 2 / LONS) + ballPhase * 0.13;
                cosLon[k] = (float) Math.cos(lon);
                sinLon[k] = (float) Math.sin(lon);
            }

            for (int i = 0; i < LATS; i++) {
                float cl = cosLat[i], sl = sinLat[i];
                for (int k = 0; k < LONS; k++) {
                    float nx = cl * cosLon[k];
                    float ny = sl;
                    float nz = cl * sinLon[k];

                    // this facet's own place on the ball
                    float px3 = bx + R * nx, py3 = by + R * ny, pz3 = R * nz;

                    // and its own direction to the lamp - per facet, so moving
                    // either end re-aims every reflection independently
                    float ldx = lx - px3, ldy = ly - py3, ldz = LZ - pz3;
                    float ld = (float) Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
                    ldx /= ld; ldy /= ld; ldz /= ld;

                    float ndl = nx * ldx + ny * ldy + nz * ldz;
                    if (ndl <= 0.08f) continue;             // facet faces away

                    // is this facet actually standing in the beam? the cone
                    // has a soft shoulder, so swinging the lamp off the ball
                    // fades the reflections out instead of cutting them
                    float inCone = -(ldx * axx + ldy * axy + ldz * axz);
                    if (inCone <= COS_OUTER) continue;
                    float lit = inCone >= COS_INNER ? 1f
                            : (inCone - COS_OUTER) / (COS_INNER - COS_OUTER);
                    lit *= lit;

                    // mirror the incoming ray about the facet
                    float rx = 2 * ndl * nx - ldx;
                    float ry = 2 * ndl * ny - ldy;
                    float rz = 2 * ndl * nz - ldz;
                    if (rz > -0.10f) continue;              // not heading into the room

                    // and follow it to the back wall
                    float tHit = (-WALL - pz3) / rz;
                    if (tHit <= 0) continue;
                    float sx = px3 + rx * tHit;
                    float sy = py3 + ry * tHit;
                    if (sx < -30 || sx > 510 || sy < -30 || sy > 670) continue;

                    float size = 2.2f + tHit * 0.009f;
                    int col = ((i + k) % 3 == 0) ? PINK
                            : ((i + k) % 3 == 1) ? LAMP_HOT : PRINT;
                    // brightness by facet angle, thinning with throw distance
                    int alpha = (int) (groove * 900f * ndl * ndl * lit
                                       * (1200f / (1200f + tHit)));
                    if (alpha <= 3) continue;
                    lp.setColor(withAlpha(col, Math.min(255, alpha)));
                    c.drawRect(sx - size, sy - size * 0.85f,
                               sx + size, sy + size * 0.85f, lp);
                }
            }

            c.restore();
            lp.setXfermode(null);
        }

        private void drawSafelight(Canvas c, float t) {
            float flick = 0.90f + 0.10f * (float) Math.sin(t * 3.1)
                                 * (float) Math.sin(t * 1.31);
            c.save();
            c.rotate((float) Math.toDegrees(lampAng), LAMP_PIVX, LAMP_PIVY);   // hangs from the cord
            p.setStyle(Paint.Style.FILL);
            p.setColor(LINE);
            c.drawRect(446, LAMP_PIVY, 458, 62, p);     // cord, from off screen
            p.setColor(LAMP);
            path.reset();                               // full conical shade
            path.moveTo(410, 62); path.lineTo(494, 62);
            path.lineTo(478, 92); path.lineTo(426, 92);
            path.close();
            c.drawPath(path, p);
            p.setColor(blend(LAMP, INK, 0.25f));        // rim under the shade
            c.drawRect(426, 90, 478, 94, p);
            p.setColor(withAlpha(LAMP_HOT, (int) (235 * flick)));
            c.drawRect(428, 92, 476, 99, p);            // hot bulb line

            p.setColor(withAlpha(LAMP, (int) (15 * flick)));
            path.reset();
            // symmetric about the bulb centre (452); the right half runs off
            // the panel until the lamp swings, which is when it shows
            path.moveTo(430, 99); path.lineTo(474, 99);
            path.lineTo(760, 700); path.lineTo(144, 700);
            path.close();
            // fade it out down its length so it does not stop dead at the bench
            lp.setXfermode(null);
            lp.setShader(new LinearGradient(452, 99, 452, 660,
                    new int[] {withAlpha(LAMP, (int) (20 * flick)),
                               withAlpha(LAMP, (int) (10 * flick)),
                               withAlpha(LAMP, 0)},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(path, lp);
            lp.setShader(null);
            c.restore();
        }

        private void drawGrain(Canvas c, float t) {
            long seed = (long) (t * 8);
            p.setColor(withAlpha(0xFFFFFFFF, 8));
            for (int i = 0; i < 22; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float gx = Math.abs((seed >> 17) % 480);
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float gy = Math.abs((seed >> 17) % 640);
                c.drawRect(gx, gy, gx + 1.4f, gy + 1.4f, p);
            }
        }

        private int blend(int a, int b, float f) {
            f = Math.max(0f, Math.min(1f, f));
            return Color.rgb(
                (int) (Color.red(a)   + (Color.red(b)   - Color.red(a))   * f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                (int) (Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * f));
        }

        private int withAlpha(int col, int a) {
            return (Math.max(0, Math.min(255, a)) << 24) | (col & 0x00FFFFFF);
        }
    }
}
