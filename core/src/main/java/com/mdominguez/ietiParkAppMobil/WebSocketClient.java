package com.mdominguez.ietiParkAppMobil;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.czyzby.websocket.WebSocket;
import com.github.czyzby.websocket.WebSocketAdapter;
import com.github.czyzby.websocket.WebSocketHandler;
import com.github.czyzby.websocket.WebSockets;
import com.github.czyzby.websocket.data.WebSocketCloseCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Cliente → Servidor:
 *   { "type": "JOIN",        "nickname": "xxx" }
 *   { "type": "MOVE",        "direction": "UP|LEFT|RIGHT", "timestamp": 123 }
 *   { "type": "GET_PLAYERS" }
 *
 * Servidor → Cliente:
 *   { "type": "WELCOME",     "msg": "..." }
 *   { "type": "JOIN_OK",     "nickname": "xxx" }
 *   { "type": "PLAYER_LIST", "players": ["p1","p2"] }
 *   { "type": "MOVE",        "nickname": "xxx", "direction": "UP", ... }
 */
public class WebSocketClient {

    // ==================== INTERFACES ====================

    /**
     * Listener para notificar cambios en la lista de jugadores.
     */
    public interface PlayerListListener {
        void onPlayerListUpdated(List<String> players);
    }

    /**
     * Listener genérico para mensajes del servidor.
     * Permite que cualquier pantalla reciba mensajes WebSocket.
     */
    public interface MessageListener {
        void onMessage(String type, JsonValue payload);
    }

    // ==================== CAMPOS ====================

    private WebSocket socket;
    private boolean connected = false;
    private String confirmedNickname = null;
    private final List<String> activePlayers = new ArrayList<>();
    private final JsonReader jsonReader = new JsonReader();

    private PlayerListListener playerListListener;
    private MessageListener messageListener;

    // ==================== LISTENERS ====================

    public void setPlayerListListener(PlayerListListener listener) {
        this.playerListListener = listener;
    }

    // Setter para el listener de mensajes
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    // ==================== CONEXIÓN ====================

    public void connect() {
        socket = WebSockets.newSocket("wss://pico2.ieti.site");
        socket.setSendGracefully(true);

        socket.addListener(new WebSocketAdapter() {

            @Override
            public boolean onOpen(WebSocket webSocket) {
                connected = true;
                Gdx.app.log("WebSocketClient", "Conectado al servidor");
                return WebSocketHandler.FULLY_HANDLED;
            }

            @Override
            public boolean onClose(WebSocket webSocket, int code, String reason) {
                connected = false;
                Gdx.app.log("WebSocketClient", "Desconectado: " + reason);
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
    }

    // ==================== MANEJO DE MENSAJES ====================

    private void handleMessage(String payload) {
        JsonValue root;
        try {
            root = jsonReader.parse(payload);
        } catch (Exception e) {
            Gdx.app.error("WebSocketClient", "JSON inválido: " + payload);
            return;
        }

        String type = root.getString("type", "");

        switch (type) {
            case "WELCOME":
                Gdx.app.log("WebSocketClient", "Bienvenida: " + root.getString("msg", ""));
                break;

            case "JOIN_OK":
                confirmedNickname = root.getString("nickname", "");
                Gdx.app.log("WebSocketClient", "JOIN confirmado como: " + confirmedNickname);
                // Notificar al listener genérico
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
                Gdx.app.log("WebSocketClient", "Lista actualizada: " + activePlayers);
                notifyPlayerList();
                // También notificar al listener genérico
                notifyMessageListener(type, root);
                break;

            case "MOVE":
                String nick = root.getString("nickname", "?");
                String direction = root.getString("direction", "?");
                Gdx.app.log("WebSocketClient", "MOVE de " + nick + ": " + direction);
                // Notificar al listener genérico
                notifyMessageListener(type, root);
                break;

            default:
                Gdx.app.log("WebSocketClient", "Tipo de mensaje desconocido: " + type);
                break;
        }
    }

    // ==================== NOTIFICACIONES ====================

    private void notifyPlayerList() {
        if (playerListListener == null) return;
        final List<String> snapshot = new ArrayList<>(activePlayers);
        Gdx.app.postRunnable(() -> playerListListener.onPlayerListUpdated(snapshot));
    }

    // Función para notificar al listener genérico
    private void notifyMessageListener(String type, JsonValue payload) {
        if (messageListener == null) return;
        // Creamos una copia del payload para evitar problemas de threading
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

    // ==================== MENSAJES SALIENTES ====================

    public void sendJoin(String nickname) {
        send("{\"type\":\"JOIN\",\"nickname\":\"" + escapeJson(nickname) + "\"}");
    }

    public void sendMove(String direction) {
        long timestamp = System.currentTimeMillis();
        send("{\"type\":\"MOVE\","
                + "\"direction\":\"" + direction + "\","
                + "\"timestamp\":" + timestamp + "}");
    }

    public void sendGetPlayers() {
        send("{\"type\":\"GET_PLAYERS\"}");
    }

    private void send(String message) {
        if (connected && socket != null) {
            socket.send(message);
            Gdx.app.log("WebSocketClient", "Enviado: " + message);
        } else {
            Gdx.app.log("WebSocketClient", "Sin conexión, mensaje descartado: " + message);
        }
    }

    // Escapar strings para JSON
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
        return connected;
    }

    public String getConfirmedNickname() {
        return confirmedNickname;
    }

    public List<String> getActivePlayers() {
        return new ArrayList<>(activePlayers);
    }

    // ==================== CIERRE ====================

    public void close() {
        if (socket != null) {
            WebSockets.closeGracefully(socket);
        }
        connected = false;
    }
}
