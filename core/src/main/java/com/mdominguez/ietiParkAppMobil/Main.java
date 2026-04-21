package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class Main extends Game {
    private SpriteBatch batch;
    private AssetsManager assets;
    private WebSocketClient wsClient;

    public SpriteBatch getBatch() {
        return batch;
    }
    public AssetsManager getAssets() {
        return assets;
    }
    public WebSocketClient getWsClient() {
        return wsClient;
    }


    @Override
    public void create() {
        batch = new SpriteBatch();
        assets = new AssetsManager();
        assets.load();
        wsClient = new WebSocketClient();
        wsClient.connect();
        setScreen(new MenuScreen(this));
    }

    @Override
    public void render() {
        super.render();
    }

    @Override
    public void dispose() {
        batch.dispose();
        assets.dispose();
        wsClient.close();
    }
}
