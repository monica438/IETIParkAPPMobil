package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import java.util.List;

public class MenuScreen implements Screen, WebSocketClient.PlayerListListener{
    private static final float WORLD_W = 320f;
    private static final float WORLD_H = 180f;

    private final Main game;

    private Stage stage;
    private Skin skin;

    // Widgets
    private TextField nicknameField;
    private Label statusLabel;
    private Table playersTable;
    private Label playersTitle;

    // Fondo simple (usamos BG_3 como fondo de menú)
    private ShapeRenderer shapeRenderer;

    public MenuScreen(Main game) {
        this.game = game;
    }

    @Override
    public void show() {
        // Stage con viewport de pantalla completa
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);

        // Skin por defecto de LibGDX (debes tener uiskin.json en assets/,
        // o reemplaza por tu propio skin)
        skin = new Skin(Gdx.files.internal("uiskin.json"));

        shapeRenderer = new ShapeRenderer();

        buildUI();

        // Registramos el listener para recibir actualizaciones de la lista
        game.getWsClient().setPlayerListListener(this);

        // Pedimos la lista actual al entrar
        // (el servidor puede responder con PLAYER_LIST al recibir cualquier JOIN
        // o podemos enviar un mensaje específico; ver servidor actualizado)
        refreshPlayerList();
    }


    // Construcción de la UI

    private void buildUI() {
        Table root = new Table();
        root.setFillParent(true);
        root.pad(20f);

        // Título
        Label title = new Label("IETIPark 2", skin, "default");
        title.setFontScale(2f);
        root.add(title).padBottom(24f).row();

        // Estado de conexión
        statusLabel = new Label("Conectando...", skin);
        root.add(statusLabel).padBottom(16f).row();

        // Campo nickname
        Label nickLabel = new Label("Nickname:", skin);
        root.add(nickLabel).left().padBottom(4f).row();

        nicknameField = new TextField("", skin);
        nicknameField.setMessageText("Introduce tu nick...");
        nicknameField.setMaxLength(20);
        root.add(nicknameField).width(200f).padBottom(16f).row();

        // Botón PLAY
        TextButton playButton = new TextButton("PLAY", skin);
        playButton.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                onPlayClicked();
            }
        });
        root.add(playButton).width(120f).height(40f).padBottom(30f).row();

        // --- Lista de jugadores ---
        playersTitle = new Label("Jugadores en sala: 0", skin);
        root.add(playersTitle).left().padBottom(6f).row();

        playersTable = new Table(skin);
        playersTable.left();
        root.add(playersTable).left().row();

        stage.addActor(root);

        // Actualizar estado de conexión
        updateConnectionStatus();
    }


    // Lógica

    private void onPlayClicked() {
        String nick = nicknameField.getText().trim();
        if (nick.isEmpty()) {
            statusLabel.setText("¡Introduce un nickname!");
            return;
        }

        // Enviar JOIN → el servidor actualiza la lista y broadcast a todos
        game.getWsClient().sendJoin(nick);

        // Ir directamente a GameScreen sin esperar a nadie
        game.setScreen(new GameScreen(game, nick));
    }

    private void refreshPlayerList() {
        // Podemos enviar un mensaje GET_PLAYERS para pedir la lista
        // (esto depende de la implementación del servidor)
        // Por ahora solo mostramos lo que ya tiene el cliente
        updatePlayerListUI(game.getWsClient().getActivePlayers());
    }

    /** Llamado desde el WebSocketClient en el hilo de render. */
    @Override
    public void onPlayerListUpdated(List<String> players) {
        updatePlayerListUI(players);
    }

    private void updatePlayerListUI(List<String> players) {
        if (playersTable == null) return;

        playersTable.clear();
        playersTitle.setText("Jugadores en sala: " + players.size());

        for (String p : players) {
            playersTable.add(new Label("• " + p, skin)).left().padBottom(2f).row();
        }

        if (players.isEmpty()) {
            playersTable.add(new Label("(ninguno todavía)", skin)).left().row();
        }
    }

    private void updateConnectionStatus() {
        if (game.getWsClient().isConnected()) {
            statusLabel.setText("Conectado al servidor ✓");
        } else {
            statusLabel.setText("Sin conexión al servidor");
        }
    }


    // Screen lifecycle

    @Override
    public void render(float delta) {
        // Fondo azul oscuro simple
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.2f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Actualizar estado de conexión cada frame (barato)
        updateConnectionStatus();

        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void hide() {
        // Desregistrar listener para no recibir callbacks en pantalla oculta
        game.getWsClient().setPlayerListListener(null);
    }

    @Override
    public void dispose() {
        stage.dispose();
        skin.dispose();
        shapeRenderer.dispose();
    }
}
