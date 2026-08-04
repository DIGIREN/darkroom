package com.calypso.darkroom;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Renders the homescreen widget: a wall clock in the flat-vector style and
 * palette of the darkroom wallpaper, hanging from a nail. While a timer or
 * process step runs, the hands show the remaining time (minute hand walks the
 * dial once per hour of remaining time, second hand once per minute); idle,
 * both hands rest at twelve. Centered, no cord - it reads as a small icon.
 */
final class WallClock {

    // palette lifted from DarkroomWallpaper
    private static final int CASE_DK  = 0xFF2A1416;   // timer body
    private static final int CASE     = 0xFF35191A;
    private static final int CASE_EDGE= 0xFF5A2B27;
    private static final int FACE     = 0xFFEDE6D8;   // glow-face cream (PRINT)
    private static final int FACE_DIM = 0xFFD9CDB9;
    private static final int TICK     = 0xFF3B2622;   // ink marks on the face
    private static final int TICK_HI  = 0xFFDE8F8B;
    private static final int HAND     = 0xFFC33A2E;
    private static final int HAND_HOT = 0xFFE85A3C;
    private static final int PIN      = 0xFF140A0C;

    private WallClock() {}

    /** remainSec >= 0 shows that countdown on the hands; -1 = idle (12:00). */
    static Bitmap render(int remainSec) {
        int w = 400, h = 400;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        float cx = w / 2f;
        // square Gralab-style case sitting on little feet
        float caseL = 60, caseR = w - 60, caseT = 64, caseB = h - 84;
        float r = (caseR - caseL) / 2f - 26;
        float cy = (caseT + caseB) / 2f;

        p.setStyle(Paint.Style.FILL);
        // feet (rubber pads)
        p.setColor(PIN);
        cv.drawRect(caseL + 14, caseB, caseL + 52, caseB + 18, p);
        cv.drawRect(caseR - 52, caseB, caseR - 14, caseB + 18, p);
        // body: edge, then face-plate front
        p.setColor(CASE_EDGE);
        cv.drawRoundRect(new android.graphics.RectF(caseL - 8, caseT - 8, caseR + 8, caseB + 8), 22, 22, p);
        p.setColor(CASE);
        cv.drawRoundRect(new android.graphics.RectF(caseL, caseT, caseR, caseB), 16, 16, p);
        p.setColor(CASE_DK);
        cv.drawRoundRect(new android.graphics.RectF(caseL + 10, caseT + 10, caseR - 10, caseB - 10), 12, 12, p);

        // glow-in-the-dark dial
        p.setColor(FACE_DIM);
        cv.drawCircle(cx, cy, r + 5, p);
        p.setColor(FACE);
        cv.drawCircle(cx, cy, r, p);

        // 60-minute dial marks: majors every 5
        for (int i = 0; i < 60; i++) {
            boolean major = i % 5 == 0;
            double a = Math.toRadians(i * 6 - 90);
            p.setColor(TICK);
            p.setStrokeWidth(major ? 7f : 3f);
            p.setStyle(Paint.Style.STROKE);
            float r1 = major ? r - 22 : r - 12;
            cv.drawLine((float) (cx + Math.cos(a) * r1), (float) (cy + Math.sin(a) * r1),
                        (float) (cx + Math.cos(a) * (r - 6)), (float) (cy + Math.sin(a) * (r - 6)), p);
        }

        // twin toggle switches under the dial, gralab-style
        p.setStyle(Paint.Style.FILL);
        p.setColor(TICK_HI);
        cv.drawCircle(caseL + 42, caseB - 26, 9, p);
        cv.drawCircle(caseR - 42, caseB - 26, 9, p);

        int min = remainSec < 0 ? 0 : (remainSec / 60) % 60;
        int sec = remainSec < 0 ? 0 : remainSec % 60;

        // minute hand (remaining minutes), stubby and flat
        double ma = Math.toRadians((min + sec / 60.0) * 6 - 90);
        p.setColor(HAND);
        p.setStrokeWidth(11f);
        p.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(cx, cy, (float) (cx + Math.cos(ma) * r * 0.55f),
                    (float) (cy + Math.sin(ma) * r * 0.55f), p);

        // second hand (remaining seconds), thin and hot
        double sa = Math.toRadians(sec * 6 - 90);
        p.setColor(HAND_HOT);
        p.setStrokeWidth(5f);
        cv.drawLine((float) (cx - Math.cos(sa) * r * 0.14f), (float) (cy - Math.sin(sa) * r * 0.14f),
                    (float) (cx + Math.cos(sa) * r * 0.8f), (float) (cy + Math.sin(sa) * r * 0.8f), p);

        // center pin, rabbit-cream
        p.setColor(PIN);
        cv.drawCircle(cx, cy, 9, p);
        p.setColor(FACE);
        cv.drawCircle(cx, cy, 4, p);

        return bm;
    }
}
