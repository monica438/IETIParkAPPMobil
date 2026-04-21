package com.mdominguez.ietiParkAppMobil.UI;

import com.badlogic.gdx.graphics.Texture;

public class SpriteInstance {
    private final Texture texture;
    private final float x, y, width, height;

    public SpriteInstance(Texture texture, float x, float y, float width, float height) {
        this.texture = texture;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public Texture getTexture() {
        return texture;
    }
    public float getX() {
        return x;
    }
    public float getY() {
        return y;
    }
    public float getWidth() {
        return width;
    }
    public float getHeight() {
        return height;
    }
}
