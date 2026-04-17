package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;

/**
 * Central font factory. Generates antialiased TrueType bitmap fonts via libGDX
 * FreeType.
 * Dispose via dispose() when the application exits.
 */
public class FontManager {

    public final BitmapFont uiFont; // General HUD text, 14px
    public final BitmapFont labelFont; // Body labels, 12px
    public final BitmapFont titleFont; // Section headers, 16px

    public FontManager() {
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(
                Gdx.files.internal("fonts/JetBrainsMono-Regular.ttf"));

        uiFont = generate(generator, 14, Color.WHITE);
        labelFont = generate(generator, 12, new Color(0.85f, 0.85f, 0.85f, 1f));
        titleFont = generate(generator, 16, Color.WHITE);

        generator.dispose();
    }

    private BitmapFont generate(FreeTypeFontGenerator gen, int size, Color color) {
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.size = size;
        p.color = color;
        p.borderWidth = 0;
        p.shadowOffsetX = 1;
        p.shadowOffsetY = -1;
        p.shadowColor = new Color(0, 0, 0, 0.6f);
        p.minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
        p.magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
        p.genMipMaps = false;
        return gen.generateFont(p);
    }

    public void dispose() {
        uiFont.dispose();
        labelFont.dispose();
        titleFont.dispose();
    }
}
