package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.mdominguez.ietiParkAppMobil.UI.LevelData;
import com.mdominguez.ietiParkAppMobil.UI.SpriteData;
import com.mdominguez.ietiParkAppMobil.UI.SpriteInstance;

import java.util.ArrayList;
import java.util.List;

public class GameScreen implements Screen {
    // Dimensiones del mundo virtual (game_data.json: viewportWidth/Height)
    private static final float WORLD_W = 320f;
    private static final float WORLD_H = 180f;

    // Posiciones de los gatos según game_data.json (x, y)
    // NOTA: en LibGDX Y crece hacia arriba; el JSON usa Y hacia abajo con
    // origen en la esquina superior-izquierda y altura de nivel 400px.
    // Convertimos: gdxY = LEVEL_H - jsonY - spriteH
    private static final float LEVEL_H = 400f;
    private static final float SPRITE_SIZE = 16f;

    private static final float[][] CAT_POSITIONS = {
        { 51f,  391f },  // cat1
        { 68f,  391f },  // cat2
        { 84f,  391f },  // cat3
        {101f,  391f },  // cat4
        { 34f,  391f },  // cat5
        {117f,  391f },  // cat6
        {133f,  392f },  // cat7
        {149f,  392f },  // cat8
    };

    private final Main game;
    private final String nickname;

    private OrthographicCamera camera;
    private Viewport viewport;
    private SpriteBatch batch;

    // Texturas de fondo
    private Texture bg3, bg2, bg1;

    // Texturas de los gatos (idle)
    private Texture[] catTextures;

    private List<SpriteInstance> spritesToRender;

    // Posición horizontal de la cámara (para parallax futuro)
    private float cameraX = 0f;

    public GameScreen(Main game, String nickname) {
        this.game = game;
        this.nickname = nickname;
    }

    @Override
    public void show() {
        spritesToRender = new ArrayList<>();

        // Obtener los sprites del primer nivel
        LevelData level = game.getAssets().getGameData().getLevels().get(0);

        for (SpriteData sprite : level.getSprites()) {
            Texture tex = game.getAssets().get(sprite.getType()); // "cat1", "cat2"...
            if (tex == null) continue;

            float scale = WORLD_H / LEVEL_H;
            float gdxX  = sprite.getX() * scale;
            float gdxY  = (LEVEL_H - sprite.getY() - sprite.getHeight()) * scale;
            float w     = sprite.getWidth()  * scale;
            float h     = sprite.getHeight() * scale;

            // guardar en una lista para renderizar
            spritesToRender.add(new SpriteInstance(tex, gdxX, gdxY, w, h));
        }
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.863f, 0.863f, 0.882f, 1f); // #DCDCE1 del JSON
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camera.update();
        batch.setProjectionMatrix(camera.combined);

        batch.begin();

        // Capas de fondo con parallax
        drawBackground(bg3, 0.10f);   // background3 – más lejano
        drawBackground(bg2, 0.25f);   // background2
        drawBackground(bg1, 0.45f);   // background1 – más cercano

        // Sprites de los gatos
        for (SpriteInstance s : spritesToRender) {
            batch.draw(s.getTexture(), s.getX(), s.getY(), s.getWidth(), s.getHeight());
        }

        batch.end();
    }

    private void drawBackground(Texture tex, float factor) {
        if (tex == null) return;

        // Offset horizontal para parallax
        float offsetX = cameraX * factor;

        // Calculamos cuántas repeticiones necesitamos para cubrir la pantalla
        float ratio    = WORLD_H / tex.getHeight();       // escala para llenar la altura
        float texW     = tex.getWidth()  * ratio;
        float texH     = tex.getHeight() * ratio;

        // Posición de inicio (puede ser negativa por el scroll)
        float startX = -(offsetX % texW);
        if (startX > 0) startX -= texW;

        // Dibujar suficientes repeticiones para cubrir el ancho del mundo
        float x = startX;
        while (x < WORLD_W + texW) {
            batch.draw(tex, x, 0, texW, texH);
            x += texW;
        }
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height);
        camera.position.set(WORLD_W / 2f, WORLD_H / 2f, 0);
        camera.update();
    }

    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        batch.dispose();
        // Los assets son gestionados por AssetsManager
    }
}
