package com.qualcomm.qti.snpedetector.helpers;

import android.graphics.Bitmap;
import android.util.Log;

import com.qualcomm.qti.snpedetector.MainActivity;

import java.nio.ByteBuffer;

class BitmapToFloatArrayHelper {
    private ByteBuffer mByteBufferHW4;
    private float[] mFloatBufferHW3;
    private boolean mIsFloatBufferBlack;

    /**
     * This will assume the geometry of both buffers from the first input bitmap.
     */
    void bitmapToBuffer(final Bitmap inputBitmap) {
        final int inputBitmapBytesSize = inputBitmap.getRowBytes() * inputBitmap.getHeight();
        if (mByteBufferHW4 == null || mByteBufferHW4.capacity() != inputBitmapBytesSize) {
            mByteBufferHW4 = ByteBuffer.allocate(inputBitmapBytesSize);
            mFloatBufferHW3 = new float[inputBitmap.getWidth() * inputBitmap.getHeight() * 3];
            Log.d(MainActivity.LOGTAG, "Reallocating input byte arrays");
        }
        mByteBufferHW4.rewind();
        inputBitmap.copyPixelsToBuffer(mByteBufferHW4);
    }

    /**
     * This will process pixels RGBA(0..255) to BGR(-1..1)
     */
    float[] bufferToNormalFloatsBGR() {
        // Pre-processing as per: https://confluence.qualcomm.com/confluence/display/ML/Preprocessing+for+Inference
        final byte[] inputArrayHW4 = mByteBufferHW4.array();
        final int area = mFloatBufferHW3.length / 3;
        long sumG = 0;
        int srcIdx = 0, dstIdx = 0;
        final float inputScale = 0.00784313771874f;
        for (int i = 0; i < area; i++) {
            // NOTE: the 0xFF a "cast" to unsigned int (otherwise it will be negative numbers for bright colors)
            final int pixelR = inputArrayHW4[srcIdx] & 0xFF;
            final int pixelG = inputArrayHW4[srcIdx + 1] & 0xFF;
            final int pixelB = inputArrayHW4[srcIdx + 2] & 0xFF;
            mFloatBufferHW3[dstIdx] = inputScale * (float) pixelB - 1;
            mFloatBufferHW3[dstIdx + 1] = inputScale * (float) pixelG - 1;
            mFloatBufferHW3[dstIdx + 2] = inputScale * (float) pixelR - 1;
            srcIdx += 4;
            dstIdx += 3;
            sumG += pixelG;
        }
        // the buffer is black if on average on average Green < 13/255 (aka: 5%)
        mIsFloatBufferBlack = sumG < (area * 13);
        return mFloatBufferHW3;
    }

    boolean isFloatBufferBlack() {
        return mIsFloatBufferBlack;
    }
}
