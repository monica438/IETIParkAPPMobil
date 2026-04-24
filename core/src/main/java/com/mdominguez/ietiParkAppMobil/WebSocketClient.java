package com.mdominguez.ietiParkAppMobil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.czyzby.websocket.WebSocket;
import com.github.czyzby.websocket.WebSocketAdapter;
import com.github.czyzby.websocket.WebSocketHandler;
import com.github.czyzby.websocket.WebSockets;

public class WebSocketClient {

    // ==================== INTERFACES ====================

    public interface PlayerListListener {
        void onPlayerListUpdated(List<String> players);
    }

    public interface MessageListener {
        void onMessage(String type, JsonValue payload);
    }

    public interface ConnectionListener {
        void onConnectionStateChanged(boolean connected);
    }

    // ==================== CONSTANTES DE RECONEXIÓN ====================

    private static final String SERVER_URL = "wss://pico2.ieti.site";
    //private static final String SERVER_URL = "ws://10.0.2.2:8080";
    private static final float RECONNECT_DELAY_MIN = 1f;
    private static final float RECONNECT_DELAY_MAX = 30f;
    private static final float RECONNECT_DELAY_FACTOR = 2f;

    // ==================== CAMPOS ====================

    private WebSocket socket;
    private boolean connected = false;
    private boolean intentionallyClosed = false;
    private boolean reconnecting = false;

    private float reconnectDelayCurrent = RECONNECT_DELAY_MIN;
    private float reconnectTimer = 0f;

    private String confirmedNickname = null;
    private String confirmedCat = null;  // Se asigna en JOIN_OK, NO en WELCOME
    private int availableCatsCount = 0;  // Se actualiza en WELCOME
    private final List<String> activePlayers = new ArrayList<>();
    private final Map<String, String> activePlayerCats = new HashMap<>();
    private final JsonReader jsonReader = new JsonReader();
    private final java.util.Queue<String> pendingMessages = new java.util.ArrayDeque<>();

    private PlayerListListener playerListListener;
    private MessageListener messageListener;
    private ConnectionListener connectionListener;

    // ==================== SETTERS DE LISTENERS ====================

    public void setPlayerListListener(PlayerListListener listener) {
        this.playerListListener = listener;
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    // ==================== GETTERS ====================

    public boolean isConnected() { return connected; }
    public boolean isReconnecting() { return reconnecting; }
    public String getConfirmedNickname() { return confirmedNickname; }
    public String getConfirmedCat() { return confirmedCat; }
    public int getAvailableCatsCount() { return availableCatsCount; }
    public List<String> getActivePlayers() { return new ArrayList<>(activePlayers); }

    public Map<String, String> getActivePlayerCats() {
        return new HashMap<>(activePlayerCats);
    }

    public float getReconnectDelay() { return reconnectDelayCurrent; }

    public float getReconnectProgress() {
        if (!reconnecting || reconnectDelayCurrent <= 0f) return 0f;
        return Math.min(reconnectTimer / reconnectDelayCurrent, 1f);
    }

    // ==================== CONEXIÓN ====================

    public void connect() {
        intentionallyClosed = false;
        reconnecting = false;
        reconnectTimer = 0f;
        doConnect();
    }

    private void doConnect() {
        if (socket != null) {
            try { WebSockets.closeGracefully(socket); } catch (Exception ignored) {}
            socket = null;
        }

        Gdx.app.log("WebSocketClient", "Conectando a " + SERVER_URL + " ...");

        socket = WebSockets.newSocket(SERVER_URL);
        socket.setSendGracefully(true);

        socket.addListener(new WebSocketAdapter() {

            @Override
            public boolean onOpen(WebSocket webSocket) {
                connected = true;
                reconnecting = false;
                reconnectDelayCurrent = RECONNECT_DELAY_MIN;
                Gdx.app.log("WebSocketClient", "✅ Conectado al servidor");

                // Reenviar mensajes pendientes
                try {
                    while (!pendingMessages.isEmpty()) {
                        String m = pendingMessages.poll();
                        if (m == null) break;
                        webSocket.send(m);
                        Gdx.app.log("WebSocketClient", "📤 Enviado (pendiente): " + m);
                    }
                } catch (Exception e) {
                    Gdx.app.error("WebSocketClient", "Error al enviar mensajes pendientes", e);
                }

                notifyConnectionState(true);
                return WebSocketHandler.FULLY_HANDLED;
            }

            @Override
            public boolean onClose(WebSocket webSocket, int code, String reason) {
                connected = false;
                Gdx.app.log("WebSocketClient", "🔌 Desconectado (code=" + code + "): " + reason);
                notifyConnectionState(false);

                if (!intentionallyClosed) {
                    scheduleReconnect();
                }
                return WebSocketHandler.FULLY_HANDLED;
            }

            @Override
            public boolean onMessage(WebSocket webSocket, String payload) {
                handleMessage(payload);
                return WebSocketHandler.FULLY_HANDLED;
            }

            @Override
            public boolean onError(WebSocket webSocket, Throwable error) {
                Gdx.app.error("WebSocketClient", "Error WS: " + error.getMessage());
                return WebSocketHandler.FULLY_HANDLED;
            }
        });

        socket.connect();
    }

    public void update(float delta) {
        if (!reconnecting || intentionallyClosed) return;

        reconnectTimer += delta;
        if (reconnectTimer >= reconnectDelayCurrent) {
            reconnectTimer = 0f;
            reconnecting = false;
            Gdx.app.log("WebSocketClient",
                "Intentando reconectar (próximo intervalo: "
                    + Math.min(reconnectDelayCurrent * RECONNECT_DELAY_FACTOR, RECONNECT_DELAY_MAX) + "s)...");
            reconnectDelayCurrent = Math.min(
                reconnectDelayCurrent * RECONNECT_DELAY_FACTOR,
                RECONNECT_DELAY_MAX
            );
            doConnect();
        }
    }

    private void scheduleReconnect() {
        if (intentionallyClosed) return;
        reconnecting = true;
        reconnectTimer = 0f;
        Gdx.app.log("WebSocketClient",
            "Reconexión programada en " + reconnectDelayCurrent + "s");
    }

    // ==================== MANEJO DE MENSAJES DEL SERVIDOR ====================

    private void handleMessage(String payload) {
        JsonValue root;
        try {
            root = jsonReader.parse(payload);
        } catch (Exception e) {
            Gdx.app.error("WebSocketClient", "❌ JSON inválido: " + payload);
            return;
        }

        String type = root.getString("type", "");
        Gdx.app.log("WebSocketClient", "📩 Recibido: " + type);

        switch (type) {
            case "WELCOME": {
                // SOLO mensaje de bienvenida, NO asigna gato
                String msg = root.getString("msg", "");
                availableCatsCount = root.getInt("availableCats", 0);
                Gdx.app.log("WebSocketClient", "👋 WELCOME: " + msg + " (gatos disponibles: " + availableCatsCount + ")");

                // Si ya teníamos un nickname confirmado (reconexión), reenviar JOIN
                if (confirmedNickname != null && !confirmedNickname.isEmpty()) {
                    Gdx.app.log("WebSocketClient", "Reconexión detectada, reenviando JOIN para " + confirmedNickname);
                    sendJoin(confirmedNickname);
                }
                break;
            }

            case "JOIN_OK": {
                // AQUÍ es donde el servidor nos asigna el gato
                confirmedNickname = root.getString("nickname", "");
                confirmedCat = root.getString("cat", "");
                Gdx.app.log("WebSocketClient", "✅ JOIN_OK: nickname=" + confirmedNickname + ", cat=" + confirmedCat);
                notifyMessageListener(type, root);
                break;
            }

            case "JOIN_ERROR": {
                // El servidor rechaza nuestro registro (no hay gatos disponibles)
                String msg = root.getString("msg", "Error desconocido");
                Gdx.app.error("WebSocketClient", "❌ JOIN_ERROR: " + msg);
                confirmedNickname = null;
                confirmedCat = null;
                notifyMessageListener(type, root);
                break;
            }

            case "PLAYER_LIST": {
                // El servidor nos envía la lista de jugadores con sus gatos
                JsonValue playersArray = root.get("players");
                activePlayers.clear();
                activePlayerCats.clear();

                if (playersArray != null) {
                    for (JsonValue item = playersArray.child; item != null; item = item.next) {
                        String nick = item.getString("nickname", "");
                        String cat = item.getString("cat", "");
                        if (!nick.isEmpty()) {
                            activePlayers.add(nick);
                            if (cat != null && !cat.isEmpty()) {
                                activePlayerCats.put(nick, cat);
                            }
                        }
                    }
                }

                Gdx.app.log("WebSocketClient", "👥 PLAYER_LIST: " + activePlayers.size() + " jugadores");
                notifyPlayerList();
                notifyMessageListener(type, root);
                break;
            }

            case "MOVE": {
                // Otro jugador se ha movido
                String nick = root.getString("nickname", "?");
                String cat = root.getString("cat", "");
                String dir = root.getString("dir", "?");

                Gdx.app.log("WebSocketClient",
                    "🏃 MOVE: " + nick + " (gato=" + cat + ") -> " + dir);
                notifyMessageListener(type, root);
                break;
            }

            case "PONG": {
                // Respuesta del servidor a nuestro PING
                Gdx.app.log("WebSocketClient", "💓 PONG recibido");
                break;
            }

            case "ERROR": {
                // Error genérico del servidor
                String msg = root.getString("msg", "Error desconocido");
                Gdx.app.error("WebSocketClient", "⚠️ ERROR del servidor: " + msg);
                notifyMessageListener(type, root);
                break;
            }

            default:
                Gdx.app.log("WebSocketClient", "❓ Tipo de mensaje desconocido: " + type);
                break;
        }
    }

    // ==================== NOTIFICACIONES ====================

    private void notifyPlayerList() {
        if (playerListListener == null) return;
        final List<String> snapshot = new ArrayList<>(activePlayers);
        Gdx.app.postRunnable(() -> playerListListener.onPlayerListUpdated(snapshot));
    }

    private void notifyMessageListener(String type, JsonValue payload) {
        if (messageListener == null) return;
        final String typeCopy = type;
        final String payloadCopy = payload.toString();
        Gdx.app.postRunnable(() -> {
            try {
                JsonValue parsed = new JsonReader().parse(payloadCopy);
                messageListener.onMessage(typeCopy, parsed);
            } catch (Exception e) {
                Gdx.app.error("WebSocketClient", "Error notificando mensaje", e);
            }
        });
    }

    private void notifyConnectionState(boolean isConnected) {
        if (connectionListener == null) return;
        Gdx.app.postRunnable(() -> connectionListener.onConnectionStateChanged(isConnected));
    }

    // ==================== MENSAJES SALIENTES ====================

    public void sendJoin(String nickname) {
        send("{\"type\":\"JOIN\","
            + "\"nickname\":\"" + escapeJson(nickname) + "\","
            + "\"cat\":\"\"}");  // Enviamos vacío, el servidor asigna
        Gdx.app.log("WebSocketClient", "📤 JOIN: " + nickname);
    }

    public void sendLeave() {
        if (confirmedNickname != null && !confirmedNickname.isEmpty()) {
            String msg = "{\"type\":\"LEAVE\",\"nickname\":\""
                + escapeJson(confirmedNickname) + "\"}";
            if (socket != null && connected) {
                try {
                    socket.send(msg);
                    Gdx.app.log("WebSocketClient", "📤 LEAVE: " + confirmedNickname);
                } catch (Exception e) {
                    Gdx.app.error("WebSocketClient", "Error enviando LEAVE", e);
                }
            }
            confirmedNickname = null;
            confirmedCat = null;
        }
    }

    public void sendMove(String dir, float x, float y, String anim, int frame) {
        // El servidor añadirá automáticamente el nickname y cat al broadcast
        send("{\"type\":\"MOVE\","
            + "\"dir\":\"" + escapeJson(dir) + "\","
            + "\"x\":" + (int)x + ","
            + "\"y\":" + (int)y + ","
            + "\"anim\":\"" + escapeJson(anim) + "\","
            + "\"frame\":" + frame + "}");
    }

    public void sendGetPlayers() {
        send("{\"type\":\"GET_PLAYERS\"}");
    }

    public void sendResetPlayers() {
        send("{\"type\":\"RESET_PLAYERS\"}");
    }

    public void sendPing() {
        send("{\"type\":\"PING\"}");
    }

    private void send(String message) {
        if (socket != null && connected) {
            try {
                socket.send(message);
            } catch (Exception e) {
                Gdx.app.error("WebSocketClient", "Error enviando, encolando: " + message, e);
                pendingMessages.add(message);
            }
        } else {
            pendingMessages.add(message);
            Gdx.app.log("WebSocketClient", "Sin conexión, encolado: " + message);
            if (!reconnecting && !intentionallyClosed) {
                scheduleReconnect();
            }
        }
    }

    // ==================== UTILIDADES ====================

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    // ==================== CIERRE ====================

    public void close() {
        intentionallyClosed = true;
        reconnecting = false;
        connected = false;
        if (socket != null) {
            WebSockets.closeGracefully(socket);
            socket = null;
        }
    }
}
