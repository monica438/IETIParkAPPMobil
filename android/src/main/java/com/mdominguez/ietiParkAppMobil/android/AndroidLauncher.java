package com.mdominguez.ietiParkAppMobil.android;

import com.github.czyzby.websocket.CommonWebSockets;

import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.mdominguez.ietiParkAppMobil.GameApp;


/** Launches the Android application. */
public class AndroidLauncher extends AndroidApplication {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        configuration.useImmersiveMode = true; // Recommended, but not required.
        CommonWebSockets.initiate();
        initialize(new GameApp(), configuration);
    }
}
