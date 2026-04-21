package com.mdominguez.ietiParkAppMobil.UI;

import com.badlogic.gdx.utils.Array;

public class LevelData {
    private String name;
    private Array<SpriteData> sprites;
    private Array<LayerData> layers;
    private int viewportWidth;
    private int viewportHeight;


    public String getName() {
        return name;
    }
    public Array<SpriteData> getSprites() {
        return sprites;
    }
    public Array<LayerData> getLayers() {
        return layers;
    }
    public int getViewportWidth() {
        return viewportWidth;
    }
    public int getViewportHeight() {
        return viewportHeight;
    }
}
