package com.traductorwa;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslatorAccessibilityService extends AccessibilityService {

    private static final String TAG = "TranslatorWA";

    // WhatsApp package names
    private static final String WA_PACKAGE = "com.whatsapp";
    private static final String WA_BUSINESS_PACKAGE = "com.whatsapp.w4b";

    // WhatsApp input field resource IDs
    private static final String WA_INPUT_ID = "com.whatsapp:id/entry";
    private static final String WA_BUSINESS_INPUT_ID = "com.whatsapp.w4b:id/entry";

    // Translate API base
    private static final String TRANSLATE_BASE = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t";

    private TranslatorOverlay overlay;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // State tracking
    private String lastTranslatedMessage = "";
    private String lastInputText = "";
    private long lastInputChangeTime = 0;
    private static final long INPUT_DEBOUNCE_MS = 800;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        overlay = new TranslatorOverlay(this);
        overlay.setReplaceCallback(translatedText -> replaceWhatsAppInput(translatedText));
        Log.d(TAG, "Servicio de accesibilidad conectado");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.equals(WA_PACKAGE) && !packageName.equals(WA_BUSINESS_PACKAGE)) {
            return;
        }

        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleInputChanged(event, packageName);

        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowContentChanged(event, packageName);
        }
    }

    private void handleInputChanged(AccessibilityEvent event, String packageName) {
        CharSequence text = event.getText().isEmpty() ? null : event.getText().get(0);
        if (text == null || TextUtils.isEmpty(text)) {
            overlay.hide();
            lastInputText = "";
            return;
        }

        String inputText = text.toString().trim();
        if (inputText.equals(lastInputText)) return;

        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String viewId = source.getViewIdResourceName();
            source.recycle();
            if (viewId == null || (!viewId.equals(WA_INPUT_ID) && !viewId.equals(WA_BUSINESS_INPUT_ID))) {
                return;
            }
        }

        lastInputText = inputText;
        lastInputChangeTime = System.currentTimeMillis();

        mainHandler.postDelayed(() -> {
            if (System.currentTimeMillis() - lastInputChangeTime >= INPUT_DEBOUNCE_MS
                    && lastInputText.equals(inputText)
                    && inputText.length() >= 3) {
                triggerOutgoingTranslation(inputText);
            }
        }, INPUT_DEBOUNCE_MS);
    }

    private void triggerOutgoingTranslation(String spanishText) {
        overlay.showOutgoingTranslation(spanishText, "Traduciendo…");

        executor.submit(() -> {
            try {
                String detectedLang = detectLanguage(spanishText);
                if ("en".equals(detectedLang)) {
                    mainHandler.post(overlay::hide);
                    return;
                }

                String translated = translate(spanishText, "es", "en");
                if (!TextUtils.isEmpty(translated)) {
                    String humanized = humanizeEnglish(translated);
                    mainHandler.post(() -> overlay.updateTranslation(humanized));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error traduciendo saliente", e);
                mainHandler.post(overlay::hide);
            }
        });
    }

    private void handleWindowContentChanged(AccessibilityEvent event, String packageName) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            findAndTranslateMessages(root, packageName);
        } finally {
            root.recycle();
        }
    }

    private void findAndTranslateMessages(AccessibilityNodeInfo node, String packageName) {
        if (node == null) return;

        String msgBodyId = packageName + ":id/message_text";
        String msgContainerId = packageName + ":id/main_panel";

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;

            String resId = child.getViewIdResourceName();

            if (msgBodyId.equals(resId)) {
                CharSequence textCs = child.getText();
                if (textCs != null && !TextUtils.isEmpty(textCs)) {
                    String msgText = textCs.toString().trim();
                    if (!msgText.equals(lastTranslatedMessage) && msgText.length() >= 4) {
                        lastTranslatedMessage = msgText;
                        translateIncoming(msgText);
                    }
                }
            }

            findAndTranslateMessages(child, packageName);
            child.recycle();
        }
    }

    private void translateIncoming(String text) {
        executor.submit(() -> {
            try {
                String detectedLang = detectLanguage(text);
                if ("es".equals(detectedLang)) {
                    return;
                }

                mainHandler.post(() -> overlay.showIncomingTranslation(text, "Traduciendo…"));

                String translated = translate(text, "auto", "es");
                if (!TextUtils.isEmpty(translated)) {
                    mainHandler.post(() -> overlay.updateTranslation(translated));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error traduciendo entrante", e);
            }
        });
    }

    private void replaceWhatsAppInput(String newText) {
        executor.submit(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            try {
                AccessibilityNodeInfo inputNode = findInputNode(root);
                if (inputNode != null) {
                    Bundle selectAll = new Bundle();
                    selectAll.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                    selectAll.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                            inputNode.getText() != null ? inputNode.getText().length() : 0);
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll);

                    mainHandler.post(() -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("wa_translate", newText);
                        clipboard.setPrimaryClip(clip);
                    });

                    Thread.sleep(100);

                    inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    inputNode.recycle();

                    lastInputText = newText;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reemplazando texto", e);
            } finally {
                root.recycle();
            }
        });
    }

    private AccessibilityNodeInfo findInputNode(AccessibilityNodeInfo node) {
        if (node == null) return null;

        String resId = node.getViewIdResourceName();
        if (WA_INPUT_ID.equals(resId) || WA_BUSINESS_INPUT_ID.equals(resId)) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findInputNode(child);
            if (result != null) {
                if (child != result) child.recycle();
                return result;
            }
            if (child != null) child.recycle();
        }
        return null;
    }

    private String detectLanguage(String text) {
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            String urlStr = TRANSLATE_BASE + "&sl=auto&tl=en&q=" + encoded;
            String response = httpGet(urlStr);
            if (response == null) return "unknown";

            JSONArray json = new JSONArray(response);
            if (json.length() > 2 && !json.isNull(2)) {
                return json.getString(2);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detectando idioma", e);
        }
        return "unknown";
    }

    private String translate(String text, String sourceLang, String targetLang) {
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            String urlStr = TRANSLATE_BASE + "&sl=" + sourceLang + "&tl=" + targetLang + "&q=" + encoded;
            String response = httpGet(urlStr);
            if (response == null) return null;

            JSONArray json = new JSONArray(response);
            JSONArray segments = json.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray segment = segments.getJSONArray(i);
                if (!segment.isNull(0)) {
                    sb.append(segment.getString(0));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error en translate()", e);
            return null;
        }
    }

    private String humanizeEnglish(String text) {
        if (text == null) return "";
        return text
                .replace("I am going to", "I'm gonna")
                .replace("I am going", "I'm going")
                .replace("I am ", "I'm ")
                .replace("you are ", "you're ")
                .replace("we are ", "we're ")
                .replace("they are ", "they're ")
                .replace("he is ", "he's ")
                .replace("she is ", "she's ")
                .replace("it is ", "it's ")
                .replace("do not ", "don't ")
                .replace("does not ", "doesn't ")
                .replace("did not ", "didn't ")
                .replace("can not ", "can't ")
                .replace("cannot ", "can't ")
                .replace("will not ", "won't ")
                .replace("would not ", "wouldn't ")
                .replace("should not ", "shouldn't ")
                .replace("have not ", "haven't ")
                .replace("has not ", "hasn't ")
                .replace("I have ", "I've ")
                .replace("I will ", "I'll ")
                .replace("I would ", "I'd ")
                .replace("That is ", "That's ")
                .replace("That is,", "That's,")
                .replace("What is ", "What's ")
                .replace("There is ", "There's ")
                .replace("How are you doing?", "How's it going?")
                .replace("How are you?", "How are you doing?");
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HTTP error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void onInterrupt() {
        if (overlay != null) overlay.hide();
        Log.d(TAG, "Servicio interrumpido");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlay != null) overlay.hide();
        executor.shutdownNow();
    }
}
