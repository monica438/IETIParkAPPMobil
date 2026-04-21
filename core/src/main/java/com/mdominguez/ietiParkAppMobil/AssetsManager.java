package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.mdominguez.ietiParkAppMobil.UI.GameData;
import com.mdominguez.ietiParkAppMobil.UI.MediaAsset;

import java.util.HashMap;
import java.util.Map;

public class AssetsManager implements Disposable {
    private final Map<String, Texture> textures = new HashMap<>();
    private GameData gameData;

    public void load() {
        // 1. Leer y parsear el JSON
        String jsonStr = Gdx.files.internal("game_data.json").readString();
        com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json();
        gameData = json.fromJson(GameData.class, jsonStr);

        // 2. Cargar solo los assets que aparecen en mediaAssets
        for (MediaAsset asset : gameData.getMediaAssets()) {
            loadTexture(asset.getName(), asset.getFileName());
        }
    }

    private void loadTexture(String key, String path) {
        try {
            textures.put(key, new Texture(Gdx.files.internal(path)));
        } catch (Exception e) {
            Gdx.app.error("Assets", "No se pudo cargar: " + path);
        }
    }

    public Texture get(String key) {
        return textures.get(key);
    }

    public GameData getGameData() {
        return gameData;
    }

    @Override
    public void dispose() {
        for (Texture t : textures.values()) t.dispose();
    }
}
