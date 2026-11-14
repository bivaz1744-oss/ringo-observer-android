package com.ringoai.sixone;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenOcrService extends Service {
    public static final String ACTION_NUMBER = "com.ringoai.sixone.OCR_NUMBER";
    public static final String ACTION_STOP = "com.ringoai.sixone.OCR_STOP";
    private static final String CHANNEL = "ringo_screen_reader";
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextRecognizer recognizer;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private int lastSent = -1;
    private long lastSentAt = 0;

    @Override public void onCreate() {
        super.onCreate();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "RINGO AI — чтение экрана", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null || !intent.hasExtra("data")) { stopSelf(); return START_NOT_STICKY; }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("RINGO AI читает экран")
                .setContentText("Открой Buleto. Распознанное число нужно подтвердить.")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(620, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(620, notification);
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));
        startCapture();
        return START_NOT_STICKY;
    }

    private void startCapture() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int density = getResources().getDisplayMetrics().densityDpi;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> {
            Image image = null;
            if (!processing.compareAndSet(false, true)) { try { source.acquireLatestImage().close(); } catch (Exception ignored) {} return; }
            try {
                image = source.acquireLatestImage();
                if (image == null) { processing.set(false); return; }
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
                int rowPadding = rowStride - pixelStride * width;
                Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                bitmap.recycle();
                InputImage input = InputImage.fromBitmap(cropped, 0);
                recognizer.process(input).addOnSuccessListener(this::handleText)
                    .addOnFailureListener(e -> { })
                    .addOnCompleteListener(t -> { cropped.recycle(); processing.set(false); });
            } catch (Exception e) { if (image != null) image.close(); processing.set(false); }
            finally { if (image != null) image.close(); }
        }, new Handler(Looper.getMainLooper()));
        display = projection.createVirtualDisplay("RingoOCR", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
    }

    private void handleText(Text text) {
        int candidate = -1;
        // Берём только отдельную строку с числом 0–36: это уменьшает ложные срабатывания на коэффициентах/счёте.
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText().trim().replaceAll("[^0-9]", "");
                if (value.matches("0|[1-9]|[12][0-9]|3[0-6]")) {
                    try { candidate = Integer.parseInt(value); } catch (Exception ignored) {}
                }
            }
        }
        long now = System.currentTimeMillis();
        if (candidate >= 0 && (candidate != lastSent || now - lastSentAt > 8000)) {
            lastSent = candidate; lastSentAt = now;
            Intent broadcast = new Intent(ACTION_NUMBER);
            broadcast.setPackage(getPackageName());
            broadcast.putExtra("number", candidate);
            sendBroadcast(broadcast);
        }
    }

    @Override public void onDestroy() {
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
