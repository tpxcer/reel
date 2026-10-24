package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Random;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 *
 * With trending_posters' pictures saved, it shows those instead: one every POSTER_MS, cross-fading and slowly
 * zooming, with the title, synopsis and TMDB's credit, and a smaller clock that drifts only around the top corner.
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    private static final long POSTER_MS = 20_000L, FADE_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private final float density;
    /** The trending pictures and their decoder; empty and null for the plain clock. */
    private final List<TrendingPosters.Item> posters;
    private final HeroPosters decoder;
    private ImageView[] posterViews;
    private final Animator[] zooms = new Animator[2];
    private View caption;
    private TextView title, overview;
    private int posterIndex, front;
    private boolean stopped;

    private final Runnable posterTick = new Runnable() {
        @Override
        public void run() {
            showNextPoster();
            handler.postDelayed(this, POSTER_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            if (decoder == null) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        View ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        DisplayMetrics screen = root.getResources().getDisplayMetrics();
        density = screen.density;
        posters = TrendingPosters.saved(Config.dir(root.getContext()));
        if (posters.isEmpty()) {
            decoder = null;
            return;
        }
        decoder = new HeroPosters(null, false, screen.widthPixels, screen.heightPixels, false);
        ambient.setVisibility(View.GONE);
        posterViews = new ImageView[]{root.findViewById(R.id.dreamPosterA), root.findViewById(R.id.dreamPosterB)};
        View scrim = root.findViewById(R.id.dreamScrim);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x59000000, 0x00000000, 0x40000000}));
        scrim.setVisibility(View.VISIBLE);
        caption = root.findViewById(R.id.dreamCaption);
        caption.setVisibility(View.VISIBLE);
        caption.setAlpha(0f);
        title = root.findViewById(R.id.dreamTitle);
        overview = root.findViewById(R.id.dreamOverview);
        ((TextView) root.findViewById(R.id.dreamClock)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f);
        ((TextView) root.findViewById(R.id.dreamAmPm)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        ((TextView) root.findViewById(R.id.dreamDate)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        posterIndex = random.nextInt(posters.size());
    }

    /** True when it shows the trending pictures, which want the screen at its normal brightness. */
    boolean showsPosters() {
        return decoder != null;
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (decoder != null) {
            handler.removeCallbacks(posterTick);
            handler.post(posterTick);
        }
    }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
        if (decoder == null) return;
        for (int i = 0; i < 2; i++) {
            posterViews[i].animate().cancel();
            if (zooms[i] != null) zooms[i].cancel();
        }
        caption.animate().cancel();
        decoder.close();
    }

    /** The next picture fades in over the last one and zooms in slowly while it stays; its caption follows. */
    private void showNextPoster() {
        TrendingPosters.Item item = posters.get(posterIndex);
        posterIndex = (posterIndex + 1) % posters.size();
        decoder.load(new HeroPosters.Source(item.file, item.title, item.overview), picture -> {
            if (stopped || picture == null) return;
            int in = 1 - front;
            ImageView incoming = posterViews[in], outgoing = posterViews[front];
            front = in;
            if (zooms[in] != null) zooms[in].cancel();
            incoming.animate().cancel();
            outgoing.animate().cancel();
            incoming.setImageBitmap(picture);
            incoming.setScaleX(1f);
            incoming.setScaleY(1f);
            incoming.animate().alpha(1f).setDuration(FADE_MS).start();
            outgoing.animate().alpha(0f).setDuration(FADE_MS).start();
            ObjectAnimator zoom = ObjectAnimator.ofPropertyValuesHolder(incoming,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f));
            zoom.setDuration(POSTER_MS + FADE_MS);
            zoom.setInterpolator(new LinearInterpolator());
            zoom.start();
            zooms[in] = zoom;
            caption.animate().cancel();
            caption.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
                title.setText(item.title);
                overview.setText(item.overview);
                overview.setVisibility(item.overview.isEmpty() ? View.GONE : View.VISIBLE);
                caption.animate().alpha(1f).setDuration(FADE_MS / 2).start();
            }).start();
        });
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        if (decoder != null) {
            // Over the pictures: the top corner, away from the caption, a few dp further each minute.
            content.setX((64 + 32 * random.nextFloat()) * density);
            content.setY((48 + 24 * random.nextFloat()) * density);
            return;
        }
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
