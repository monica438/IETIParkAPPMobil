package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.StretchViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

public class PlayScreen extends ScreenAdapter {

    // Constantes de renderizado y física
    private static final float DEFAULT_ANIMATION_FPS = 8f;
    private static final float FIXED_STEP_SECONDS = 1f / 60f;
    private static final float MAX_FRAME_SECONDS = 0.25f;
    private static final float CAMERA_DEAD_ZONE_FRACTION_X = 0.22f;
    private static final float CAMERA_DEAD_ZONE_FRACTION_Y = 0.18f;
    private static final float CAMERA_FOLLOW_SMOOTHNESS = 10f;
    private static final float HUD_MARGIN = 14f;
    private static final float HUD_BUTTON_HEIGHT = 48f;
    private static final Color HUD_TEXT_COLOR = Color.WHITE;
    private static final Color END_OVERLAY_DIM = Color.valueOf("000000A8");

    // Constantes del joystick
    private static final float JOYSTICK_BASE_RADIUS = 80f;
    private static final float JOYSTICK_THUMB_RADIUS = 36f;
    private static final float JOYSTICK_DEAD_ZONE = 0.25f;
    private static final float JOYSTICK_MARGIN = 110f;
    private static final float JUMP_BUTTON_RADIUS = 54f;
    private static final float JUMP_MARGIN = 110f;
    private static final Color JOYSTICK_BASE_COLOR = Color.valueOf("35FF7450");
    private static final Color JOYSTICK_THUMB_COLOR = Color.valueOf("35FF74CC");
    private static final Color JUMP_BUTTON_COLOR = Color.valueOf("FFD700CC");
    private static final Color JUMP_BUTTON_DIM = Color.valueOf("FFD70050");

    // Intervalo mínimo entre envíos WebSocket
    private static final long WS_SEND_INTERVAL_MS = 100;
    private static final float POSITION_SEND_INTERVAL = 0.1f; // 10 veces por segundo

    private final GameApp game;
    private final int levelIndex;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final Viewport viewport;
    private final OrthographicCamera hudCamera = new OrthographicCamera();
    private final Viewport hudViewport = new ScreenViewport(hudCamera);
    private final LevelRenderer levelRenderer = new LevelRenderer();
    private final DebugOverlay debugOverlay = new DebugOverlay();
    private final Array<LevelRenderer.SpriteRuntimeState> spriteRuntimeStates = new Array<>();
    private final Array<RuntimeTransform> layerRuntimeStates = new Array<>();
    private final Array<RuntimeTransform> zoneRuntimeStates = new Array<>();
    private final Array<RuntimeTransform> zonePreviousStates = new Array<>();
    private final Array<PathBindingRuntime> pathBindingRuntimes = new Array<>();
    private final FloatArray spriteAnimElapsed = new FloatArray();
    private final IntArray spriteTotalFrames = new IntArray();
    private String[] spriteCacheKey = new String[0];
    private String[] spriteCurrentAnim = new String[0];
    private final LevelData levelData;
    private final boolean[] layerVisibility;
    private final GameplayController gameplayController;
    private final GameInputState inputState = new GameInputState();
    private final Vector2 samplePointCache = new Vector2();
    private final Rectangle backButtonBounds = new Rectangle();
    private final GlyphLayout hudLayout = new GlyphLayout();
    private Texture hudSolidTexture;
    private float fixedStepAccumulator = 0f;
    private float pathMotionTime = 0f;

    // Estado del joystick
    private float joystickCenterX, joystickCenterY;
    private float joystickDx = 0f, joystickDy = 0f;
    private float jumpCenterX, jumpCenterY;
    private int joystickPointer = -1;
    private int jumpPointer = -1;
    private boolean wasTouchingJump = false;
    private String lastSentDirection = "";
    private long lastWsSendTimeMs = 0;
    private float timeSinceLastPositionSend = 0f;

    // Multijugador
    private final ObjectMap<String, RemotePlayer> remotePlayers = new ObjectMap<>();
    private Texture remotePlayerTexture;
    private final String myNickname;
    private static final float REMOTE_PLAYER_WIDTH = 32f;
    private static final float REMOTE_PLAYER_HEIGHT = 32f;

    // Listener para WebSocket
    private WebSocketClient.MessageListener wsMessageListener;

    public PlayScreen(GameApp game, int levelIndex) {
        this.game = game;
        this.levelIndex = levelIndex;
        this.levelData = LevelLoader.loadLevel(levelIndex);
        this.myNickname = game.getPlayerNickname();
        this.layerVisibility = buildInitialLayerVisibility(levelData);
        this.viewport = createViewport(levelData, camera);
        camera.setToOrtho(false);
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
        applyInitialCamera();
        initAnimationState();
        initTransformState();
        initPathBindings();
        this.gameplayController = createController();
        hudViewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        loadHudAssets();
        createRemotePlayerTexture();
        updateJoystickPositions();
        updateBackButtonBounds();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(null);

        // Configurar listener de WebSocket
        wsMessageListener = this::handleServerMessage;
        game.getWsClient().setMessageListener(wsMessageListener);

        // Enviar posición inicial y solicitar lista de jugadores
        sendPositionUpdate();
        game.getWsClient().sendGetPlayers();
    }

    @Override
    public void hide() {
        game.getWsClient().setMessageListener(null);
    }

    @Override
    public void render(float delta) {
        updateInputState();

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            returnToMenu();
            return;
        }

        gameplayController.handleInput();
        stepSimulation(delta);

        viewport.apply();
        updateCamera();
        ScreenUtils.clear(levelData.backgroundColor);

        SpriteBatch batch = game.getBatch();
        batch.begin();
        levelRenderer.render(
            levelData,
            game.getAssetManager(),
            batch,
            camera,
            spriteRuntimeStates,
            layerVisibility,
            layerRuntimeStates
        );
        // Renderizar otros jugadores
        //renderRemotePlayers(batch); // COMENTADO PARA QUE NO SALGAN LOS NOMBRES
        batch.end();

        debugOverlay.render(levelData, camera, false, false, zoneRuntimeStates);

        renderHud();
        renderJoystick();
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, false);
        hudViewport.update(width, height, true);
        updateJoystickPositions();
        updateBackButtonBounds();
        updateCamera();
    }

    @Override
    public void dispose() {
        debugOverlay.dispose();
        if (hudSolidTexture != null) hudSolidTexture.dispose();
        if (remotePlayerTexture != null) remotePlayerTexture.dispose();
    }

    // ==================== INPUT ====================

    private void updateInputState() {
        inputState.reset();

        // Teclado físico
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A))
            inputState.moveX -= 1f;
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D))
            inputState.moveX += 1f;
        inputState.jumpHeld = Gdx.input.isKeyPressed(Input.Keys.SPACE)
            || Gdx.input.isKeyPressed(Input.Keys.W)
            || Gdx.input.isKeyPressed(Input.Keys.UP);
        inputState.jumpPressed = Gdx.input.isKeyJustPressed(Input.Keys.SPACE)
            || Gdx.input.isKeyJustPressed(Input.Keys.W)
            || Gdx.input.isKeyJustPressed(Input.Keys.UP);
        inputState.resetPressed = Gdx.input.isKeyJustPressed(Input.Keys.R);

        // Joystick táctil
        readTouchInput();
        if (Math.abs(joystickDx) > JOYSTICK_DEAD_ZONE)
            inputState.moveX = joystickDx;

        // Enviar movimiento si cambió
        sendMovementIfNeeded();
    }

    private void readTouchInput() {
        float hudW = hudViewport.getWorldWidth();

        if (joystickPointer >= 0 && !Gdx.input.isTouched(joystickPointer)) {
            joystickPointer = -1;
            joystickDx = 0f;
            joystickDy = 0f;
        }
        if (jumpPointer >= 0 && !Gdx.input.isTouched(jumpPointer)) {
            jumpPointer = -1;
            wasTouchingJump = false;
        }

        for (int i = 0; i < 5; i++) {
            if (!Gdx.input.isTouched(i)) continue;

            float touchX = Gdx.input.getX(i);
            float touchY = hudViewport.getScreenHeight() - Gdx.input.getY(i);

            if (joystickPointer < 0 && touchX < hudW * 0.5f) {
                joystickPointer = i;
            }
            if (jumpPointer < 0 && touchX >= hudW * 0.5f) {
                jumpPointer = i;
            }

            if (i == joystickPointer) {
                float dx = touchX - joystickCenterX;
                float dy = touchY - joystickCenterY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float maxDist = JOYSTICK_BASE_RADIUS - JOYSTICK_THUMB_RADIUS;
                if (dist > maxDist) {
                    dx = dx / dist * maxDist;
                    dy = dy / dist * maxDist;
                }
                joystickDx = (maxDist > 0f) ? dx / maxDist : 0f;
                joystickDy = (maxDist > 0f) ? dy / maxDist : 0f;
            }

            if (i == jumpPointer) {
                float dx = touchX - jumpCenterX;
                float dy = touchY - jumpCenterY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist <= JUMP_BUTTON_RADIUS) {
                    inputState.jumpHeld = true;
                    inputState.jumpPressed = !wasTouchingJump;
                    wasTouchingJump = true;
                } else {
                    wasTouchingJump = false;
                }
            }
        }

        if (jumpPointer < 0) wasTouchingJump = false;
    }

    private void sendMovementIfNeeded() {
        String direction = resolveDirection();
        long now = System.currentTimeMillis();

        if (!direction.equals(lastSentDirection) && (now - lastWsSendTimeMs) >= WS_SEND_INTERVAL_MS) {
            if (!direction.isEmpty()) {
                game.getWsClient().sendMove(direction);
            }
            lastSentDirection = direction;
            lastWsSendTimeMs = now;
        }
    }

    private String resolveDirection() {
        if (inputState.jumpPressed || inputState.jumpHeld) return "UP";
        if (inputState.moveX < -JOYSTICK_DEAD_ZONE) return "LEFT";
        if (inputState.moveX > JOYSTICK_DEAD_ZONE) return "RIGHT";
        return "";
    }

    private void sendPositionUpdate() {
        if (!game.getWsClient().isConnected()) return;
        if (!gameplayController.hasCameraTarget()) return;

        String direction = resolveDirection();
        if (!direction.isEmpty()) {
            game.getWsClient().sendMove(direction);
        }
    }

    // ==================== WEBSOCKET ====================

    private void handleServerMessage(String type, JsonValue payload) {
        switch (type) {
            case "PLAYER_LIST":
                handlePlayerList(payload);
                break;
            case "MOVE":
                handleRemoteMove(payload);
                break;
            case "JOIN_OK":
                String newPlayer = payload.getString("nickname", "");
                if (!newPlayer.isEmpty() && !newPlayer.equals(myNickname)) {
                    addRemotePlayer(newPlayer);
                }
                break;
        }
    }

    private void handlePlayerList(JsonValue payload) {
        JsonValue playersArray = payload.get("players");
        if (playersArray == null) return;

        for (RemotePlayer rp : remotePlayers.values()) {
            rp.tempFlag = false;
        }

        for (JsonValue item = playersArray.child; item != null; item = item.next) {
            String nickname = item.asString();
            if (nickname.equals(myNickname)) continue;

            RemotePlayer rp = remotePlayers.get(nickname);
            if (rp == null) {
                rp = new RemotePlayer(nickname, levelData.viewportX + 100, levelData.viewportY + 100);
                remotePlayers.put(nickname, rp);
            }
            rp.tempFlag = true;
        }

        Array<String> toRemove = new Array<>();
        for (ObjectMap.Entry<String, RemotePlayer> entry : remotePlayers.entries()) {
            if (!entry.value.tempFlag) {
                toRemove.add(entry.key);
            }
        }
        for (String nick : toRemove) {
            remotePlayers.remove(nick);
        }
    }

    private void handleRemoteMove(JsonValue payload) {
        String nickname = payload.getString("nickname", "");
        String direction = payload.getString("direction", "");

        if (nickname.equals(myNickname)) return;

        RemotePlayer rp = remotePlayers.get(nickname);
        if (rp == null) {
            rp = new RemotePlayer(nickname, levelData.viewportX + 100, levelData.viewportY + 100);
            remotePlayers.put(nickname, rp);
        }

        rp.direction = direction;
        rp.flipX = "LEFT".equals(direction);

        float moveSpeed = 5f;
        if ("LEFT".equals(direction)) {
            rp.x -= moveSpeed;
        } else if ("RIGHT".equals(direction)) {
            rp.x += moveSpeed;
        }

        rp.x = MathUtils.clamp(rp.x, 0, levelData.worldWidth - REMOTE_PLAYER_WIDTH);
    }

    private void addRemotePlayer(String nickname) {
        if (remotePlayers.containsKey(nickname)) return;
        RemotePlayer rp = new RemotePlayer(nickname, levelData.viewportX + 100, levelData.viewportY + 100);
        remotePlayers.put(nickname, rp);
    }

    // ==================== RENDERIZADO ====================

    private void renderRemotePlayers(SpriteBatch batch) {
        for (RemotePlayer rp : remotePlayers.values()) {
            float drawX = rp.x;
            float drawY = levelData.worldHeight - rp.y - REMOTE_PLAYER_HEIGHT;

            boolean flip = rp.flipX;
            // Versión para Texture (sin región)
            batch.draw(remotePlayerTexture,
                drawX, drawY,
                REMOTE_PLAYER_WIDTH / 2, REMOTE_PLAYER_HEIGHT / 2,
                REMOTE_PLAYER_WIDTH, REMOTE_PLAYER_HEIGHT,
                flip ? -1f : 1f, 1f, 0f,
                0, 0,
                (int)REMOTE_PLAYER_WIDTH, (int)REMOTE_PLAYER_HEIGHT,
                flip, false);

            BitmapFont font = game.getFont();
            font.setColor(Color.WHITE);
            font.getData().setScale(0.6f);
            GlyphLayout layout = new GlyphLayout(font, rp.nickname);
            font.draw(batch, layout,
                rp.x + REMOTE_PLAYER_WIDTH / 2 - layout.width / 2,
                drawY + REMOTE_PLAYER_HEIGHT + 15);
            font.getData().setScale(1f);
        }
    }

    private void renderJoystick() {
        hudViewport.apply();
        ShapeRenderer shapes = game.getShapeRenderer();
        shapes.setProjectionMatrix(hudCamera.combined);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(JOYSTICK_BASE_COLOR);
        shapes.circle(joystickCenterX, joystickCenterY, JOYSTICK_BASE_RADIUS, 32);

        float maxDist = JOYSTICK_BASE_RADIUS - JOYSTICK_THUMB_RADIUS;
        float thumbX = joystickCenterX + joystickDx * maxDist;
        float thumbY = joystickCenterY + joystickDy * maxDist;
        shapes.setColor(JOYSTICK_THUMB_COLOR);
        shapes.circle(thumbX, thumbY, JOYSTICK_THUMB_RADIUS, 24);

        boolean jumping = inputState.jumpHeld;
        shapes.setColor(jumping ? JUMP_BUTTON_COLOR : JUMP_BUTTON_DIM);
        shapes.circle(jumpCenterX, jumpCenterY, JUMP_BUTTON_RADIUS, 32);

        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(JOYSTICK_THUMB_COLOR);
        shapes.circle(joystickCenterX, joystickCenterY, JOYSTICK_BASE_RADIUS, 32);
        shapes.setColor(JUMP_BUTTON_COLOR);
        shapes.circle(jumpCenterX, jumpCenterY, JUMP_BUTTON_RADIUS, 32);
        shapes.end();

        SpriteBatch batch = game.getBatch();
        batch.setProjectionMatrix(hudCamera.combined);
        batch.begin();
        BitmapFont font = game.getFont();
        font.getData().setScale(1.4f);
        font.setColor(Color.BLACK);
        hudLayout.setText(font, "JUMP");
        font.draw(batch, hudLayout,
            jumpCenterX - hudLayout.width * 0.5f,
            jumpCenterY + hudLayout.height * 0.5f);
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);
        batch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderHud() {
        hudViewport.apply();
        SpriteBatch batch = game.getBatch();
        BitmapFont font = game.getFont();
        batch.setProjectionMatrix(hudCamera.combined);
        batch.begin();
        font.getData().setScale(1.6f);
        font.setColor(HUD_TEXT_COLOR);
        font.draw(batch, "< Volver", backButtonBounds.x, backButtonBounds.y + backButtonBounds.height * 0.75f);

        font.getData().setScale(1.3f);
        font.setColor(Color.valueOf("35FF74"));
        hudLayout.setText(font, myNickname);
        float hudW = hudViewport.getWorldWidth();
        font.draw(batch, myNickname,
            hudW - HUD_MARGIN - hudLayout.width,
            hudViewport.getWorldHeight() - HUD_MARGIN);

        batch.end();
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);

        if (Gdx.input.justTouched()) {
            float tx = Gdx.input.getX();
            float ty = hudViewport.getScreenHeight() - Gdx.input.getY();
            if (backButtonBounds.contains(tx, ty)) returnToMenu();
        }
    }

    private void updateJoystickPositions() {
        float hudW = hudViewport.getWorldWidth();
        joystickCenterX = JOYSTICK_MARGIN;
        joystickCenterY = JOYSTICK_MARGIN;
        jumpCenterX = hudW - JUMP_MARGIN;
        jumpCenterY = JUMP_MARGIN;
    }

    private void updateBackButtonBounds() {
        float hudH = hudViewport.getWorldHeight();
        backButtonBounds.set(HUD_MARGIN, hudH - HUD_MARGIN - HUD_BUTTON_HEIGHT,
            160f, HUD_BUTTON_HEIGHT);
    }

    private void createRemotePlayerTexture() {
        Pixmap pixmap = new Pixmap((int) REMOTE_PLAYER_WIDTH, (int) REMOTE_PLAYER_HEIGHT, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.RED);
        pixmap.fill();
        pixmap.setColor(Color.BLACK);
        pixmap.drawRectangle(0, 0, (int) REMOTE_PLAYER_WIDTH, (int) REMOTE_PLAYER_HEIGHT);
        remotePlayerTexture = new Texture(pixmap);
        pixmap.dispose();
    }

    private void returnToMenu() {
        game.unloadReferencedAssetsForLevel(levelIndex);
        game.setScreen(new MenuScreen(game));
    }

    // ==================== SIMULACIÓN ====================

    private void stepSimulation(float delta) {
        float clamped = Math.max(0f, Math.min(MAX_FRAME_SECONDS, delta));
        fixedStepAccumulator += clamped;

        timeSinceLastPositionSend += clamped;
        if (timeSinceLastPositionSend >= POSITION_SEND_INTERVAL) {
            sendPositionUpdate();
            timeSinceLastPositionSend = 0f;
        }

        while (fixedStepAccumulator >= FIXED_STEP_SECONDS) {
            snapshotPreviousZones();
            advancePathBindings(FIXED_STEP_SECONDS);
            gameplayController.fixedUpdate(FIXED_STEP_SECONDS);
            updateAnimations(FIXED_STEP_SECONDS);
            fixedStepAccumulator -= FIXED_STEP_SECONDS;
        }
    }

    // ==================== MÉTODOS HEREDADOS (sin cambios) ====================

    private void initAnimationState() {
        spriteRuntimeStates.clear();
        spriteAnimElapsed.clear();
        spriteTotalFrames.clear();
        spriteAnimElapsed.setSize(levelData.sprites.size);
        spriteTotalFrames.setSize(levelData.sprites.size);
        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            spriteRuntimeStates.add(new LevelRenderer.SpriteRuntimeState(
                s.frameIndex, s.anchorX, s.anchorY, s.x, s.y,
                true, s.flipX, s.flipY,
                Math.max(1, Math.round(s.width)),
                Math.max(1, Math.round(s.height)),
                s.texturePath, s.animationId
            ));
            spriteTotalFrames.set(i, 0);
            spriteAnimElapsed.set(i, 0f);
        }
        spriteCacheKey = new String[levelData.sprites.size];
        spriteCurrentAnim = new String[levelData.sprites.size];
    }

    private void initTransformState() {
        layerRuntimeStates.clear();
        zoneRuntimeStates.clear();
        zonePreviousStates.clear();
        for (int i = 0; i < levelData.layers.size; i++) {
            LevelData.LevelLayer l = levelData.layers.get(i);
            layerRuntimeStates.add(new RuntimeTransform(l.x, l.y));
        }
        for (int i = 0; i < levelData.zones.size; i++) {
            LevelData.LevelZone z = levelData.zones.get(i);
            zoneRuntimeStates.add(new RuntimeTransform(z.x, z.y));
            zonePreviousStates.add(new RuntimeTransform(z.x, z.y));
        }
        pathMotionTime = 0f;
    }

    private void initPathBindings() {
        pathBindingRuntimes.clear();
        if (levelData.pathBindings == null || levelData.pathBindings.size <= 0) return;
        ObjectMap<String, PathRuntime> pathById = new ObjectMap<>();
        for (int i = 0; i < levelData.paths.size; i++) {
            LevelData.LevelPath p = levelData.paths.get(i);
            if (p == null || p.id == null || p.id.isEmpty() || p.points == null || p.points.size < 2) continue;
            PathRuntime rt = PathRuntime.from(p);
            if (rt != null) pathById.put(p.id, rt);
        }
        for (int i = 0; i < levelData.pathBindings.size; i++) {
            LevelData.LevelPathBinding b = levelData.pathBindings.get(i);
            if (b == null || !b.enabled) continue;
            PathRuntime p = pathById.get(b.pathId);
            if (p == null) continue;
            float ix = 0f, iy = 0f;
            if ("layer".equals(b.targetType) && b.targetIndex < layerRuntimeStates.size) {
                ix = layerRuntimeStates.get(b.targetIndex).x;
                iy = layerRuntimeStates.get(b.targetIndex).y;
            } else if ("zone".equals(b.targetType) && b.targetIndex < zoneRuntimeStates.size) {
                ix = zoneRuntimeStates.get(b.targetIndex).x;
                iy = zoneRuntimeStates.get(b.targetIndex).y;
            } else if ("sprite".equals(b.targetType) && b.targetIndex < spriteRuntimeStates.size) {
                ix = spriteRuntimeStates.get(b.targetIndex).worldX;
                iy = spriteRuntimeStates.get(b.targetIndex).worldY;
            }
            pathBindingRuntimes.add(new PathBindingRuntime(b, p, ix, iy));
        }
    }

    private void snapshotPreviousZones() {
        for (int i = 0; i < zoneRuntimeStates.size && i < zonePreviousStates.size; i++) {
            zonePreviousStates.get(i).x = zoneRuntimeStates.get(i).x;
            zonePreviousStates.get(i).y = zoneRuntimeStates.get(i).y;
        }
    }

    private void advancePathBindings(float dt) {
        if (pathBindingRuntimes.size <= 0) return;
        pathMotionTime += Math.max(0f, dt);
        for (int i = 0; i < pathBindingRuntimes.size; i++) {
            PathBindingRuntime r = pathBindingRuntimes.get(i);
            if (r == null || !r.binding.enabled) continue;
            float progress = pathProgress(r.binding.behavior, r.binding.durationSeconds, pathMotionTime);
            r.path.sampleAtProgress(progress, samplePointCache);
            float tx = r.binding.relativeToInitialPosition
                ? r.initialX + (samplePointCache.x - r.path.firstPointX)
                : samplePointCache.x;
            float ty = r.binding.relativeToInitialPosition
                ? r.initialY + (samplePointCache.y - r.path.firstPointY)
                : samplePointCache.y;
            applyPathTarget(r.binding.targetType, r.binding.targetIndex, tx, ty);
        }
    }

    private void applyPathTarget(String type, int idx, float x, float y) {
        if ("layer".equals(type) && idx >= 0 && idx < layerRuntimeStates.size) {
            layerRuntimeStates.get(idx).x = x;
            layerRuntimeStates.get(idx).y = y;
        } else if ("zone".equals(type) && idx >= 0 && idx < zoneRuntimeStates.size) {
            zoneRuntimeStates.get(idx).x = x;
            zoneRuntimeStates.get(idx).y = y;
        } else if ("sprite".equals(type) && idx >= 0 && idx < spriteRuntimeStates.size) {
            spriteRuntimeStates.get(idx).worldX = x;
            spriteRuntimeStates.get(idx).worldY = y;
        }
    }

    private float pathProgress(String behavior, float duration, float time) {
        if (!Float.isFinite(duration) || duration <= 0f) return 0f;
        float t = Math.max(0f, time);
        String b = behavior == null ? "" : behavior.trim().toLowerCase();
        if ("ping_pong".equals(b) || "pingpong".equals(b)) {
            float cycle = duration * 2f;
            float ct = t % cycle;
            return ct <= duration ? ct / duration : 1f - (ct - duration) / duration;
        }
        if ("once".equals(b)) return MathUtils.clamp(t / duration, 0f, 1f);
        return (t % duration) / duration;
    }

    private void updateAnimations(float delta) {
        for (int i = 0; i < levelData.sprites.size; i++) {
            updateSpriteAnim(levelData.sprites.get(i), spriteRuntimeStates.get(i), i, delta);
        }
    }

    private void updateSpriteAnim(LevelData.LevelSprite sprite, LevelRenderer.SpriteRuntimeState rs, int idx, float dt) {
        String animId = gameplayController.animationOverrideForSprite(idx);
        if (animId == null || animId.isEmpty()) animId = sprite.animationId;

        String prev = idx < spriteCurrentAnim.length ? spriteCurrentAnim[idx] : null;
        if ((prev == null) != (animId == null) || (prev != null && !prev.equals(animId))) {
            if (idx < spriteAnimElapsed.size) spriteAnimElapsed.set(idx, 0f);
            if (idx < spriteCurrentAnim.length) spriteCurrentAnim[idx] = animId;
        }

        if (animId == null || animId.isEmpty()) {
            rs.texturePath = sprite.texturePath;
            rs.frameWidth = Math.max(1, Math.round(sprite.width));
            rs.frameHeight = Math.max(1, Math.round(sprite.height));
            rs.anchorX = sprite.anchorX;
            rs.anchorY = sprite.anchorY;
            rs.frameIndex = sprite.frameIndex;
            return;
        }

        LevelData.AnimationClip clip = levelData.animationClips.get(animId);
        if (clip == null) {
            rs.texturePath = sprite.texturePath;
            rs.frameWidth = Math.max(1, Math.round(sprite.width));
            rs.frameHeight = Math.max(1, Math.round(sprite.height));
            rs.anchorX = sprite.anchorX;
            rs.anchorY = sprite.anchorY;
            rs.frameIndex = sprite.frameIndex;
            return;
        }

        rs.texturePath = clip.texturePath != null && !clip.texturePath.isEmpty() ? clip.texturePath : sprite.texturePath;
        rs.frameWidth = clip.frameWidth > 0 ? clip.frameWidth : Math.max(1, Math.round(sprite.width));
        rs.frameHeight = clip.frameHeight > 0 ? clip.frameHeight : Math.max(1, Math.round(sprite.height));

        int total = resolveTotalFrames(idx, rs.texturePath, rs.frameWidth, rs.frameHeight);
        if (total <= 0) {
            rs.anchorX = clip.anchorX;
            rs.anchorY = clip.anchorY;
            return;
        }

        float elapsed = spriteAnimElapsed.get(idx) + dt;
        spriteAnimElapsed.set(idx, elapsed);

        int start = Math.max(0, Math.min(total - 1, clip.startFrame));
        int end = Math.max(start, Math.min(total - 1, clip.endFrame));
        int span = Math.max(1, end - start + 1);
        float fps = Float.isFinite(clip.fps) && clip.fps > 0f ? clip.fps : DEFAULT_ANIMATION_FPS;
        int ticks = (int) Math.floor(elapsed * fps);
        int offset = clip.loop ? positiveMod(ticks, span) : Math.min(ticks, span - 1);
        rs.frameIndex = start + offset;

        LevelData.FrameRig rig = clip.frameRigs.get(rs.frameIndex);
        rs.anchorX = rig != null ? rig.anchorX : clip.anchorX;
        rs.anchorY = rig != null ? rig.anchorY : clip.anchorY;
    }

    private int resolveTotalFrames(int idx, String texPath, int fw, int fh) {
        if (idx < 0 || idx >= spriteTotalFrames.size) return 0;
        int cached = spriteTotalFrames.get(idx);
        String key = (texPath == null ? "" : texPath) + "#" + fw + "x" + fh;
        String cachedKey = idx < spriteCacheKey.length ? spriteCacheKey[idx] : null;
        if (cached > 0 && key.equals(cachedKey)) return cached;

        if (texPath == null || texPath.isEmpty()) return 0;
        if (!game.getAssetManager().isLoaded(texPath, Texture.class)) return 0;
        Texture tex = game.getAssetManager().get(texPath, Texture.class);
        int cols = Math.max(1, tex.getWidth() / Math.max(1, Math.min(fw, tex.getWidth())));
        int rows = Math.max(1, tex.getHeight() / Math.max(1, Math.min(fh, tex.getHeight())));
        int total = Math.max(1, cols * rows);
        spriteTotalFrames.set(idx, total);
        if (idx < spriteCacheKey.length) spriteCacheKey[idx] = key;
        return total;
    }

    private void loadHudAssets() {
        if (hudSolidTexture == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            hudSolidTexture = new Texture(pm);
            pm.dispose();
        }
    }

    private void applyInitialCamera() {
        // Centrar la cámara en el viewport definido (no en el mundo completo)
        float cx = levelData.viewportX + levelData.viewportWidth * 0.5f;
        float cy = levelData.viewportY + levelData.viewportHeight * 0.5f;

        // Convertir Y de coordenadas del mundo (Y hacia abajo) a coordenadas de cámara (Y hacia arriba)
        float cameraY = levelData.worldHeight - cy;

        camera.position.set(cx, cameraY, 0f);
        camera.zoom = 1f; // Zoom normal
        camera.update();
    }

    private void updateCamera() {
        // Cámara FIJA en la posición del viewport
        float cx = levelData.viewportX + levelData.viewportWidth * 0.5f;
        float cy = levelData.viewportY + levelData.viewportHeight * 0.5f;
        float cameraY = levelData.worldHeight - cy;

        camera.position.set(cx, cameraY, 0f);
        camera.update();
    }

    private GameplayController createController() {
        if (isPlatformerLevel(levelData)) {
            Gdx.app.log("PlayScreen", "Modo: platformer");
            return new GameplayControllerPlatformer(
                levelData, spriteRuntimeStates, layerVisibility,
                zoneRuntimeStates, zonePreviousStates, inputState);
        }
        Gdx.app.log("PlayScreen", "Modo: topdown");
        return new GameplayControllerTopDown(
            levelData, spriteRuntimeStates, layerVisibility,
            zoneRuntimeStates, zonePreviousStates, inputState);
    }

    private static boolean isPlatformerLevel(LevelData d) {
        for (int i = 0; i < d.zones.size; i++) {
            String t = normalize(d.zones.get(i).type);
            String n = normalize(d.zones.get(i).name);
            if (containsAny(t, "floor", "death") || containsAny(n, "floor", "death")) return true;
        }
        return false;
    }

    private static String normalize(String v) {
        return v == null ? "" : v.trim().toLowerCase();
    }

    private static boolean containsAny(String v, String... needles) {
        if (v == null || v.isEmpty()) return false;
        for (String n : needles)
            if (n != null && !n.isEmpty() && v.contains(n)) return true;
        return false;
    }

    private static Viewport createViewport(LevelData d, OrthographicCamera cam) {
        // Usar el viewport DEFINIDO en game_data.json (tamaño lógico de pantalla)
        // NO el worldWidth/worldHeight (que es el tamaño total del nivel)
        float viewportWidth = d.viewportWidth;
        float viewportHeight = d.viewportHeight;

        if (viewportWidth <= 0) viewportWidth = 1280;
        if (viewportHeight <= 0) viewportHeight = 720;

        Gdx.app.log("PlayScreen", "Viewport lógico: " + viewportWidth + "x" + viewportHeight);

        return new FitViewport(viewportWidth, viewportHeight, cam);
    }

    private static boolean[] buildInitialLayerVisibility(LevelData d) {
        boolean[] v = new boolean[d.layers.size];
        for (int i = 0; i < d.layers.size; i++) v[i] = d.layers.get(i).visible;
        return v;
    }

    private static int positiveMod(int v, int d) {
        if (d <= 0) return 0;
        int m = v % d;
        return m < 0 ? m + d : m;
    }

    // ==================== CLASES INTERNAS ====================

    private static final class PathBindingRuntime {
        final LevelData.LevelPathBinding binding;
        final PathRuntime path;
        final float initialX, initialY;

        PathBindingRuntime(LevelData.LevelPathBinding b, PathRuntime p, float x, float y) {
            binding = b;
            path = p;
            initialX = x;
            initialY = y;
        }
    }

    private static final class PathRuntime {
        final Array<Vector2> points;
        final FloatArray cumulative;
        final float totalDistance, firstPointX, firstPointY;

        private PathRuntime(Array<Vector2> pts, FloatArray cum, float total, float fx, float fy) {
            points = pts;
            cumulative = cum;
            totalDistance = total;
            firstPointX = fx;
            firstPointY = fy;
        }

        static PathRuntime from(LevelData.LevelPath path) {
            if (path == null || path.points == null || path.points.size < 2) return null;
            FloatArray cum = new FloatArray();
            cum.add(0f);
            float total = 0f;
            for (int i = 1; i < path.points.size; i++) {
                total += path.points.get(i).dst(path.points.get(i - 1));
                cum.add(total);
            }
            Vector2 first = path.points.first();
            return new PathRuntime(path.points, cum, total, first.x, first.y);
        }

        void sampleAtProgress(float progress, Vector2 out) {
            if (out == null) return;
            if (points == null || points.size <= 0) {
                out.set(0, 0);
                return;
            }
            if (points.size < 2 || totalDistance <= 0f) {
                out.set(points.first());
                return;
            }
            float target = totalDistance * MathUtils.clamp(progress, 0f, 1f);
            for (int i = 1; i < points.size; i++) {
                float segEnd = cumulative.get(i);
                if (target > segEnd && i < points.size - 1) continue;
                float segStart = cumulative.get(i - 1);
                float segLen = segEnd - segStart;
                if (segLen <= 0f) {
                    out.set(points.get(i));
                    return;
                }
                float t = MathUtils.clamp((target - segStart) / segLen, 0f, 1f);
                Vector2 a = points.get(i - 1), b = points.get(i);
                out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
                return;
            }
            out.set(points.peek());
        }
    }
}
