package com.gravitas.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;

/**
 * Sequential music player — plays a list of OGG tracks in order, looping
 * back to the first when the last finishes. Supports fade-in on start.
 */
public class MusicPlayer {

    private static final float FADE_IN_DURATION = 4.0f;
    private static final float DEFAULT_VOLUME = 0.35f;

    private final String[] trackPaths;
    private Music current;
    private int currentIndex;
    private float targetVolume = DEFAULT_VOLUME;

    // fade-in state
    private boolean fadingIn;
    private float fadeTimer;

    public MusicPlayer(String... trackPaths) {
        this.trackPaths = trackPaths;
    }

    /** Start playing from the first track with a gentle fade-in. */
    public void play() {
        if (trackPaths.length == 0)
            return;
        currentIndex = 0;
        startTrack(currentIndex, true);
    }

    /** Call every frame to handle fade-in and track advancement. */
    public void update(float dt) {
        if (current == null)
            return;

        // Fade in
        if (fadingIn) {
            fadeTimer += dt;
            float progress = Math.min(fadeTimer / FADE_IN_DURATION, 1.0f);
            current.setVolume(targetVolume * progress);
            if (progress >= 1.0f) {
                fadingIn = false;
            }
        }

        // When track ends, advance to next
        if (!current.isPlaying() && !fadingIn) {
            currentIndex = (currentIndex + 1) % trackPaths.length;
            startTrack(currentIndex, false);
        }
    }

    public void setVolume(float volume) {
        this.targetVolume = Math.max(0f, Math.min(1f, volume));
        if (current != null && !fadingIn) {
            current.setVolume(targetVolume);
        }
    }

    public void pause() {
        if (current != null)
            current.pause();
    }

    public void resume() {
        if (current != null)
            current.play();
    }

    public void dispose() {
        if (current != null) {
            current.stop();
            current.dispose();
            current = null;
        }
    }

    private void startTrack(int index, boolean fadeIn) {
        if (current != null) {
            current.stop();
            current.dispose();
        }
        current = Gdx.audio.newMusic(Gdx.files.internal(trackPaths[index]));
        if (fadeIn) {
            current.setVolume(0f);
            fadingIn = true;
            fadeTimer = 0f;
        } else {
            current.setVolume(targetVolume);
            fadingIn = false;
        }
        current.play();
        Gdx.app.log("MusicPlayer", "Now playing: " + trackPaths[index]);
    }
}
