package com.calypso.darkroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Darkroom timer. Red-on-black, driven by the R1's scroll wheel (arrives as
 * DPAD_UP/DOWN from the hall sensor) and side button (arrives as VOLUME_DOWN).
 *
 * The R1's single physical button is remapped from POWER to F1 at the
 * keylayout level (scripts/build-super.sh), so it arrives here as an ordinary
 * key press - no screen-off, no lockscreen, no SOS/camera gestures. Outside
 * this activity, tap-root's bundled Power Key accessibility service makes
 * the same key lock the screen, passing it through while we are foreground. A partial wake lock keeps timers,
 * speech and beeps running if the screen times out.
 *
 * Timer mode is fully eyes-free: wheel detents click and set minutes, the
 * button confirms and speaks the value, second wheel pass sets 15 s steps,
 * button starts, button resets. Five quick presses toggle blackout.
 *
 * Process mode runs user-defined steps (name, duration, agitation cycle),
 * announcing each step and waiting for a button confirm between them.
 */
public class TimerActivity extends Activity implements TextToSpeech.OnInitListener {

    // ---- modes ----
    private static final int HOME = 0, TIMER = 1, PROCESS = 2;
    private int mode = HOME;

    // ---- timer-mode state machine ----
    private static final int T_SET_MIN = 0, T_SET_SEC = 1, T_RUN = 2, T_DONE = 3;
    private int tState = T_SET_MIN;
    private int setMin = 0, setSec = 0;
    private long endAt = 0;

    // ---- process-mode state ----
    private static class Proc {
        String name;
        ArrayList<Step> steps = new ArrayList<Step>();
        Proc(String n) { name = n; }
    }
    private ArrayList<Proc> procs = new ArrayList<Proc>();
    private ArrayList<Step> steps = new ArrayList<Step>();  // steps of the running process
    private int stepIx = 0;
    private boolean stepRunning = false;     // false = announced, awaiting confirm
    private long stepEndAt = 0;
    private Step cur;

    private static class Step {
        String name; int sec; int agInit; int agEvery; int agFor;
        int r35, r120, r45;      // replenishment ml per 35mm roll / 120 roll / 4x5 sheet
        Step(String n, int s, int i, int e, int f, int a, int b, int c) {
            name = n; sec = s; agInit = i; agEvery = e; agFor = f; r35 = a; r120 = b; r45 = c;
        }
        boolean replenishes() { return r35 > 0 || r120 > 0 || r45 > 0; }
    }

    // How much film is in the tank this run - asked at procedure start.
    private int rolls35 = 0, rolls120 = 0, sheets45 = 0;
    private int askIx = -1;      // 0=35mm, 1=120, 2=4x5, -1 = not asking
    private static final String[] ASK_LABEL = {"35 millimeter rolls", "120 rolls", "4 by 5 sheets"};

    // ---- plumbing ----
    private TextToSpeech tts;
    private ToneGenerator tone;
    private ToneGenerator tickTone;
    private int lastTickSec = -1;
    private int lastStepSec = -1;
    private final Handler h = new Handler(Looper.getMainLooper());
    private android.os.HandlerThread sndThread;
    private Handler snd;
    private TextView big, small, hint;
    private LinearLayout homeMenu, procMenu;
    private GearIcon gearBtn;
    private Button backBtn;
    private VoiceIcon voiceBtn;

    /** Minimal line-art cog; opens the setup screen. */
    private class GearIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        GearIcon(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            p.setColor(0xFFCC1A00);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float rOut = Math.min(getWidth(), getHeight()) * 0.34f;   // tooth tip
            float rIn = rOut * 0.76f;                                  // tooth root
            int teeth = 8;
            float half = (float) (Math.PI / teeth) * 0.34f;            // half tooth width
            path.reset();
            for (int i = 0; i < teeth; i++) {
                double c = i * 2 * Math.PI / teeth;                    // tooth centre
                double next = (i + 1) * 2 * Math.PI / teeth;
                double[] a = {c - half, c + half, next - half - (next - c - half) * 0.0};
                // root before the tooth, up the flank, across the tip, down the flank
                if (i == 0) path.moveTo((float) (cx + Math.cos(c - half * 2.2) * rIn),
                                        (float) (cy + Math.sin(c - half * 2.2) * rIn));
                else path.lineTo((float) (cx + Math.cos(c - half * 2.2) * rIn),
                                 (float) (cy + Math.sin(c - half * 2.2) * rIn));
                path.lineTo((float) (cx + Math.cos(a[0]) * rOut), (float) (cy + Math.sin(a[0]) * rOut));
                path.lineTo((float) (cx + Math.cos(a[1]) * rOut), (float) (cy + Math.sin(a[1]) * rOut));
                path.lineTo((float) (cx + Math.cos(c + half * 2.2) * rIn),
                            (float) (cy + Math.sin(c + half * 2.2) * rIn));
            }
            path.close();
            cv.drawPath(path, p);
            cv.drawCircle(cx, cy, rOut * 0.34f, p);                    // hub
        }
    }

    /** Minimal line-art mouth; a slash through it when the voice is muted. */
    private class VoiceIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        VoiceIcon(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(3.5f);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            float l = w * 0.2f, r = w * 0.8f, cy = h * 0.5f;
            p.setColor(voiceOn ? 0xFFCC1A00 : 0xFF801000);
            // upper lip: two shallow arcs meeting at the cupid's bow
            path.reset();
            path.moveTo(l, cy);
            path.quadTo(w * 0.35f, h * 0.30f, w * 0.5f, h * 0.42f);
            path.quadTo(w * 0.65f, h * 0.30f, r, cy);
            // lower lip
            path.moveTo(l, cy);
            path.quadTo(w * 0.5f, h * 0.74f, r, cy);
            cv.drawPath(path, p);
            if (!voiceOn) {
                p.setColor(0xFFCC1A00);
                cv.drawLine(w * 0.16f, h * 0.82f, w * 0.84f, h * 0.18f, p);
            }
        }
    }

    // ---- voice selection: long-press the mouth cycles en-US voices ----

    private java.util.ArrayList<android.speech.tts.Voice> usVoices() {
        java.util.ArrayList<android.speech.tts.Voice> out =
                new java.util.ArrayList<android.speech.tts.Voice>();
        try {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                // local en-US voices only - network ones stall without wifi
                if ("en".equals(v.getLocale().getLanguage())
                        && "US".equals(v.getLocale().getCountry())
                        && !v.isNetworkConnectionRequired()) {
                    out.add(v);
                }
            }
            java.util.Collections.sort(out, new java.util.Comparator<android.speech.tts.Voice>() {
                public int compare(android.speech.tts.Voice a, android.speech.tts.Voice b) {
                    return a.getName().compareTo(b.getName());
                }
            });
        } catch (Exception ignored) {}
        return out;
    }

    private void applySavedVoice() {
        String name = getSharedPreferences("safelight", MODE_PRIVATE).getString("ttsVoice", null);
        if (name == null) return;
        for (android.speech.tts.Voice v : usVoices()) {
            if (v.getName().equals(name)) { tts.setVoice(v); return; }
        }
    }

    private void nextVoice() {
        java.util.ArrayList<android.speech.tts.Voice> vs = usVoices();
        if (vs.isEmpty()) { say("No other voices installed."); return; }
        String curName = tts.getVoice() != null ? tts.getVoice().getName() : "";
        int ix = 0;
        for (int i = 0; i < vs.size(); i++) {
            if (vs.get(i).getName().equals(curName)) { ix = (i + 1) % vs.size(); break; }
        }
        android.speech.tts.Voice v = vs.get(ix);
        tts.setVoice(v);
        getSharedPreferences("safelight", MODE_PRIVATE).edit()
                .putString("ttsVoice", v.getName()).apply();
        sayNow("Voice " + (ix + 1) + " of " + vs.size() + ". Three minutes, agitate, rest.");
    }

    private void renderVoiceBtn() {
        voiceBtn.setAlpha(voiceOn ? 1f : 0.6f);
        voiceBtn.invalidate();
    }
    private View noSel;     // invisible focus holder = "nothing selected"
    private boolean blackout = false;
    private View blackView;

    private android.os.PowerManager.WakeLock wake;


    // The live wallpaper draws a desk timer whose hands track this deadline
    // (same process). 0 = no timer running.
    static volatile long sceneEndAt = 0;

    @Override protected void onStart() { super.onStart(); }
    @Override protected void onStop()  { super.onStop(); }

    // ---- setup screen: the grants the system cannot give silently ----

    private LinearLayout setupMenu;
    private boolean setupSnoozed = false;

    private boolean canWriteSettings() {
        return android.provider.Settings.System.canWrite(this);
    }

    private boolean hasMic() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean wallpaperSet() {
        android.app.WallpaperInfo wi =
                android.app.WallpaperManager.getInstance(this).getWallpaperInfo();
        return wi != null && getPackageName().equals(wi.getPackageName());
    }

    /** Required grants only - the wallpaper and the tile are optional extras. */
    private boolean setupIncomplete() {
        return !canWriteSettings() || !hasMic();
    }

    private void askWriteSettings() {
        startActivity(new android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private void askMic() {
        requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 1);
    }

    /**
     * No API reports whether a QS tile is added, so the tick is remembered:
     * set when the system reports the tile added (or already added), and on
     * older releases once the user has been sent to do it by hand.
     */
    private boolean tileAdded() {
        return getSharedPreferences("safelight", MODE_PRIVATE).getBoolean("tileAdded", false);
    }

    private void setTileAdded() {
        getSharedPreferences("safelight", MODE_PRIVATE).edit().putBoolean("tileAdded", true).apply();
        if (setupMenu.getVisibility() == View.VISIBLE) showSetup();
    }

    private void askTile() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            say("Add the Safelight tile by editing your quick settings.");
            setTileAdded();
            return;
        }
        android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
        sbm.requestAddTileService(
                new android.content.ComponentName(this, SafelightTile.class),
                "Safelight",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_safelight),
                getMainExecutor(),
                new java.util.function.Consumer<Integer>() {
                    public void accept(Integer r) {
                        if (r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                         || r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            setTileAdded();
                        }
                    }
                });
    }

    private void askWallpaper() {
        android.content.Intent i = new android.content.Intent(
                "android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
        i.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
                new android.content.ComponentName(this, DarkroomWallpaper.class));
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private String mark(boolean done, String label) {
        return (done ? "\u2713 " : "") + label;
    }

    private void showSetup() {
        mode = HOME;
        homeMenu.setVisibility(View.GONE);
        procMenu.setVisibility(View.GONE);
        setupMenu.removeAllViews();
        setupMenu.addView(menuButton(mark(canWriteSettings(), "SAFELIGHT ACCESS"),
                new Runnable() { public void run() { askWriteSettings(); } }));
        setupMenu.addView(menuButton(mark(hasMic(), "MICROPHONE"),
                new Runnable() { public void run() { askMic(); } }));
        // Ticked once added, but still re-offers the prompt on every tap -
        // the tile can be removed from quick settings without telling us.
        setupMenu.addView(menuButton(mark(tileAdded(), "SAFELIGHT TILE"),
                new Runnable() { public void run() { askTile(); } }));
        setupMenu.addView(menuButton(mark(wallpaperSet(), "WALLPAPER"),
                new Runnable() { public void run() { askWallpaper(); } }));
        setupMenu.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.VISIBLE);
        gearBtn.setVisibility(View.GONE);
        menuOff = 0;
        noSel.requestFocusFromTouch();
        big.setText("SETUP");
        big.setTextSize(36);
        big.setPadding(0, 0, 0, 0);
        small.setText("");
        hint.setText("grants the app cannot give itself\ntick = done");
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (setupMenu.getVisibility() == View.VISIBLE) showSetup();
    }

    @Override protected void onResume() {
        super.onResume();
        if (setupMenu != null && setupMenu.getVisibility() == View.VISIBLE) {
            showSetup();        // refresh ticks after returning from a system screen
            return;
        }
        // Required grants missing (safelight or mic) - go straight to setup.
        // Snoozed for the rest of this launch once the user backs out.
        if (!setupSnoozed && setupIncomplete()) showSetup();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        wake = ((android.os.PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "darkroom:timer");
        wake.acquire();
        sndThread = new android.os.HandlerThread("darkroom-snd");
        sndThread.start();
        snd = new Handler(sndThread.getLooper());
        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        tickTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);
        tts = new TextToSpeech(this, this);
        loadSteps();
        buildUi();
        showHome();
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        tts.setLanguage(Locale.US);
        applySavedVoice();
        // Duck the user's music while voice lines play: hold transient
        // may-duck audio focus whenever the TTS queue is busy.
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) {
                snd.post(new Runnable() { public void run() { duck(true); } });
            }
            @Override public void onDone(String id) { maybeUnduck(); }
            @Override public void onError(String id) { maybeUnduck(); }
            @Override public void onStop(String id, boolean interrupted) { maybeUnduck(); }
        });
    }

    // Standard focus-based ducking: the player dips itself while she speaks.
    private android.media.AudioFocusRequest focusReq;
    private boolean ducked = false;

    private void duck(boolean on) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (on == ducked || am == null) return;
        if (focusReq == null) {
            focusReq = new android.media.AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .build();
        }
        if (on) am.requestAudioFocus(focusReq); else am.abandonAudioFocusRequest(focusReq);
        ducked = on;
    }

    /** Release focus shortly after the queue drains (bridges sentence gaps). */
    private void maybeUnduck() {
        snd.postDelayed(new Runnable() { public void run() {
            if (tts != null && !tts.isSpeaking()) duck(false);
        } }, 400);
    }

    @Override protected void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        h.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
        if (tone != null) tone.release();
        if (tickTone != null) tickTone.release();
        if (sndThread != null) sndThread.quitSafely();
        if (sr != null) sr.destroy();
        duck(false);
        TimerWidget.push(this, -1);
        super.onDestroy();
    }

    // Spoken at reduced gain so the voice sits with the music, not on top of it.
    private static final float VOICE_GAIN = 0.5f;

    private Bundle voiceParams() {
        Bundle b = new Bundle();
        b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VOICE_GAIN);
        return b;
    }

    // Voice can be muted from the corner button: announcements become a short
    // beep, scroll readouts go silent (the wheel already clicks), and the
    // timer-finished alert sounds regardless.
    private boolean voiceOn = true;

    /**
     * Agitation-boundary cue: voice on = beep + spoken word; muted = a
     * distinct beep pattern instead (2 = agitate, 1 = rest).
     */
    private void cue(String line, int beeps) {
        if (voiceOn) {
            beep();
            say(line);
            return;
        }
        cueBeeps(beeps);
    }

    private void restCue() {
        h.postDelayed(new Runnable() { public void run() {
            if (mode == PROCESS && stepRunning) cue("Rest.", 1);
        } }, 150);
    }

    // While a beep pattern plays, ticks stand down (see sndTick).
    private volatile long cueUntil = 0;

    private void cueBeeps(final int beeps) {
        cueUntil = System.currentTimeMillis() + beeps * 200L + 150;
        // chained scheduling, never sleep on the sound thread - a blocked
        // thread delays the metronome ticks queued behind it
        for (int i = 0; i < beeps; i++) {
            snd.postDelayed(new Runnable() { public void run() {
                tone.startTone(ToneGenerator.TONE_CDMA_PIP, 120);
            } }, i * 200L);
        }
    }

    // speak() is a binder call into the TTS service and can stall for tens of
    // ms (longer when the service is cold) - keep it off the UI thread.
    private void say(final String s) {
        if (!voiceOn) { beep(); return; }
        snd.post(new Runnable() { public void run() {
            if (tts != null) tts.speak(s, TextToSpeech.QUEUE_ADD, voiceParams(), "dk");
        } });
    }

    // Interrupts anything queued - for rapid-fire scroll readouts.
    private void sayNow(final String s) {
        if (!voiceOn) return;
        snd.post(new Runnable() { public void run() {
            if (tts != null) tts.speak(s, TextToSpeech.QUEUE_FLUSH, voiceParams(), "dk");
        } });
    }

    // ToneGenerator.startTone and Vibrator.vibrate can block for tens of ms -
    // enough to visibly stutter the countdown. All sound runs on its own thread.
    private void click()    { snd.post(new Runnable() { public void run() { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 30); } }); }
    private void beep()     { snd.post(new Runnable() { public void run() { tone.startTone(ToneGenerator.TONE_CDMA_PIP, 120); } }); }
    private void longBeep() { snd.post(new Runnable() { public void run() { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800); } }); }
    /** True when an actual music player is playing (same filter the wallpaper uses). */
    private boolean musicActive() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return false;
        try {
            for (android.media.AudioPlaybackConfiguration cfg : am.getActivePlaybackConfigurations()) {
                android.media.AudioAttributes at = cfg.getAudioAttributes();
                if (at != null
                        && at.getUsage() == android.media.AudioAttributes.USAGE_MEDIA
                        && at.getContentType() == android.media.AudioAttributes.CONTENT_TYPE_MUSIC) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // Ticks are scheduled one at a time on the sound thread, re-aligned to the
    // timer's wall-clock deadline every second - a looping AudioTrack runs on
    // the audio hardware clock and audibly drifts from the countdown.
    private volatile boolean tickWanted = false;
    private volatile long tickEndAt = 0;

    private final Runnable sndTick = new Runnable() {
        public void run() {
            if (!tickWanted) return;
            long now = System.currentTimeMillis();
            long left = tickEndAt - now;
            if (left <= 0) return;
            // yield to agitation beeps; skip this tick, keep the schedule
            if (now >= cueUntil && !musicActive()) {
                tickTone.startTone(ToneGenerator.TONE_PROP_BEEP, 15);
            }
            long delay = left % 1000;               // next second boundary
            if (delay < 60) delay += 1000;          // never double-fire a boundary
            snd.postDelayed(this, delay);
        }
    };

    private void startTicking() {
        tickWanted = true;
        tickEndAt = sceneEndAt;
        snd.removeCallbacks(sndTick);
        // first tick on the first second boundary, not at the moment of start
        long left = tickEndAt - System.currentTimeMillis();
        long delay = left % 1000;
        if (delay < 60) delay += 1000;
        snd.postDelayed(sndTick, delay);
    }

    private void stopTicking() {
        tickWanted = false;
        sceneEndAt = 0;     // wallpaper desk timer back to rest
        snd.removeCallbacks(sndTick);
    }

    private void buzz(final int ms) {
        snd.post(new Runnable() { public void run() {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } });
    }

    // Gentle beep+buzz every 1.5 s at 0:00 until the button is pressed.
    private final Runnable alertLoop = new Runnable() {
        public void run() {
            if (mode != TIMER || tState != T_DONE) return;
            beep();
            buzz(250);
            h.postDelayed(this, 1500);
        }
    };

    // ---------------------------------------------------------------- UI

    /** Line-art beaker with a pouring flask, animated while visible. */
    private class BeakerView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint();
        boolean pouring = false;    // false = static info graphic, no animation

        BeakerView(Context c) {
            super(c);
            p.setColor(0xFFFF2000);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setAntiAlias(true);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), hgt = getHeight();
            long t = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            // beaker: open-topped vessel, bottom half of the view
            float bx = w * 0.15f, bw = w * 0.55f, by = hgt * 0.45f, bh = hgt * 0.5f;
            cv.drawLine(bx, by, bx, by + bh, p);
            cv.drawLine(bx + bw, by, bx + bw, by + bh, p);
            cv.drawLine(bx, by + bh, bx + bw, by + bh, p);
            cv.drawLine(bx - w * 0.05f, by, bx, by + 6, p);          // spout lips
            cv.drawLine(bx + bw + w * 0.05f, by, bx + bw, by + 6, p);
            if (!pouring) {
                // static: just the beaker with a settled liquid level
                float lvl = by + bh - bh * 0.4f;
                cv.drawLine(bx + 4, lvl, bx + bw - 4, lvl, p);
                return;
            }
            // liquid: level rises on a slow loop, gentle wobble on top
            float cycle = (t % 4000) / 4000f;
            float lvl = by + bh - (bh * 0.55f) * cycle - 4;
            float wob = (float) Math.sin(t / 180.0) * 2.5f;
            cv.drawLine(bx + 4, lvl + wob, bx + bw - 4, lvl - wob, p);
            // pouring flask, tilted, top right
            float fx = bx + bw + w * 0.02f, fy = hgt * 0.06f;
            cv.save();
            cv.rotate(-35, fx + w * 0.12f, fy + hgt * 0.12f);
            cv.drawRect(fx, fy, fx + w * 0.24f, fy + hgt * 0.2f, p);
            cv.restore();
            // stream: dots falling from the flask mouth into the beaker
            float sx = bx + bw * 0.72f;
            for (int i = 0; i < 3; i++) {
                float ph = ((t + i * 260) % 800) / 800f;
                float dy = fy + hgt * 0.18f + (lvl - fy - hgt * 0.18f) * ph;
                cv.drawCircle(sx, dy, 2.5f, p);
            }
            if (isShown()) postInvalidateDelayed(50);
        }
    }

    private BeakerView beaker;
    private TextView replText;
    private LinearLayout replRow;
    private AgitView agitView;
    private CelebrationView celebrate;
    private boolean procDone = false;

    /**
     * Line-art film strip scrolling by, each frame holding a tiny one-line
     * negative (sun over hills, horizons, little scenes). Pure strokes, all
     * reds - the quiet end-of-process reward.
     */
    private class CelebrationView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        CelebrationView(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(0xFFCC1A00);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), hgt = getHeight();
            long t = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            float top = hgt * 0.10f, bot = hgt * 0.90f;
            float off = t / 24f;
            // strip border
            cv.drawRect(2, top, w - 2, bot, p);
            // sprocket holes
            p.setStrokeWidth(2f);
            for (float x = -(off % 34); x < w; x += 34) {
                cv.drawRoundRect(new android.graphics.RectF(x + 8, top + 6, x + 21, top + 14), 2, 2, p);
                cv.drawRoundRect(new android.graphics.RectF(x + 8, bot - 14, x + 21, bot - 6), 2, 2, p);
            }
            // frames with tiny negatives, 4 motifs cycling
            float fw = 130, sp = fw + 14;
            float fy = top + 20, fh = bot - top - 40;
            int k = (int) (off / sp);
            for (float x = -(off % sp); x < w; x += sp, k++) {
                float fx = x + 8;
                cv.drawRect(fx, fy, fx + fw, fy + fh, p);
                cv.save();
                cv.clipRect(fx, fy, fx + fw, fy + fh);
                path.reset();
                switch (((k % 4) + 4) % 4) {
                    case 0:     // sun over jagged hills
                        path.addCircle(fx + fw * 0.30f, fy + fh * 0.35f, 8, android.graphics.Path.Direction.CW);
                        path.moveTo(fx + 4, fy + fh - 6);
                        path.lineTo(fx + fw * 0.35f, fy + fh * 0.5f);
                        path.lineTo(fx + fw * 0.55f, fy + fh * 0.75f);
                        path.lineTo(fx + fw * 0.75f, fy + fh * 0.45f);
                        path.lineTo(fx + fw - 4, fy + fh - 6);
                        break;
                    case 1:     // moon over a rolling hill
                        path.addCircle(fx + fw * 0.70f, fy + fh * 0.40f, 9, android.graphics.Path.Direction.CW);
                        path.moveTo(fx + 6, fy + fh - 8);
                        path.quadTo(fx + fw / 2f, fy + fh * 0.4f, fx + fw - 6, fy + fh - 8);
                        break;
                    case 2:     // mountain skyline
                        path.moveTo(fx + 6, fy + fh - 6);
                        path.lineTo(fx + fw * 0.4f, fy + fh * 0.3f);
                        path.lineTo(fx + fw * 0.6f, fy + fh * 0.6f);
                        path.lineTo(fx + fw - 6, fy + fh * 0.25f);
                        break;
                    default:    // big sun over a dune
                        path.addCircle(fx + fw / 2f, fy + fh * 0.42f, 10, android.graphics.Path.Direction.CW);
                        path.moveTo(fx + fw * 0.25f, fy + fh - 6);
                        path.quadTo(fx + fw / 2f, fy + fh * 0.55f, fx + fw * 0.75f, fy + fh - 6);
                        break;
                }
                cv.drawPath(path, p);
                cv.restore();
            }
            p.setStrokeWidth(2.5f);
            if (isShown()) postInvalidateDelayed(40);
        }
    }

    /**
     * AGITATE / REST banner for process steps: the word flanked by liquid
     * lines - rolling waves while agitating, settled flat lines at rest.
     */
    private class AgitView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path wave = new android.graphics.Path();
        boolean agitating = false;

        AgitView(Context c) {
            super(c);
            p.setTypeface(Typeface.MONOSPACE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), hgt = getHeight(), cy = hgt * 0.55f;
            long t = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            String word = agitating ? "AGITATE" : "REST";
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setTextSize(hgt * 0.5f);
            p.setFakeBoldText(true);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setColor(agitating ? 0xFFFF2000 : 0xFF801000);
            cv.drawText(word, w / 2f, cy + hgt * 0.16f, p);

            float tw = p.measureText(word);
            float gap = 24, span = (w - tw) / 2f - gap * 2;
            if (span < 20) span = 20;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(3.5f);
            for (int side = 0; side < 2; side++) {
                float x0 = side == 0 ? (w - tw) / 2f - gap - span : (w + tw) / 2f + gap;
                wave.reset();
                if (agitating) {
                    // rolling wave, phase scrolls with time
                    float ph = (t % 900) / 900f * (float) (Math.PI * 2);
                    wave.moveTo(x0, cy);
                    for (int i = 1; i <= 24; i++) {
                        float fx = x0 + span * i / 24f;
                        wave.lineTo(fx, cy + (float) Math.sin(i / 24f * Math.PI * 3 + ph) * hgt * 0.14f);
                    }
                } else {
                    wave.moveTo(x0, cy);
                    wave.lineTo(x0 + span, cy);
                }
                cv.drawPath(wave, p);
            }
            if (agitating && isShown()) postInvalidateDelayed(50);
        }

        void set(boolean ag) {
            if (ag != agitating) {
                agitating = ag;
                invalidate();
            }
        }
    }

    private void showRepl(Step s, boolean pouring) {
        int ml = s.r35 * rolls35 + s.r120 * rolls120 + s.r45 * sheets45;
        if (!s.replenishes() || ml == 0) { replRow.setVisibility(View.GONE); return; }
        beaker.pouring = pouring;
        beaker.invalidate();
        StringBuilder txt = new StringBuilder();
        if (s.r35 > 0 && rolls35 > 0)   txt.append(rolls35).append(" x 35mm x ").append(s.r35).append("ml\n");
        if (s.r120 > 0 && rolls120 > 0) txt.append(rolls120).append(" x 120 x ").append(s.r120).append("ml\n");
        if (s.r45 > 0 && sheets45 > 0)  txt.append(sheets45).append(" x 4x5 x ").append(s.r45).append("ml\n");
        txt.append("= ").append(ml).append(" ml replenisher\n")
           .append("at the end of this step");
        replText.setText(txt.toString());
        replRow.setVisibility(View.VISIBLE);
    }

    /** Shown once a step finishes: what to actually do with the X ml. */
    private void showReplDone(int ml, String stepName) {
        beaker.pouring = true;
        beaker.invalidate();
        replText.setText("replenish " + stepName + ":\n\n"
                + "add " + ml + " ml to the\n"
                + "stock bottle, then\n"
                + "top back up to the\n"
                + "original volume");
        replRow.setVisibility(View.VISIBLE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setGravity(Gravity.CENTER);

        big = new TextView(this);
        big.setTextColor(0xFFFF2000);
        big.setTextSize(88);
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        big.setGravity(Gravity.CENTER);

        small = new TextView(this);
        small.setTextColor(0xFFCC1A00);
        small.setTextSize(26);
        small.setGravity(Gravity.CENTER);

        hint = new TextView(this);
        hint.setTextColor(0xFF801000);
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(20, 30, 20, 0);

        homeMenu = new LinearLayout(this);
        homeMenu.setOrientation(LinearLayout.VERTICAL);
        homeMenu.setGravity(Gravity.CENTER);
        homeMenu.addView(menuButton("TIMER", new Runnable() { public void run() { enterTimer(); } }));
        homeMenu.addView(menuButton("PROCESS", new Runnable() { public void run() { pickProcess(); } }));
        homeMenu.addView(menuButton("EDIT PROCESSES", new Runnable() { public void run() { editProcs(); } }));
        homeMenu.addView(menuButton("SCREEN OFF", new Runnable() { public void run() { setBlackout(true); } }));

        procMenu = new LinearLayout(this);
        procMenu.setOrientation(LinearLayout.VERTICAL);
        procMenu.setGravity(Gravity.CENTER);
        procMenu.setVisibility(View.GONE);

        setupMenu = new LinearLayout(this);
        setupMenu.setOrientation(LinearLayout.VERTICAL);
        setupMenu.setGravity(Gravity.CENTER);
        setupMenu.setVisibility(View.GONE);

        replRow = new LinearLayout(this);
        replRow.setOrientation(LinearLayout.HORIZONTAL);
        // bottom-align so the text sits level with the beaker vessel (the top
        // half of the beaker view is empty air for the pouring flask)
        replRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        replRow.setVisibility(View.GONE);
        replRow.setPadding(0, 26, 0, 10);
        beaker = new BeakerView(this);
        LinearLayout.LayoutParams bkl = new LinearLayout.LayoutParams(120, 140);
        bkl.setMargins(0, 0, 24, 0);
        beaker.setLayoutParams(bkl);
        replText = new TextView(this);
        replText.setTextColor(0xFFCC1A00);
        replText.setTextSize(16);
        replText.setLineSpacing(8f, 1.15f);
        replRow.addView(beaker);
        replRow.addView(replText);

        agitView = new AgitView(this);
        agitView.setLayoutParams(new LinearLayout.LayoutParams(-1, 92));
        agitView.setVisibility(View.GONE);

        // film strip under COMPLETE at process end
        celebrate = new CelebrationView(this);
        celebrate.setLayoutParams(new LinearLayout.LayoutParams(-1, 110));
        celebrate.setVisibility(View.GONE);

        root.addView(big);
        root.addView(small);
        root.addView(celebrate);
        root.addView(agitView);
        root.addView(homeMenu);
        root.addView(procMenu);
        root.addView(setupMenu);
        root.addView(replRow);
        root.addView(hint);

        blackView = new View(this);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setVisibility(View.GONE);

        // Tap on empty space deselects, so the side button can mean "back".
        // A touch listener (not click) keeps the root out of the wheel's
        // focus order.
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    noSel.requestFocusFromTouch();
                    menuOff = 0;
                }
                return false;
            }
        });

        backBtn = new Button(this);
        backBtn.setText("\u2190");
        backBtn.setTextSize(26);
        backBtn.setTextColor(0xFFCC1A00);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setVisibility(View.GONE);
        backBtn.setFocusable(false);    // touch-only; wheel skips it
        voiceOn = getSharedPreferences("safelight", MODE_PRIVATE).getBoolean("voice", true);
        backBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { quietExit(); }
        });
        android.widget.FrameLayout.LayoutParams blp =
                new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.setMargins(8, 8, 0, 0);

        // voice mute toggle, bottom right on every screen
        voiceBtn = new VoiceIcon(this);
        voiceBtn.setFocusable(false);   // touch-only; wheel skips it
        renderVoiceBtn();
        voiceBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                voiceOn = !voiceOn;
                getSharedPreferences("safelight", MODE_PRIVATE).edit()
                        .putBoolean("voice", voiceOn).apply();
                if (!voiceOn && tts != null) tts.stop();
                renderVoiceBtn();
                if (voiceOn) say("Voice on."); else beep();
            }
        });
        voiceBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!voiceOn) { beep(); return true; }
                nextVoice();
                return true;
            }
        });
        gearBtn = new GearIcon(this);
        gearBtn.setFocusable(false);
        gearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { click(); showSetup(); }
        });
        android.widget.FrameLayout.LayoutParams glp =
                new android.widget.FrameLayout.LayoutParams(64, 64, Gravity.BOTTOM | Gravity.START);
        glp.setMargins(18, 0, 0, 18);

        android.widget.FrameLayout.LayoutParams vlp =
                new android.widget.FrameLayout.LayoutParams(64, 64, Gravity.BOTTOM | Gravity.END);
        vlp.setMargins(0, 0, 18, 18);

        // clearFocus() makes Android hand focus straight back to a button, so
        // "deselected" is modeled as focus on this invisible 0x0 view instead.
        noSel = new View(this);
        noSel.setFocusable(true);
        noSel.setFocusableInTouchMode(true);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(root);
        frame.addView(noSel, new android.widget.FrameLayout.LayoutParams(1, 1));
        frame.addView(backBtn, blp);
        frame.addView(voiceBtn, vlp);
        frame.addView(gearBtn, glp);
        frame.addView(blackView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    private Button menuButton(String label, final Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(22);
        btn.setTextColor(0xFFFF2000);
        btn.setBackgroundColor(0xFF200400);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(340, -2);
        lp.setMargins(0, 14, 0, 14);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return btn;
    }

    @SuppressWarnings("deprecation")
    private void setBlackout(boolean on) {
        blackout = on;
        blackView.setVisibility(on ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.0f : -1.0f;
        getWindow().setAttributes(lp);
        // The status bar clock and gesture bar sit outside our overlay - hide
        // them too or they glow faintly through the blackout.
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                : 0);
        say(on ? "Screen off, audio only. Five quick presses for screen." : "Screen on.");
    }

    private void showHome() {
        mode = HOME;
        h.removeCallbacksAndMessages(null);
        stopTicking();
        backBtn.setVisibility(View.GONE);
        if (replRow != null) replRow.setVisibility(View.GONE);
        if (agitView != null) agitView.setVisibility(View.GONE);
        if (celebrate != null) celebrate.setVisibility(View.GONE);
        procDone = false;
        TimerWidget.push(this, -1);
        big.setPadding(0, 0, 0, 0);
        big.setText("DARKROOM");
        big.setTextSize(36);
        small.setText("");
        if (setupMenu != null && setupMenu.getVisibility() == View.VISIBLE) setupSnoozed = true;
        procMenu.setVisibility(View.GONE);
        setupMenu.setVisibility(View.GONE);
        homeMenu.setVisibility(View.VISIBLE);
        gearBtn.setVisibility(View.VISIBLE);
        menuOff = 0;
        hint.setText("wheel scrolls, side button = confirm\n5 quick presses = screen off/on\nback exits a mode");
    }

    // ---------------------------------------------------------------- timer mode

    private void enterTimer() {
        mode = TIMER;
        tState = T_SET_MIN;
        setMin = 0; setSec = 0;
        homeMenu.setVisibility(View.GONE);
        backBtn.setVisibility(View.VISIBLE);
        gearBtn.setVisibility(View.GONE);
        big.setTextSize(88);
        renderTimer();
        say("Timer. Scroll to set minutes, then press the button.");
    }

    private void renderTimer() {
        int total = (tState == T_RUN || tState == T_DONE)
                ? Math.max(0, (int) ((endAt - System.currentTimeMillis() + 999) / 1000))
                : setMin * 60 + setSec;
        big.setText(String.format(Locale.US, "%d:%02d", total / 60, total % 60));
        switch (tState) {
            case T_SET_MIN: small.setText("set minutes"); hint.setText("scroll = minutes, press = confirm"); break;
            case T_SET_SEC: small.setText("set seconds"); hint.setText("scroll = 15 s steps, press = start"); break;
            case T_RUN:     small.setText("running");     hint.setText("double press = reset, 5x = screen off"); break;
            case T_DONE:    small.setText("done");        hint.setText("press = new timer"); break;
        }
    }

    private final Runnable timerTick = new Runnable() {
        public void run() {
            if (mode != TIMER || tState != T_RUN) return;
            long left = endAt - System.currentTimeMillis();
            if (left <= 0) {
                tState = T_DONE;
                stopTicking();
                longBeep();
                buzz(400);
                say("Time.");
                renderTimer();
                h.postDelayed(alertLoop, 1500);
                return;
            }
            int sec = (int) ((left + 999) / 1000);
            if (sec != lastTickSec) {
                lastTickSec = sec;
                if (sec <= 3) beep();
                final int ws = sec;     // widget render + binder push off the UI thread
                snd.post(new Runnable() { public void run() {
                    TimerWidget.push(TimerActivity.this, ws);
                } });
            }
            renderTimer();
            h.postDelayed(this, 100);
        }
    };

    private String spoken(int min, int sec) {
        StringBuilder s = new StringBuilder();
        if (min > 0) s.append(min).append(min == 1 ? " minute" : " minutes");
        if (sec > 0) {
            if (s.length() > 0) s.append(" ");
            s.append(sec).append(" seconds");
        }
        if (s.length() == 0) s.append("zero");
        return s.toString();
    }

    private void timerScroll(int dir) {
        click();
        if (tState == T_SET_MIN) {
            setMin = Math.max(0, setMin + dir);
            sayNow(String.valueOf(setMin));
        } else if (tState == T_SET_SEC) {
            setSec += dir * 15;
            if (setSec < 0) setSec = 45;
            if (setSec > 45) setSec = 0;
            sayNow(String.valueOf(setSec));
        }
        renderTimer();
    }

    private void timerPress(int presses) {
        switch (tState) {
            case T_SET_MIN:
                tState = T_SET_SEC;
                say(spoken(setMin, 0));
                break;
            case T_SET_SEC:
                if (setMin == 0 && setSec == 0) {
                    // Pressing through a zeroed timer exits to the menu.
                    if (blackout) setBlackout(false);
                    showHome();
                    say("Menu.");
                    return;
                }
                tState = T_RUN;
                lastTickSec = -1;
                endAt = System.currentTimeMillis() + (setMin * 60 + setSec) * 1000L;
                sceneEndAt = endAt;
                say(spoken(setMin, setSec) + ", starting now.");
                startTicking();
                h.post(timerTick);
                break;
            case T_RUN:
                // a lone press mid-run is likely accidental; reset needs two
                if (presses < 2) { click(); break; }
                // falls through
            case T_DONE:
                h.removeCallbacks(timerTick);
                h.removeCallbacks(alertLoop);
                stopTicking();
                tState = T_SET_MIN;
                setMin = 0; setSec = 0;
                say("Reset.");
                break;
        }
        renderTimer();
    }

    // ---------------------------------------------------------------- process mode

    // Submenu styled like the main menu: one button per process, select to run.
    private void pickProcess() {
        if (procs.isEmpty()) { say("No processes yet. Use edit steps."); return; }
        homeMenu.setVisibility(View.GONE);
        procMenu.removeAllViews();
        for (final Proc pr : procs) {
            procMenu.addView(menuButton(pr.name.toUpperCase(Locale.US), new Runnable() {
                public void run() { enterProcess(pr); }
            }));
        }
        procMenu.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.VISIBLE);
        gearBtn.setVisibility(View.GONE);
        menuOff = 0;
        big.setText("PROCESS");
        big.setTextSize(36);
        small.setText("");
        hint.setText("scroll = select, press = run\npress with nothing selected = back");
    }

    private void enterProcess(Proc pr) {
        steps = pr.steps;
        if (steps.isEmpty()) { say("That process has no steps yet."); return; }
        mode = PROCESS;
        stepIx = 0;
        rolls35 = 0; rolls120 = 0; sheets45 = 0;
        homeMenu.setVisibility(View.GONE);
        procMenu.setVisibility(View.GONE);
        noSel.requestFocusFromTouch();
        big.setTextSize(88);
        boolean needsFilm = false;
        for (Step s : steps) if (s.replenishes()) { needsFilm = true; break; }
        if (needsFilm) {
            askIx = 0;
            renderAsk();
            say("How many " + ASK_LABEL[0] + "? Scroll, then press.");
        } else {
            askIx = -1;
            announceStep();
        }
    }

    private int askCount() {
        return askIx == 0 ? rolls35 : askIx == 1 ? rolls120 : sheets45;
    }

    private void askAdjust(int d) {
        int v = Math.max(0, askCount() + d);
        if (askIx == 0) rolls35 = v; else if (askIx == 1) rolls120 = v; else sheets45 = v;
        sayNow(String.valueOf(v));
        renderAsk();
    }

    private void renderAsk() {
        big.setText(String.valueOf(askCount()));
        small.setText(ASK_LABEL[askIx]);
        hint.setText("scroll = count, press = next");
    }

    private void askConfirm() {
        // Confirming a zero says nothing and cuts any queued prompts, so
        // clicking through unused formats is instant.
        boolean zero = askCount() == 0;
        if (!zero) say(askCount() + " " + ASK_LABEL[askIx] + ".");
        if (askIx < 2) {
            askIx++;
            renderAsk();
            String prompt = "How many " + ASK_LABEL[askIx] + "?";
            if (zero) sayNow(prompt); else say(prompt);
        } else {
            askIx = -1;
            announceFlush = zero;
            announceStep();
        }
    }

    private boolean announceFlush = false;

    private void announceStep() {
        stepRunning = false;
        cur = steps.get(stepIx);
        big.setText(String.format(Locale.US, "%d:%02d", cur.sec / 60, cur.sec % 60));
        small.setText((stepIx + 1) + "/" + steps.size() + "  " + cur.name);
        hint.setText("press = start step");
        showRepl(cur, false);
        StringBuilder s = new StringBuilder();
        s.append("Step ").append(stepIx + 1).append(": ").append(cur.name).append(", ")
         .append(spoken(cur.sec / 60, cur.sec % 60)).append(". ");
        if (cur.agInit > 0) {
            s.append("Agitate ").append(cur.agInit).append(" seconds to start. ");
        }
        if (cur.agEvery > 0) {
            s.append(cur.agInit > 0 ? "Then agitate " : "Agitate ")
             .append(cur.agFor).append(" seconds every ")
             .append(spoken(cur.agEvery / 60, cur.agEvery % 60)).append(". ");
        }
        int ml = cur.r35 * rolls35 + cur.r120 * rolls120 + cur.r45 * sheets45;
        if (cur.replenishes() && ml > 0) {
            s.append("For this step, add ").append(ml)
             .append(" milliliters of replenisher to the working solution bottle,")
             .append(" then top back up to the original volume. ");
        }
        s.append("Press to start.");
        if (announceFlush) { announceFlush = false; sayNow(s.toString()); }
        else say(s.toString());
    }

    private final Runnable stepTick = new Runnable() {
        public void run() {
            if (mode != PROCESS || !stepRunning) return;
            long leftMs = stepEndAt - System.currentTimeMillis();
            if (leftMs <= 0) { finishStep(); return; }
            int left = (int) ((leftMs + 999) / 1000);
            int elapsed = cur.sec - left;
            big.setText(String.format(Locale.US, "%d:%02d", left / 60, left % 60));
            if (left != lastStepSec) {
                lastStepSec = left;
                final int ws = left;    // widget render + binder push off the UI thread
                snd.post(new Runnable() { public void run() {
                    TimerWidget.push(TimerActivity.this, ws);
                } });
            }
            // live AGITATE / REST banner
            if (cur.agInit > 0 || cur.agEvery > 0) {
                boolean ag = elapsed < cur.agInit;
                if (!ag && cur.agEvery > 0 && elapsed >= cur.agInit) {
                    int c2 = elapsed - cur.agInit;
                    ag = c2 >= cur.agEvery && (c2 % cur.agEvery) < cur.agFor;
                }
                agitView.setVisibility(View.VISIBLE);
                agitView.set(ag);
            }
            // agitation cues: initial agitation first, then cycles measured
            // from the end of the initial period. Cues fire 1 s early so the
            // spoken word lands on the boundary despite TTS latency.
            if (elapsed > 0 && leftMs % 1000 < 200) {
                int e1 = elapsed + 1;
                // "Agitate" fires 1 s early (TTS spin-up); "Rest" fires ON the
                // boundary plus half a second of breathing room, so short
                // agitation windows don't run the two lines together.
                if (cur.agInit > 0 && elapsed == cur.agInit) restCue();
                if (cur.agEvery > 0) {
                    if (e1 > cur.agInit) {
                        int inCycle = (e1 - cur.agInit) % cur.agEvery;
                        if (inCycle == 0 && left > cur.agFor) cue("Agitate.", 2);
                    }
                    // cycle rest only counts after the first recurring agitate -
                    // otherwise it fires agFor seconds into the initial rest
                    int c2 = elapsed - cur.agInit;
                    if (c2 > cur.agEvery && c2 % cur.agEvery == cur.agFor) restCue();
                }
            }
            if (leftMs <= 3500 && leftMs % 1000 < 200) beep();
            h.postDelayed(this, 200);
        }
    };

    private void finishStep() {
        stepRunning = false;
        stopTicking();
        replRow.setVisibility(View.GONE);
        agitView.setVisibility(View.GONE);
        longBeep();
        String repl = "";
        int ml = cur.r35 * rolls35 + cur.r120 * rolls120 + cur.r45 * sheets45;
        if (cur.replenishes() && ml > 0) {
            repl = " Replenish " + cur.name + " now: add " + ml
                 + " milliliters of replenisher to the stock bottle,"
                 + " then top up with tank solution back to the original stock volume.";
        }
        if (stepIx + 1 < steps.size()) {
            say(cur.name + " complete." + repl);
            String doneName = cur.name;     // announceStep repoints cur at the next step
            stepIx++;
            announceStep();
            // the replenish-now panel wins over the next step's preview
            if (ml > 0) showReplDone(ml, doneName);
        } else {
            say(cur.name + " complete." + repl + " Process finished.");
            // celebration screen: confetti + the final replenishment info
            // stays up until a button press returns to the menu
            procDone = true;
            big.setTextSize(56);
            big.setPadding(0, 70, 0, 0);
            big.setText("COMPLETE");
            small.setText("");
            hint.setText("press = menu");
            if (ml > 0) showReplDone(ml, cur.name);
            celebrate.setVisibility(View.VISIBLE);
        }
    }

    private void processPress(int presses) {
        if (procDone) { showHome(); return; }
        if (askIx >= 0) { askConfirm(); return; }
        if (stepRunning && presses < 2) {
            // a lone press mid-step is most likely accidental (wet hands,
            // fumbling in the dark) - skipping requires a double press
            click();
            return;
        }
        if (!stepRunning) {
            stepRunning = true;
            stepEndAt = System.currentTimeMillis() + cur.sec * 1000L;
            sceneEndAt = stepEndAt;
            hint.setText("double press = skip step");
            // flush - the step announcement can lag 10+ s behind the press
            boolean agitatesNow = cur.agInit > 0 || (cur.agEvery > 0 && cur.agFor > 0);
            if (!voiceOn) {
                cueBeeps(agitatesNow ? 2 : 1);      // muted: 2 beeps = start agitating
            } else if (cur.agInit > 0) {
                sayNow("Go. Agitate for " + cur.agInit + " seconds.");
            } else if (agitatesNow) {
                sayNow("Go. Agitate.");
            } else {
                sayNow("Go.");
            }
            showRepl(cur, false);
            startTicking();
            h.post(stepTick);
        } else {
            // running: treat press as "skip / done early"
            finishStep();
        }
    }

    // ---------------------------------------------------------------- step editor

    private Step stepFromJson(JSONObject o) throws Exception {
        return new Step(o.getString("n"), o.getInt("s"), o.optInt("ai"),
                o.getInt("e"), o.getInt("f"),
                o.optInt("r35"), o.optInt("r120"), o.optInt("r45"));
    }

    private void loadSteps() {
        procs.clear();
        android.content.SharedPreferences p = getSharedPreferences("safelight", MODE_PRIVATE);
        try {
            JSONArray pa = new JSONArray(p.getString("procs", "[]"));
            for (int i = 0; i < pa.length(); i++) {
                JSONObject po = pa.getJSONObject(i);
                Proc pr = new Proc(po.getString("name"));
                JSONArray a = po.getJSONArray("steps");
                for (int j = 0; j < a.length(); j++) pr.steps.add(stepFromJson(a.getJSONObject(j)));
                procs.add(pr);
            }
            // seed a stock C-41 on a fresh install (deletable and editable
            // like any other; the flag keeps a deleted one from coming back)
            if (procs.isEmpty() && !p.getBoolean("seeded", false)) {
                Proc c41 = new Proc("C-41 - Kodak");
                //                       name             sec  init every for  35  120  4x5
                c41.steps.add(new Step("Developer",       195,  30,  15,   2,  40,  40,  10));
                c41.steps.add(new Step("Bleach",          390,  30,  30,   2,  40,  40,  10));
                c41.steps.add(new Step("Wash",             90,  30,  15,   5,   0,   0,   0));
                c41.steps.add(new Step("Fixer",           390,  30,  30,   5,  40,  40,  10));
                c41.steps.add(new Step("Wash",            195,  30,  15,   5,   0,   0,   0));
                c41.steps.add(new Step("Final Rinse",      90,  30,   0,   0,  40,  40,  10));
                procs.add(c41);
                p.edit().putBoolean("seeded", true).apply();
                saveSteps();
            }
            // migrate the old single flat step list into a first process
            if (procs.isEmpty()) {
                JSONArray a = new JSONArray(p.getString("steps", "[]"));
                if (a.length() > 0) {
                    Proc pr = new Proc("Film");
                    for (int j = 0; j < a.length(); j++) pr.steps.add(stepFromJson(a.getJSONObject(j)));
                    procs.add(pr);
                    saveSteps();
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveSteps() {
        try {
            JSONArray pa = new JSONArray();
            for (Proc pr : procs) {
                JSONArray a = new JSONArray();
                for (Step s : pr.steps) {
                    JSONObject o = new JSONObject();
                    o.put("n", s.name); o.put("s", s.sec); o.put("ai", s.agInit);
                    o.put("e", s.agEvery); o.put("f", s.agFor);
                    o.put("r35", s.r35); o.put("r120", s.r120); o.put("r45", s.r45);
                    a.put(o);
                }
                JSONObject po = new JSONObject();
                po.put("name", pr.name); po.put("steps", a);
                pa.put(po);
            }
            getSharedPreferences("safelight", MODE_PRIVATE).edit()
                    .putString("procs", pa.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void editProcs() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 12, 24, 12);
        for (int i = 0; i < procs.size(); i++) {
            final Proc pr = procs.get(i);
            final int ix = i;
            TextView t = new TextView(this);
            t.setTextColor(0xFFFF2000);
            t.setTextSize(20);
            t.setPadding(0, 12, 0, 12);
            t.setText(pr.name + "  (" + pr.steps.size() + " steps)");
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { editSteps(pr); }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    procs.remove(ix); saveSteps(); editProcs(); return true;
                }
            });
            list.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        dialog()
                .setTitle("Processes (tap to edit, long-press to delete)")
                .setView(sv)
                .setPositiveButton("New process", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { newProcDialog(); }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void newProcDialog() {
        final EditText name = field(this, "name (e.g. B&W Film, RA-4 Print)");
        dialog()
                .setTitle("New process")
                .setView(name)
                .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (name.getText().length() == 0) return;
                        Proc pr = new Proc(name.getText().toString());
                        procs.add(pr);
                        saveSteps();
                        editSteps(pr);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editSteps(final Proc pr) {
        final ArrayList<Step> steps = pr.steps;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 12, 24, 12);
        for (int i = 0; i < steps.size(); i++) {
            final int ix = i;
            Step s = steps.get(i);
            TextView t = new TextView(this);
            t.setTextColor(0xFFCC1A00);
            t.setTextSize(18);
            t.setPadding(0, 10, 0, 10);
            t.setText((i + 1) + ". " + s.name + "  " + s.sec / 60 + ":" +
                    String.format(Locale.US, "%02d", s.sec % 60) +
                    (s.agInit > 0 ? "  (init " + s.agInit + "s)" : "") +
                    (s.agEvery > 0 ? "  (agitate " + s.agFor + "s / " + s.agEvery + "s)" : "") +
                    (s.replenishes() ? "  (repl " + s.r35 + "/" + s.r120 + "/" + s.r45 + "ml)" : ""));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { stepDialog(pr, ix); }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    steps.remove(ix); saveSteps(); editSteps(pr); return true;
                }
            });
            list.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        dialog()
                .setTitle(pr.name + " steps (tap to edit, long-press to delete)")
                .setView(sv)
                .setPositiveButton("Add step", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { stepDialog(pr, -1); }
                })
                .setNeutralButton("Rename", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { renameProcDialog(pr); }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void renameProcDialog(final Proc pr) {
        final EditText name = field(this, "process name");
        name.setText(pr.name);
        dialog()
                .setTitle("Rename process")
                .setView(name)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (name.getText().length() == 0) return;
                        pr.name = name.getText().toString();
                        saveSteps();
                        editSteps(pr);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Text cursor / selection handles in red instead of teal. */
    private android.graphics.drawable.Drawable tint(int resId) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFFFF2000);
        d.setSize(3, 0);
        return d;
    }

    /** AlertDialog.Builder wearing the app's palette. */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, R.style.DarkroomDialog);
    }

    private EditText numField(Context c, String hintText) {
        EditText e = field(c, hintText);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private EditText field(Context c, String hintText) {
        EditText e = new EditText(c);
        e.setHint(hintText);
        e.setTextColor(0xFFFF2000);
        e.setHintTextColor(0xFF801000);
        e.setTextSize(16);
        e.getBackground().setColorFilter(new android.graphics.PorterDuffColorFilter(
                0xFF801000, android.graphics.PorterDuff.Mode.SRC_IN));
        e.setHighlightColor(0x55FF2000);
        e.setTextCursorDrawable(tint(android.R.drawable.editbox_background));
        return e;
    }

    private void setNum(EditText e, int v) {
        if (v > 0) e.setText(String.valueOf(v));
    }

    /** Add (editIx == -1) or edit-in-place (editIx >= 0) a step. */
    private void stepDialog(final Proc pr, final int editIx) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 12, 24, 12);
        final EditText name = field(this, "name (e.g. Developer)");
        final EditText min = numField(this, "minutes");
        final EditText sec = numField(this, "seconds");
        final EditText agInit = numField(this, "initial agitation seconds (blank = none)");
        final EditText agEvery = numField(this, "then agitate every N seconds (blank = none)");
        final EditText agFor = numField(this, "agitate for N seconds");
        final EditText r35 = numField(this, "replenish ml per 35mm roll (blank = none)");
        final EditText r120 = numField(this, "replenish ml per 120 roll");
        final EditText r45 = numField(this, "replenish ml per 4x5 sheet");
        if (editIx >= 0) {
            Step s = pr.steps.get(editIx);
            name.setText(s.name);
            setNum(min, s.sec / 60); setNum(sec, s.sec % 60);
            setNum(agInit, s.agInit); setNum(agEvery, s.agEvery); setNum(agFor, s.agFor);
            setNum(r35, s.r35); setNum(r120, s.r120); setNum(r45, s.r45);
        }
        col.addView(name); col.addView(min); col.addView(sec);
        col.addView(agInit); col.addView(agEvery); col.addView(agFor);
        col.addView(r35); col.addView(r120); col.addView(r45);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        dialog()
                .setTitle(editIx >= 0 ? "Edit step" : "New step")
                .setView(sv)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int total = parse(min) * 60 + parse(sec);
                        if (name.getText().length() == 0 || total == 0) return;
                        Step s = new Step(name.getText().toString(), total,
                                parse(agInit), parse(agEvery), parse(agFor),
                                parse(r35), parse(r120), parse(r45));
                        if (editIx >= 0) pr.steps.set(editIx, s);
                        else pr.steps.add(s);
                        saveSteps();
                        editSteps(pr);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int parse(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception x) { return 0; }
    }

    // ---------------------------------------------------------------- voice

    private android.speech.SpeechRecognizer sr;
    private boolean listening = false;
    private boolean f1Held = false;
    private final Runnable holdStart = new Runnable() {
        public void run() { if (f1Held) startVoice(); }
    };

    // The recognizer plays its loud ready-chime on the notification/system
    // streams - mute those while the mic is open. Media (our ticks, beeps,
    // speech) is left alone.
    private void muteMedia(boolean m) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int dir = m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
        am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
    }

    /** Hold the button, speak while holding, release to finish. */
    private void startVoice() {
        if (listening) return;
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition is not available.");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        listening = true;
        burst = 0;
        h.removeCallbacks(burstEnd);
        if (sr == null) {
            sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new android.speech.RecognitionListener() {
                public void onResults(Bundle res) {
                    listening = false;
                    muteMedia(false);
                    ArrayList<String> m = res.getStringArrayList(
                            android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    voiceResult(m == null || m.isEmpty() ? "" : m.get(0));
                }
                public void onError(int e) {
                    listening = false;
                    muteMedia(false);
                    say("Didn't catch that. Hold and try again.");
                }
                public void onReadyForSpeech(Bundle b) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float r) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onPartialResults(Bundle b) {}
                public void onEvent(int t, Bundle b) {}
            });
        }
        final android.content.Intent i = new android.content.Intent(
                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        // Our short cue beep, then mute the media stream so the recognizer's
        // ready-chime is silent, then open the mic.
        beep();
        h.postDelayed(new Runnable() { public void run() {
            muteMedia(true);
            sr.startListening(i);
        } }, 180);
    }

    private void voiceResult(String text) {
        int[] d = parseDuration(text);
        if (d == null || (d[0] == 0 && d[1] == 0)) {
            say("Didn't catch that. Say something like three minutes thirty seconds.");
            return;
        }
        if (mode == HOME) {
            mode = TIMER;
            homeMenu.setVisibility(View.GONE);
            big.setTextSize(88);
        }
        if (mode != TIMER) return;
        h.removeCallbacks(timerTick);
        h.removeCallbacks(alertLoop);
        setMin = d[0]; setSec = d[1];
        tState = T_RUN;
        lastTickSec = -1;
        endAt = System.currentTimeMillis() + (setMin * 60 + setSec) * 1000L;
        sceneEndAt = endAt;
        say(spoken(setMin, setSec) + ", starting now.");
        startTicking();
        renderTimer();
        h.post(timerTick);
    }

    /** "3 minutes 45 seconds", "ninety seconds", "one and a half minutes" -> {min, sec}. */
    static int[] parseDuration(String text) {
        if (text == null) return null;
        String[] words = text.toLowerCase(Locale.US)
                .replace("-", " ").replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        java.util.HashMap<String, Integer> n = new java.util.HashMap<String, Integer>();
        String[] ones = {"zero","one","two","three","four","five","six","seven","eight",
                "nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen",
                "seventeen","eighteen","nineteen"};
        for (int i = 0; i < ones.length; i++) n.put(ones[i], i);
        String[] tens = {"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        for (int i = 0; i < tens.length; i++) n.put(tens[i], 20 + i * 10);
        n.put("a", 1); n.put("an", 1);
        int min = 0, sec = 0, buf = 0;
        boolean half = false, any = false;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (w.matches("\\d+")) { buf += Integer.parseInt(w); any = true; }
            else if (n.containsKey(w)) { buf += n.get(w); any = true; }
            else if (w.equals("half")) { half = true; }
            else if (w.startsWith("minute") || w.equals("min") || w.equals("mins")) {
                min += buf; if (half) sec += 30;
                buf = 0; half = false; any = true;
            } else if (w.startsWith("second") || w.equals("sec") || w.equals("secs")) {
                sec += buf; buf = 0; half = false; any = true;
            }
        }
        if (buf > 0) min += buf;        // "five" alone = five minutes
        if (!any) return null;
        min += sec / 60; sec %= 60;
        return new int[] {min, sec};
    }

    // ---------------------------------------------------------------- input

    // Presses commit only after an 800 ms burst window closes, so the first
    // presses of a 5-burst don't fire confirms or resets along the way.
    private int burst = 0;
    private final Runnable burstEnd = new Runnable() {
        public void run() {
            int n = burst;
            burst = 0;
            if (n >= 5) {
                setBlackout(!blackout);
            } else if (mode == TIMER) timerPress(n);
            else if (mode == PROCESS) processPress(n);
            else if (mode == HOME) {
                // Activate the focused menu button. After a touch, nothing is
                // focused - first press highlights the first item instead of
                // being swallowed.
                View f = getCurrentFocus();
                if (f instanceof Button) f.performClick();
                else if (setupMenu.getVisibility() == View.VISIBLE) showHome();
                else if (procMenu.getVisibility() == View.VISIBLE) showHome();
                else if (homeMenu.getChildCount() > 0) {
                    click();
                    homeMenu.getChildAt(0).requestFocusFromTouch();
                }
            }
        }
    };

    private void buttonPress() {
        burst++;
        h.removeCallbacks(burstEnd);
        h.postDelayed(burstEnd, 800);
    }

    @Override public boolean onKeyDown(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_F1:               // the remapped side button
                if (ev.getRepeatCount() == 0) {
                    // Push-to-talk: after 500 ms of hold the mic opens; speak
                    // while holding. A quick tap is a confirm on key-up.
                    f1Held = true;
                    h.postDelayed(holdStart, 500);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:      // headset button, if any
            case KeyEvent.KEYCODE_VOLUME_UP:
                buttonPress();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:          // scroll wheel detents
                if (mode == TIMER) { timerScroll(1); return true; }
                if (mode == PROCESS && askIx >= 0) { click(); askAdjust(1); return true; }
                menuScroll(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mode == TIMER) { timerScroll(-1); return true; }
                if (mode == PROCESS && askIx >= 0) { click(); askAdjust(-1); return true; }
                menuScroll(1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (blackout) return true;          // no accidental exits in the dark
                if (mode != HOME) { quietExit(); return true; }
                if (procMenu.getVisibility() == View.VISIBLE
                        || setupMenu.getVisibility() == View.VISIBLE) { showHome(); return true; }
                break;
        }
        return super.onKeyDown(code, ev);
    }

    // Manual menu selection: scrolling one detent past either end deselects
    // (so the side button means "back"); scrolling back in re-selects.
    private int menuOff = 0;    // -1 = scrolled off the top, +1 = off the bottom

    private void menuScroll(int dir) {
        click();
        LinearLayout menu = setupMenu.getVisibility() == View.VISIBLE ? setupMenu
                : procMenu.getVisibility() == View.VISIBLE ? procMenu : homeMenu;
        int n = menu.getChildCount();
        if (n == 0) return;
        View f = getCurrentFocus();
        int idx = f == null ? -1 : menu.indexOfChild(f);
        if (idx >= 0) {
            int next = idx + dir;
            if (next < 0 || next >= n) {
                noSel.requestFocusFromTouch();
                menuOff = dir;
            } else {
                menu.getChildAt(next).requestFocusFromTouch();
            }
        } else if (menuOff == -dir) {
            // coming back from off the end re-selects the edge item
            menu.getChildAt(menuOff == 1 ? n - 1 : 0).requestFocusFromTouch();
            menuOff = 0;
        } else if (menuOff == 0) {
            // nothing selected yet (fresh screen or tap-off): enter at the edge
            menu.getChildAt(dir == 1 ? 0 : n - 1).requestFocusFromTouch();
        }
    }

    /** Leave a mode: cut any speech mid-sentence and drop pending work. */
    private void quietExit() {
        if (tts != null) tts.stop();
        h.removeCallbacksAndMessages(null);
        showHome();
    }

    @Override public void onBackPressed() {
        if (blackout) return;                       // no accidental exits in the dark
        if (mode != HOME) { quietExit(); return; }
        if (procMenu.getVisibility() == View.VISIBLE || setupMenu.getVisibility() == View.VISIBLE) {
            showHome(); return;
        }
        super.onBackPressed();
    }

    @Override public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_F1) {
            f1Held = false;
            if (listening) {
                if (sr != null) sr.stopListening();  // release = done speaking
            } else {
                h.removeCallbacks(holdStart);
                buttonPress();
            }
            return true;
        }
        return super.onKeyUp(code, ev);
    }
}
