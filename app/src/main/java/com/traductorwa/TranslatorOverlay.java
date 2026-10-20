package com.traductorwa;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class TranslatorOverlay {

    private final Context context;
    private final WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;
    private boolean isShowing = false;

    // For drag
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    // Callback when user wants to replace text in WhatsApp input
    public interface ReplaceTextCallback {
        void onReplace(String translatedText);
    }

    private ReplaceTextCallback replaceCallback;

    public TranslatorOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setReplaceCallback(ReplaceTextCallback cb) {
        this.replaceCallback = cb;
    }

    /**
     * Show overlay with incoming message translation (ES display).
     * hideReplaceButton = true means it's an incoming message — no replace button.
     */
    public void showIncomingTranslation(String original, String translated) {
        show(original, translated, false);
    }

    /**
     * Show overlay for outgoing (user typed in Spanish → translate to English).
     * Shows "REEMPLAZAR EN CHAT" button.
     */
    public void showOutgoingTranslation(String original, String translated) {
        show(original, translated, true);
    }

    private void show(String original, String translated, boolean showReplaceButton) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayView == null) {
                createOverlayView();
            }

            TextView tvOriginal = overlayView.findViewById(R.id.tv_original);
            TextView tvTranslation = overlayView.findViewById(R.id.tv_translation);
            TextView btnCopy = overlayView.findViewById(R.id.btn_copy);
            TextView btnReplace = overlayView.findViewById(R.id.btn_translate_to_en);
            TextView tvClose = overlayView.findViewById(R.id.tv_close);

            tvOriginal.setText(original);
            tvTranslation.setText(translated);

            btnReplace.setVisibility(showReplaceButton ? View.VISIBLE : View.GONE);

            btnCopy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Traducción", translated);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show();
            });

            btnReplace.setOnClickListener(v -> {
                if (replaceCallback != null) {
                    replaceCallback.onReplace(translated);
                }
                hide();
            });

            tvClose.setOnClickListener(v -> hide());

            if (!isShowing) {
                try {
                    windowManager.addView(overlayView, params);
                    isShowing = true;
                } catch (Exception e) {
                    // View already added
                }
            }
        });
    }

    public void updateTranslation(String translated) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayView != null && isShowing) {
                TextView tvTranslation = overlayView.findViewById(R.id.tv_translation);
                tvTranslation.setText(translated);
            }
        });
    }

    public void hide() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayView != null && isShowing) {
                try {
                    windowManager.removeView(overlayView);
                } catch (Exception ignored) {}
                isShowing = false;
                overlayView = null;
            }
        });
    }

    public boolean isShowing() {
        return isShowing;
    }

    private void createOverlayView() {
        LayoutInflater inflater = LayoutInflater.from(context);
        overlayView = inflater.inflate(R.layout.overlay_translation, null);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        // Enable drag
        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = initialX + (int)(event.getRawX() - initialTouchX);
                    params.y = initialY + (int)(event.getRawY() - initialTouchY);
                    if (isShowing) {
                        windowManager.updateViewLayout(overlayView, params);
                    }
                    return true;
            }
            return false;
        });
    }
}
