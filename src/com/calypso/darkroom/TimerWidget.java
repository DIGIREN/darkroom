package com.calypso.darkroom;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Homescreen wall clock (see WallClock). Tapping it opens the timer. While a
 * timer runs, TimerActivity pushes per-second hand updates via push().
 */
public class TimerWidget extends AppWidgetProvider {

    static void push(Context c, int remainSec) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, TimerWidget.class));
        if (ids.length == 0) return;
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_timer);
        rv.setImageViewBitmap(R.id.widget_clock, WallClock.render(remainSec));
        Intent i = new Intent(c, TimerActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rv.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, rv);
    }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        push(c, -1);
    }
}
