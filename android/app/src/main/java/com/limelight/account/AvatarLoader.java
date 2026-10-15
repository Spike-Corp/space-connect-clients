package com.limelight.account;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class AvatarLoader {
    private static final String TAG = "AvatarLoader";
    private static final int DEFAULT_SIZE_DP = 48;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024L / 16L)) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };

    private AvatarLoader() {
    }

    static void load(ImageView view, SpaceConnectApiClient.PublicProfile profile) {
        int size = view.getLayoutParams() != null
                ? Math.max(view.getLayoutParams().width, view.getLayoutParams().height)
                : 0;
        if (size <= 0) {
            size = Math.round(DEFAULT_SIZE_DP * view.getResources().getDisplayMetrics().density);
        }
        final int avatarSize = size;

        String identity = profileName(profile);
        String avatarUrl = profile != null && profile.avatarUrl != null ? profile.avatarUrl.trim() : "";
        String requestTag = avatarUrl + "|" + identity;
        view.setTag(requestTag);
        view.setContentDescription(identity);
        view.setImageBitmap(fallback(identity, avatarSize));

        if (avatarUrl.isEmpty()) {
            return;
        }

        Bitmap cached = CACHE.get(avatarUrl);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        final Request request;
        try {
            request = new Request.Builder().url(avatarUrl).get().build();
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "URL de avatar inválida", error);
            return;
        }

        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                Log.w(TAG, "Falha ao carregar avatar", error);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.w(TAG, "Avatar respondeu HTTP " + response.code());
                        return;
                    }
                    Bitmap decoded = BitmapFactory.decodeStream(response.body().byteStream());
                    if (decoded == null) {
                        Log.w(TAG, "Avatar recebido não é uma imagem válida");
                        return;
                    }
                    Bitmap rounded = circleCrop(decoded, avatarSize);
                    CACHE.put(avatarUrl, rounded);
                    view.post(() -> {
                        if (requestTag.equals(view.getTag())) {
                            view.setImageBitmap(rounded);
                        }
                    });
                }
            }
        });
    }

    private static Bitmap fallback(String identity, int size) {
        String key = "fallback:" + identity + ":" + size;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, size, size,
                Color.rgb(91, 140, 255), Color.rgb(155, 92, 255), Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);

        paint.setShader(null);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(size * 0.34f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(initials(identity), size / 2f, baseline, paint);

        CACHE.put(key, bitmap);
        return bitmap;
    }

    private static Bitmap circleCrop(Bitmap source, int size) {
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        Rect sourceRect = new Rect(left, top, left + side, top + side);
        RectF targetRect = new RectF(0, 0, size, size);

        canvas.save();
        canvas.clipPath(circlePath(size));
        canvas.drawBitmap(source, sourceRect, targetRect, paint);
        canvas.restore();
        return output;
    }

    private static android.graphics.Path circlePath(int size) {
        android.graphics.Path path = new android.graphics.Path();
        path.addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW);
        return path;
    }

    private static String profileName(SpaceConnectApiClient.PublicProfile profile) {
        if (profile == null) {
            return "?";
        }
        if (!TextUtils.isEmpty(profile.name)) {
            return profile.name.trim();
        }
        if (!TextUtils.isEmpty(profile.username)) {
            return "@" + profile.username.trim();
        }
        return "?";
    }

    private static String initials(String value) {
        String clean = value == null ? "" : value.replace("@", "").trim();
        if (clean.isEmpty()) {
            return "?";
        }
        String[] parts = clean.split("\\s+");
        String result = parts.length > 1
                ? parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)
                : parts[0].substring(0, Math.min(2, parts[0].length()));
        return result.toUpperCase(Locale.ROOT);
    }
}
