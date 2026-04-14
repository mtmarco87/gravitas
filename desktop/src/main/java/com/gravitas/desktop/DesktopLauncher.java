package com.gravitas.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.gravitas.GravitasGame;

/**
 * Desktop entry point — configures the LWJGL3 window and launches GravitasGame.
 */
public class DesktopLauncher {

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();

        config.setTitle("Gravitas — Orbital Simulation Sandbox");
        config.setWindowedMode(1600, 900);
        config.setResizable(true);
        config.useVsync(true);
        config.setForegroundFPS(60);
        config.setWindowIcon("textures/icon.png"); // placeholder — won't crash if missing

        new Lwjgl3Application(new GravitasGame(), config);
    }
}
