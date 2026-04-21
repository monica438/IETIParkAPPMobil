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
    private WebSocket socket; // Hacer instalación de websocket en el gradle
    private boolean connected = false;

    // Nick confirmado por el servidor tras el JOIN_OK
    private String confirmedNickname = null;

    // Lista de jugadores activos recibida del servidor
    private final List<String> activePlayers = new ArrayList<>();

    // Parser JSON de LibGDX
    private final JsonReader jsonReader = new JsonReader();

    // listener para notificar a las pantallas
    public interface PlayerListListener {
        void onPlayerListUpdated(List<String> players);
    }
    private PlayerListListener playerListListener;

    public void setPlayerListListener(PlayerListListener listener) {
        this.playerListListener = listener;
    }

    // conexión

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
            public boolean onClose(WebSocket webSocket, WebSocketCloseCode code, String reason) {
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

    // manejo de mensajes entrantes

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
                // El servidor confirma el nick definitivo (puede diferir del
                // enviado si había duplicado, el servidor añade sufijo numérico)
                confirmedNickname = root.getString("nickname", "");
                Gdx.app.log("WebSocketClient", "JOIN confirmado como: " + confirmedNickname);
                break;

            case "PLAYER_LIST":
                // "players" es un array JSON: ["nick1", "nick2", ...]
                // JsonValue.child apunta al primer elemento; .next itera el resto
                JsonValue playersArray = root.get("players");
                activePlayers.clear();
                if (playersArray != null) {
                    for (JsonValue item = playersArray.child; item != null; item = item.next) {
                        activePlayers.add(item.asString());
                    }
                }
                Gdx.app.log("WebSocketClient", "Lista actualizada: " + activePlayers);
                notifyPlayerList();
                break;

            case "MOVE":
                // Movimiento de otro jugador recibido desde el servidor
                // Por ahora solo log; aquí iría actualizar posición en GameScreen
                String nick      = root.getString("nickname",  "?");
                String direction = root.getString("direction", "?");
                Gdx.app.log("WebSocketClient", "MOVE de " + nick + ": " + direction);
                break;

            default:
                Gdx.app.log("WebSocketClient", "Tipo de mensaje desconocido: " + type);
                break;
        }
    }

    // Notifica al listener en el hilo de render de LibGDX.
    private void notifyPlayerList() {
        if (playerListListener == null) return;
        final List<String> snapshot = new ArrayList<>(activePlayers);
        Gdx.app.postRunnable(() -> playerListListener.onPlayerListUpdated(snapshot));
    }

    // mensajes salientes

    // Envía JOIN con el nickname del jugador.
    public void sendJoin(String nickname) {
        send("{\"type\":\"JOIN\",\"nickname\":\"" + nickname + "\"}");
    }

    // Envía un movimiento. direction debe ser UP, LEFT o RIGHT.
    public void sendMove(String direction) {
        long timestamp = System.currentTimeMillis();
        send("{\"type\":\"MOVE\","
            + "\"direction\":\"" + direction + "\","
            + "\"timestamp\":" + timestamp + "}");
    }

    // Solicita al servidor la lista de jugadores activa.
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

    // getters

    public boolean isConnected() {
        return connected;
    }

    // Nick confirmado por el servidor. Null hasta recibir JOIN_OK.
    public String getConfirmedNickname() {
        return confirmedNickname;
    }

    // Devuelve una copia de la lista para evitar modificaciones externas.
    public List<String> getActivePlayers() {
        return new ArrayList<>(activePlayers);
    }

    // cierre

    public void close() {
        if (socket != null) {
            WebSockets.closeGracefully(socket);
        }
    }
}
