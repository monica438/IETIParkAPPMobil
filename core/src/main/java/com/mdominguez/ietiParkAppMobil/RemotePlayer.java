package com.mdominguez.ietiParkAppMobil;

public class RemotePlayer {
    public String nickname;
    public float x, y;
    public String direction;
    public boolean flipX;
    public boolean tempFlag; // Para sincronización de lista

    public RemotePlayer(String nickname, float x, float y) {
        this.nickname = nickname;
        this.x = x;
        this.y = y;
        this.direction = "RIGHT";
        this.flipX = false;
        this.tempFlag = true;
    }
}

