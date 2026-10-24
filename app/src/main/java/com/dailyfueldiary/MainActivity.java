package com.dailyfueldiary;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK_EXPORT = 100, PICK_IMPORT = 101, PICK_PHOTO = 102;
    private static final String PREFS = "dailyfuel_secure", KEY_CIPHER = "openai_key_cipher", KEY_ALIAS = "dailyfuel_openai_aes_v1";
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private WebView web;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("dailyfuel", "Daily food reminder", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Reminder to log your meals");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    public class Bridge {
        @JavascriptInterface public void setReminder(int hour, int minute, boolean enabled) {
            getSharedPreferences("dailyfuel", MODE_PRIVATE).edit().putBoolean("reminder_enabled", enabled).putInt("reminder_hour", hour).putInt("reminder_minute", minute).apply();
            if (!enabled) {
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, 42, new Intent(MainActivity.this, ReminderReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                ((AlarmManager)getSystemService(ALARM_SERVICE)).cancel(pi); return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8));
            }
            AlarmManager am = (AlarmManager)getSystemService(ALARM_SERVICE);
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
            Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, hour))); c.set(Calendar.MINUTE, Math.max(0, Math.min(59, minute))); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
            Intent i = new Intent(MainActivity.this, ReminderReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, 42, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi); else am.setExact(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        }

        @JavascriptInterface public void exportBackup(String json) {
            getPreferences(MODE_PRIVATE).edit().putString("pending_export", json).apply();
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); i.putExtra(Intent.EXTRA_TITLE, "daily-fuel-backup.json"); startActivityForResult(i, PICK_EXPORT);
            });
        }

        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_IMPORT);
            });
        }

        @JavascriptInterface public boolean hasAiKey() {
            try { return !readAiKey().isEmpty(); } catch (Exception e) { return false; }
        }

        @JavascriptInterface public void saveAiKey(String rawKey) {
            String key = rawKey == null ? "" : rawKey.trim();
            if (key.length() > 256) { sendKeyResult(false, "API key is too long."); return; }
            try {
                if (key.isEmpty()) getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_CIPHER).apply();
                else getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CIPHER, encryptKey(key)).apply();
                sendKeyResult(true, key.isEmpty() ? "Encrypted API key removed from this phone." : "API key saved encrypted on this phone.");
            } catch (Exception e) { sendKeyResult(false, "Could not encrypt the API key on this phone."); }
        }

        @JavascriptInterface public void chooseMealPhoto() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(i, PICK_PHOTO);
                } catch (Exception e) { Toast.makeText(MainActivity.this, "Could not open the photo picker", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface public void analyzeFood(String description, String imageData, String requestId, String model) {
            String text = description == null ? "" : description.trim();
            String photo = imageData == null ? "" : imageData;
            String id = requestId == null ? "" : requestId.substring(0, Math.min(100, requestId.length()));
            String selectedModel = model != null && model.matches("[A-Za-z0-9._-]{1,100}") ? model : "gpt-4o-mini";
            if (text.length() > 3000) { sendAiError(id, "Food description is too long. Keep it under 3,000 characters."); return; }
            if (photo.length() > 2200000 || (!photo.isEmpty() && !photo.matches("(?s)^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$"))) { sendAiError(id, "The selected photo is too large or has an unsupported format."); return; }
            IO.execute(() -> {
                try {
                    String key = readAiKey();
                    if (key.isEmpty()) throw new AiException("No API key is saved. Add one in Settings.");
                    JSONObject result = requestEstimate(key, selectedModel, text, photo);
                    sendAiSuccess(id, result);
                } catch (AiException e) { sendAiError(id, e.getMessage()); }
                catch (java.net.SocketTimeoutException e) { sendAiError(id, "The AI request timed out. Check your connection and try again."); }
                catch (java.io.IOException e) { sendAiError(id, "Could not reach the AI service. Check your internet connection and try again."); }
                catch (Exception e) { sendAiError(id, "The AI response could not be read. Nothing was saved."); }
            });
        }
    }

    private void sendKeyResult(boolean ok, String message) {
        if (web == null) return;
        String js = "window.onApiKeySaved && window.onApiKeySaved(" + ok + "," + JSONObject.quote(message) + ");";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private String encryptKey(String value) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        SecretKey secret = (SecretKey)store.getKey(KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String readAiKey() throws Exception {
        String packed = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CIPHER, "");
        if (packed.isEmpty()) return "";
        String[] parts = packed.split("\\.", 2); if (parts.length != 2) throw new Exception("Invalid encrypted key");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey secret = (SecretKey)store.getKey(KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private JSONObject requestEstimate(String key, String model, String description, String photo) throws Exception {
        JSONObject body = new JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", 1200).put("response_format", responseFormat());
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "Estimate nutrition for the food portion described by the user. Use only the supplied food description and optional meal photo. Do not infer anything about the person. Return calories in kcal and protein, carbohydrates, fat and fibre in grams for the total portion described. Identify likely foods and serving sizes; account for explicit quantities. Use realistic approximate values and state uncertainty in notes. If food identity or portion is unclear, return questions and avoid inventing a precise estimate. Never claim medical accuracy."));
        JSONObject user = new JSONObject().put("role", "user");
        if (photo.isEmpty()) user.put("content", description);
        else {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", description.isEmpty() ? "Identify foods in this meal photo and estimate visible serving sizes." : description));
            parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", photo).put("detail", "auto")));
            user.put("content", parts);
        }
        messages.put(user); body.put("messages", messages);
        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = (HttpsURLConnection)new URL(API_URL).openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(20000); connection.setReadTimeout(45000); connection.setUseCaches(false); connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setRequestProperty("Accept", "application/json");
        connection.setFixedLengthStreamingMode(request.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(request); }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new AiException("The API key was rejected. Check it in Settings.");
            if (status == 429) throw new AiException("The AI account reached a rate or usage limit. Check your OpenAI account and try again later.");
            if (status < 200 || status >= 300) throw new AiException(status >= 500 ? "The AI service is temporarily unavailable." : "The AI request was rejected. Check the model setting and photo, then try again.");
            String raw = readLimited(connection.getInputStream(), 1500000);
            JSONObject response = new JSONObject(raw);
            JSONArray choices = response.optJSONArray("choices"); if (choices == null || choices.length() == 0) throw new AiException("The AI service returned no estimate.");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message"); if (message == null) throw new AiException("The AI service returned no estimate.");
            if (!message.optString("refusal", "").isEmpty()) throw new AiException("The model could not identify this meal. Try a clearer description or photo.");
            String content = message.optString("content", ""); if (content.isEmpty()) throw new AiException("The AI service returned no estimate.");
            return sanitizeEstimate(new JSONObject(content));
        } finally { connection.disconnect(); }
    }

    private JSONObject responseFormat() throws Exception {
        JSONObject itemProperties = new JSONObject()
            .put("name", new JSONObject().put("type", "string"))
            .put("serving", new JSONObject().put("type", "string"))
            .put("k", new JSONObject().put("type", "number"))
            .put("p", new JSONObject().put("type", "number"))
            .put("c", new JSONObject().put("type", "number"))
            .put("f", new JSONObject().put("type", "number"))
            .put("fi", new JSONObject().put("type", "number"))
            .put("confidence", new JSONObject().put("type", "number"))
            .put("notes", new JSONObject().put("type", "string"));
        JSONArray itemRequired = new JSONArray().put("name").put("serving").put("k").put("p").put("c").put("f").put("fi").put("confidence").put("notes");
        JSONObject itemSchema = new JSONObject().put("type", "object").put("additionalProperties", false).put("properties", itemProperties).put("required", itemRequired);
        JSONObject schema = new JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", new JSONObject().put("items", new JSONObject().put("type", "array").put("items", itemSchema)).put("questions", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))))
            .put("required", new JSONArray().put("items").put("questions"));
        return new JSONObject().put("type", "json_schema").put("json_schema", new JSONObject().put("name", "food_estimate").put("strict", true).put("schema", schema));
    }

    private JSONObject sanitizeEstimate(JSONObject raw) throws Exception {
        JSONArray items = raw.optJSONArray("items"), questions = raw.optJSONArray("questions");
        if (items == null || items.length() > 20) throw new AiException("The estimate contained too many items. Try a shorter description.");
        JSONArray cleanItems = new JSONArray();
        String[] numeric = {"k", "p", "c", "f", "fi", "confidence"};
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) throw new AiException("The estimate was incomplete. Nothing was saved.");
            String name = item.optString("name", "").trim(), serving = item.optString("serving", "").trim();
            if (name.isEmpty() || name.length() > 120 || serving.isEmpty() || serving.length() > 120) throw new AiException("The estimate was incomplete. Nothing was saved.");
            JSONObject clean = new JSONObject().put("name", name).put("serving", serving).put("notes", item.optString("notes", "").substring(0, Math.min(300, item.optString("notes", "").length())));
            for (String field : numeric) {
                Object value = item.opt(field); if (!(value instanceof Number)) throw new AiException("The estimate contained invalid nutrition values. Nothing was saved.");
                double number = ((Number)value).doubleValue(); if (Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > (field.equals("confidence") ? 1 : 100000)) throw new AiException("The estimate contained invalid nutrition values. Nothing was saved.");
                clean.put(field, number);
            }
            cleanItems.put(clean);
        }
        JSONArray cleanQuestions = new JSONArray();
        if (questions != null) for (int i = 0; i < Math.min(6, questions.length()); i++) { Object q = questions.opt(i); if (q instanceof String && !((String)q).trim().isEmpty()) cleanQuestions.put(((String)q).trim().substring(0, Math.min(300, ((String)q).trim().length()))); }
        return new JSONObject().put("items", cleanItems).put("questions", cleanQuestions);
    }

    private String readLimited(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, count;
            while ((count = in.read(buffer)) != -1) { total += count; if (total > maxBytes) throw new IOException("Response too large"); out.write(buffer, 0, count); }
            return out.toString("UTF-8");
        }
    }

    private void sendAiSuccess(String requestId, JSONObject result) {
        JSONObject payload = new JSONObject();
        try { payload.put("ok", true).put("requestId", requestId).put("result", result); } catch (Exception ignored) { sendAiError(requestId, "Could not prepare the estimate."); return; }
        sendAiPayload(payload);
    }

    private void sendAiError(String requestId, String message) {
        JSONObject payload = new JSONObject();
        try { payload.put("ok", false).put("requestId", requestId).put("error", message); } catch (Exception ignored) { return; }
        sendAiPayload(payload);
    }

    private void sendAiPayload(JSONObject payload) {
        if (web == null) return;
        String js = "window.onAIResult && window.onAIResult(" + JSONObject.quote(payload.toString()) + ");";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private String compressPhoto(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");
        int sample = 1; while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 1600) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) throw new IOException("Could not decode image");
        try {
            int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (max > 1280) { float scale = 1280f / max; Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true); if (smaller != bitmap) bitmap.recycle(); bitmap = smaller; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 78, bytes)) throw new IOException("Could not encode image");
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } finally { if (!bitmap.isRecycled()) bitmap.recycle(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PHOTO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri photo = data.getData();
            IO.execute(() -> {
                try {
                    String dataUrl = compressPhoto(photo), js = "window.receiveMealPhoto && window.receiveMealPhoto(" + JSONObject.quote(dataUrl) + ");";
                    runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not prepare that photo. Choose another image.", Toast.LENGTH_LONG).show()); }
            });
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            if (requestCode == PICK_EXPORT) {
                String json = getPreferences(MODE_PRIVATE).getString("pending_export", "{}");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                Toast.makeText(this, "Backup exported", Toast.LENGTH_SHORT).show();
            } else if (requestCode == PICK_IMPORT) {
                StringBuilder sb = new StringBuilder();
                try (InputStream in = getContentResolver().openInputStream(uri); BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) { String line; while ((line = r.readLine()) != null) sb.append(line); }
                web.evaluateJavascript("window.importBackupFromNative(" + JSONObject.quote(sb.toString()) + ")", null);
                Toast.makeText(this, "Backup imported", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) { Toast.makeText(this, "Could not process backup: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    private static class AiException extends Exception { AiException(String message) { super(message); } }
}