package com.traductorwa;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private TextView tvStatus;
    private Button btnAccessibility;
    private Button btnOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnAccessibility = findViewById(R.id.btn_enable_accessibility);
        btnOverlay = findViewById(R.id.btn_enable_overlay);

        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        boolean overlayEnabled = Settings.canDrawOverlays(this);

        if (accessibilityEnabled && overlayEnabled) {
            tvStatus.setText(getString(R.string.status_active));
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, getTheme()));
        } else {
            tvStatus.setText(getString(R.string.status_inactive));
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, getTheme()));
        }

        btnAccessibility.setEnabled(!accessibilityEnabled);
        btnOverlay.setEnabled(!overlayEnabled);

        if (accessibilityEnabled) {
            btnAccessibility.setText("Accesibilidad activa");
        }
        if (overlayEnabled) {
            btnOverlay.setText("Ventana flotante activa");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        new AlertDialog.Builder(this)
                .setTitle("Activar accesibilidad")
                .setMessage("En la siguiente pantalla, busca 'Traductor WA' y actívalo.")
                .setPositiveButton("Ir a configuración", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.overlay_permission_title))
                    .setMessage(getString(R.string.overlay_permission_msg))
                    .setPositiveButton("Permitir", (d, w) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            updateStatus();
        }
    }
}
