package com.raiki.ray;

import android.os.Bundle;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(RayNative.class);
    super.onCreate(savedInstanceState);
    // Ray escucha mientras la app está abierta: la pantalla no se apaga sola
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
}
