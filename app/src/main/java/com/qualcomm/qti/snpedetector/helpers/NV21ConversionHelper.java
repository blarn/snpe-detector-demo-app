package com.qualcomm.qti.snpedetector.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.util.Log;

import com.qualcomm.qti.snpedetector.MainActivity;

/**
 * NV21->RGB conversion helpers, using Renderscript
 */
public class NV21ConversionHelper {
    private final android.renderscript.RenderScript mRenderScript;
    private final android.renderscript.ScriptIntrinsicYuvToRGB mNV21ToRgbIntrinsic;
    private android.renderscript.Allocation mNV21InAllocation;
    private android.renderscript.Allocation mNV21OutAllocation;
    private Bitmap mNV21ConvertedBitmap;

    public NV21ConversionHelper(Context context) {
        mRenderScript = RenderScript.create(context);
        mNV21ToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(mRenderScript, Element.U8_4(mRenderScript));
    }

    public Bitmap convert(final byte[] nv21bytes, int width, int height) {
        final int nv21length = nv21bytes.length;

        // allocate/reallocate objects and bitmaps if sizes change
        if (mNV21InAllocation == null || mNV21InAllocation.getBytesSize() != nv21length ||
                mNV21ConvertedBitmap == null || mNV21ConvertedBitmap.getWidth() != width ||
                mNV21ConvertedBitmap.getHeight() != height) {
            // update in/out buffers and target bitmap for NV21 bytes -> RGBA bitmap conversion
            mNV21InAllocation = Allocation.createSized(mRenderScript, Element.U8(mRenderScript), nv21length);
            mNV21ConvertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mNV21OutAllocation = Allocation.createFromBitmap(mRenderScript, mNV21ConvertedBitmap);
            mNV21ToRgbIntrinsic.setInput(mNV21InAllocation);
            Log.d(MainActivity.LOGTAG, "Reallocating NV21->RGB conversion buffers");
        }

        // hot loop: copy data into the input, process with renderscript kernels, copy the output
        mNV21InAllocation.copyFromUnchecked(nv21bytes);
        mNV21ToRgbIntrinsic.forEach(mNV21OutAllocation);
        mNV21OutAllocation.copyTo(mNV21ConvertedBitmap);

        return mNV21ConvertedBitmap;
    }

    private void cleanupData() {
        if (mNV21InAllocation == null)
            return;
        mNV21InAllocation.destroy();
        mNV21InAllocation = null;
        mNV21OutAllocation.destroy();
        mNV21OutAllocation = null;
        mNV21ConvertedBitmap = null;
    }
}
