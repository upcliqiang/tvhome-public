package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: what ScreensaverStyle picks — the thin clock on the ambient glow by default, or the date, a quote
 * or the wallpaper alone. No network, no images of its own: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ScreensaverScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        Config config = Config.load(this);
        setContentView(R.layout.screensaver_scene);
        scene = new ScreensaverScene(getWindow().getDecorView(), ScreensaverStyle.current(this, config));
        // After the scene inflated its content: the clocks inside it must see the chosen format too.
        ClockFormat.apply(getWindow().getDecorView(), config.clockFormat);
        scene.prepare(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
