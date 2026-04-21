package com.mdominguez.ietiParkAppMobil.UI;

import com.badlogic.gdx.utils.Array;

public class GameData {
    private String name;
    private Array<LevelData> levels;
    private Array<MediaAsset> mediaAssets;


    public String getName() {
        return name;
    }
    public Array<LevelData> getLevels() {
        return levels;
    }
    public Array<MediaAsset> getMediaAssets() {
        return mediaAssets;
    }
}
