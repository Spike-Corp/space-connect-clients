package com.limelight.grid.assets;

import android.annotation.TargetApi;
import android.graphics.ImageDecoder;
import android.os.Build;

import java.io.File;
import java.io.IOException;

// This class must be kept separate from DiskAssetLoader. Referencing ImageDecoder APIs
// (even inside a runtime SDK_INT check) from an anonymous inner class defined directly inside
// DiskAssetLoader causes ART on pre-P devices to throw NoClassDefFoundError when DiskAssetLoader
// is loaded, because verification of the enclosing class also tries to resolve the anonymous
// class's "implements ImageDecoder.OnHeaderDecodedListener" type. Isolating it here means this
// class (and its ImageDecoder usage) is only ever loaded/verified when actually invoked, which
// only happens on API 28+.
@TargetApi(Build.VERSION_CODES.P)
class ImageDecoderCompat {
    static ScaledBitmap decode(File file, final int targetWidth, final int targetHeight, final boolean isLowRamDevice) throws IOException {
        final ScaledBitmap scaledBitmap = new ScaledBitmap();
        scaledBitmap.bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder imageDecoder, ImageDecoder.ImageInfo imageInfo, ImageDecoder.Source source) {
                scaledBitmap.originalWidth = imageInfo.getSize().getWidth();
                scaledBitmap.originalHeight = imageInfo.getSize().getHeight();

                imageDecoder.setTargetSize(targetWidth, targetHeight);
                if (isLowRamDevice) {
                    imageDecoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                }
            }
        });
        return scaledBitmap;
    }
}
