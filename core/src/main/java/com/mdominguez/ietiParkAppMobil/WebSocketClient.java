package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.czyzby.websocket.WebSocket;
import com.github.czyzby.websocket.WebSocketAdapter;
import com.github.czyzby.websocket.WebSocketHandler;
import com.github.czyzby.websocket.WebSockets;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class WebSocketClient {

    // ==================== INTERFACES ====================

    public interface PlayerListListener {
        void onPlayerListUpdated(List<String> players);
    }

    public interface MessageListener {
        void onMessage(String type, JsonValue payload);
    }

    public interface MoveListener {
        void onMoveReceived(String nickname, String direction);
    }

    public interface ConnectionListener {
        void onConnectionStateChanged(ConnectionState state);
    }

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    // ==================== CAMPOS ====================

    private WebSocket socket;
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private String confirmedNickname = null;
    private final List<String> activePlayers = new ArrayList<>();
    private final JsonReader jsonReader = new JsonReader();

    private PlayerListListener playerListListener;
    private MessageListener messageListener;
    private MoveListener moveListener;
    private ConnectionListener connectionListener;

    // Reconexión
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000; // 1 segundo
    private static final long MAX_RECONNECT_DELAY_MS = 30000; // 30 segundos
    private int reconnectAttempts = 0;
    private long currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    private Timer reconnectTimer;
    private String pendingNickname = null; // Nickname para reenviar JOIN después de reconexión

    // URL del servidor
    private static final String SERVER_URL = "wss://pico2.ieti.site";

    // ==================== LISTENERS ====================

    public void setPlayerListListener(PlayerListListener listener) {
        this.playerListListener = listener;
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public void setMoveListener(MoveListener listener) {
        this.moveListener = listener;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    // ==================== CONEXIÓN Y RECONEXIÓN ====================

    public void connect() {
        if (connectionState == ConnectionState.CONNECTING ||
                connectionState == ConnectionState.CONNECTED) {
            return;
        }

        setConnectionState(ConnectionState.CONNECTING);
        doConnect();
    }

    private void doConnect() {
        try {
            socket = WebSockets.newSocket(SERVER_URL);
            socket.setSendGracefully(true);
            socket.addListener(new WebSocketAdapter() {

                @Override
                public boolean onOpen(WebSocket webSocket) {
                    Gdx.app.log("WebSocketClient", "Conectado al servidor");
                    setConnectionState(ConnectionState.CONNECTED);

                    // Resetear contadores de reconexión
                    reconnectAttempts = 0;
                    currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS;

                    // Si teníamos un nick pendiente, reenviar JOIN
                    if (pendingNickname != null && !pendingNickname.isEmpty()) {
                        Gdx.app.log("WebSocketClient", "Reenviando JOIN para: " + pendingNickname);
                        sendJoin(pendingNickname);
                    }

                    return WebSocketHandler.FULLY_HANDLED;
                }

                @Override
                public boolean onClose(WebSocket webSocket, int code, String reason) {
                    Gdx.app.log("WebSocketClient", "Desconectado: " + reason);

                    if (connectionState == ConnectionState.CONNECTED) {
                        // Desconexión inesperada - intentar reconectar
                        scheduleReconnect();
                    } else {
                        setConnectionState(ConnectionState.DISCONNECTED);
                    }

                    return WebSocketHandler.FULLY_HANDLED;
                }

                @Override
                public boolean onMessage(WebSocket webSocket, String payload) {
                    Gdx.app.log("WebSocketClient", "Recibido: " + payload);
                    handleMessage(payload);
                    return WebSocketHandler.FULLY_HANDLED;
                }

                @Override
                public boolean onError(WebSocket webSocket, Throwable error) {
                    Gdx.app.error("WebSocketClient", "Error: " + error.getMessage());
                    return WebSocketHandler.FULLY_HANDLED;
                }
            });

            socket.connect();
        } catch (Exception e) {
            Gdx.app.error("WebSocketClient", "Error al conectar: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Gdx.app.log("WebSocketClient", "Máximo de intentos de reconexión alcanzado");
            setConnectionState(ConnectionState.DISCONNECTED);
            return;
        }

        setConnectionState(ConnectionState.RECONNECTING);
        reconnectAttempts++;

        // Backoff exponencial con jitter
        long delay = Math.min(currentReconnectDelay, MAX_RECONNECT_DELAY_MS);
        currentReconnectDelay = (long)(currentReconnectDelay * 1.5);

        Gdx.app.log("WebSocketClient",
                String.format("Reconexión en %d ms (intento %d/%d)",
                        delay, reconnectAttempts, MAX_RECONNECT_ATTEMPTS));

        if (reconnectTimer != null) {
            reconnectTimer.cancel();
        }

        reconnectTimer = new Timer(true);
        reconnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Gdx.app.postRunnable(() -> {
                    doConnect();
                });
            }
        }, delay);
    }

    private void setConnectionState(ConnectionState state) {
        this.connectionState = state;
        Gdx.app.log("WebSocketClient", "Estado: " + state);

        if (connectionListener != null) {
            Gdx.app.postRunnable(() -> {
                connectionListener.onConnectionStateChanged(state);
            });
        }
    }

    public void disconnect() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer = null;
        }

        pendingNickname = null;

        if (socket != null) {
            WebSockets.closeGracefully(socket);
            socket = null;
        }

        setConnectionState(ConnectionState.DISCONNECTED);
    }

    // ==================== MANEJO DE MENSAJES ====================

    private void handleMessage(String payload) {
        JsonValue root;
        try {
            root = jsonReader.parse(payload);
        } catch (Exception e) {
            Gdx.app.error("WebSocketClient", "JSON inválido: " + payload, e);
            return;
        }

        String type = root.getString("type", "");

        switch (type) {
            case "WELCOME":
                Gdx.app.log("WebSocketClient", "Bienvenida: " + root.getString("msg", ""));
                notifyMessageListener(type, root);
                break;

            case "JOIN_OK":
                confirmedNickname = root.getString("nickname", "");
                pendingNickname = null; // JOIN confirmado
                Gdx.app.log("WebSocketClient", "JOIN confirmado como: " + confirmedNickname);
                notifyMessageListener(type, root);
                break;

            case "PLAYER_LIST":
                JsonValue playersArray = root.get("players");
                activePlayers.clear();
                if (playersArray != null) {
                    for (JsonValue item = playersArray.child; item != null; item = item.next) {
                        activePlayers.add(item.asString());
                    }
                }
                Gdx.app.log("WebSocketClient", "Lista actualizada: " + activePlayers.size() + " jugadores");
                notifyPlayerList();
                notifyMessageListener(type, root);
                break;

            case "MOVE":
                String nick = root.getString("nickname", "?");
                String direction = root.getString("direction", "?");
                Gdx.app.log("WebSocketClient", "MOVE de " + nick + ": " + direction);
                notifyMoveListener(nick, direction);
                notifyMessageListener(type, root);
                break;

            default:
                Gdx.app.log("WebSocketClient", "Tipo desconocido: " + type);
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
        final String payloadString = payload.toString();

        Gdx.app.postRunnable(() -> {
            try {
                JsonValue parsed = new JsonReader().parse(payloadString);
                messageListener.onMessage(typeCopy, parsed);
            } catch (Exception e) {
                Gdx.app.error("WebSocketClient", "Error notificando mensaje", e);
            }
        });
    }

    private void notifyMoveListener(String nickname, String direction) {
        if (moveListener == null) return;
        Gdx.app.postRunnable(() -> moveListener.onMoveReceived(nickname, direction));
    }

    // ==================== MENSAJES SALIENTES ====================

    public void sendJoin(String nickname) {
        pendingNickname = nickname;

        if (!isConnected()) {
            Gdx.app.log("WebSocketClient", "No conectado, JOIN pendiente para reconexión");
            return;
        }

        String escaped = escapeJson(nickname);
        send("{\"type\":\"JOIN\",\"nickname\":\"" + escaped + "\"}");
    }

    public void sendMove(String direction) {
        if (!isConnected()) {
            Gdx.app.log("WebSocketClient", "No conectado, MOVE descartado");
            return;
        }

        if (!direction.equals("UP") && !direction.equals("LEFT") && !direction.equals("RIGHT")) {
            Gdx.app.log("WebSocketClient", "Dirección inválida: " + direction);
            return;
        }

        long timestamp = System.currentTimeMillis();
        send("{\"type\":\"MOVE\",\"direction\":\"" + direction + "\",\"timestamp\":" + timestamp + "}");
    }

    public void sendGetPlayers() {
        if (!isConnected()) return;
        send("{\"type\":\"GET_PLAYERS\"}");
    }

    private void send(String message) {
        if (isConnected() && socket != null) {
            socket.send(message);
            Gdx.app.log("WebSocketClient", "Enviado: " + message);
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== GETTERS ====================

    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED &&
                socket != null &&
                socket.isOpen();
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public String getConfirmedNickname() {
        return confirmedNickname;
    }

    public List<String> getActivePlayers() {
        return new ArrayList<>(activePlayers);
    }

    // ==================== CIERRE ====================

    public void close() {
        disconnect();
    }
}
