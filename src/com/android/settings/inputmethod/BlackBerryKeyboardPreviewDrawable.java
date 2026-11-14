/* Copyright (C) 2026 The LineageOS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.inputmethod;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Compact Key2 geometry. No PC number row or absent punctuation keys. */
final class BlackBerryKeyboardPreviewDrawable extends Drawable {
    private static final int[][] SCANS = {
        {16,17,18,19,20,21,22,23,24,25},
        {30,31,32,33,34,35,36,37,38,-1},
        {-2,44,45,46,47,48,49,50,5,-3},
        {-4,11,-5,-6,-7}
    };
    private static final float[] BOTTOM_WIDTHS = {1.25f,1.25f,3f,1.25f,1.25f};
    private final BlackBerryKeyboardPreview mPreview;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mBackground;
    private final int mForeground;
    private final int mAccent;
    private int mLayer;
    private int mAlpha = 255;

    BlackBerryKeyboardPreviewDrawable(Context context, BlackBerryKeyboardPreview preview) {
        mPreview = preview;
        TypedArray colors = context.obtainStyledAttributes(new int[] {
                android.R.attr.colorBackground, android.R.attr.textColorPrimary,
                android.R.attr.colorAccent});
        mBackground = colors.getColor(0, Color.WHITE);
        mForeground = colors.getColor(1, Color.BLACK);
        mAccent = colors.getColor(2, mForeground);
        colors.recycle();
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    int nextLayer() {
        mLayer = (mLayer + 1) % 4;
        invalidateSelf();
        return mLayer;
    }

    @Override public int getIntrinsicWidth() { return 1000; }
    @Override public int getIntrinsicHeight() { return 424; }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float unit = Math.min(bounds.width() / 10f, bounds.height() / 4.24f);
        float left = bounds.left + (bounds.width() - 10f * unit) / 2f;
        float top = bounds.top + (bounds.height() - 4.24f * unit) / 2f;
        float gap = unit * 0.045f;
        mPaint.setStyle(Paint.Style.FILL);
        color(mBackground);
        mRect.set(left, top, left + unit * 10f, top + unit * 4.24f);
        canvas.drawRoundRect(mRect, unit * 0.16f, unit * 0.16f, mPaint);
        for (int row = 0; row < SCANS.length; row++) {
            float x = left + (row == 3 ? unit : 0f);
            for (int col = 0; col < SCANS[row].length; col++) {
                int scan = SCANS[row][col];
                float width = unit * (row == 3 ? BOTTOM_WIDTHS[col] : 1f);
                float y = top + row * unit * 1.08f;
                mRect.set(x + gap, y + gap, x + width - gap, y + unit - gap);
                mPaint.setStyle(Paint.Style.FILL);
                color(blend(mBackground, mForeground, 0.09f));
                canvas.drawRoundRect(mRect, unit * 0.10f, unit * 0.10f, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(unit * 0.012f);
                color(blend(mBackground, mForeground, 0.32f));
                canvas.drawRoundRect(mRect, unit * 0.10f, unit * 0.10f, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
                color(scan < 0 ? mForeground : mAccent);
                if (scan == -7) {
                    float radius = unit * 0.025f;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        canvas.drawCircle(mRect.centerX() + dx * unit * 0.10f,
                                mRect.centerY() + dy * unit * 0.10f, radius, mPaint);
                    }
                } else if (scan == -5) {
                    mRect.set(x + width * .25f, y + unit * .62f,
                            x + width * .75f, y + unit * .68f);
                    canvas.drawRoundRect(mRect, gap, gap, mPaint);
                } else if (scan == -3) {
                    // Compact return arrow, approximately Latin capital height.
                    mPaint.setStrokeWidth(unit * .021f);
                    float right = x + width * .62f;
                    float tip = x + width * .37f;
                    float middle = y + unit * .525f;
                    canvas.drawLine(right, y + unit * .36f, right, middle, mPaint);
                    canvas.drawLine(right, middle, tip, middle, mPaint);
                    canvas.drawLine(tip, middle, x + width * .465f,
                            y + unit * .43f, mPaint);
                    canvas.drawLine(tip, middle, x + width * .465f,
                            y + unit * .62f, mPaint);
                } else {
                    String text;
                    switch (scan) {
                        case -1: text = "\u232b"; break;
                        case -2: text = "alt"; break;
                        case -4: text = "\u21e7"; break;
                        case -6: text = "sym"; break;
                        default: text = mPreview.glyph(scan, mLayer); break;
                    }
                    mPaint.setTextSize(unit * ((scan == -4)
                            ? .46f : (scan < 0 ? .28f : .40f)));
                    if (mPaint.measureText(text) > width * .78f && !text.isEmpty()) {
                        mPaint.setTextSize(mPaint.getTextSize() * width * .78f
                                / mPaint.measureText(text));
                    }
                    float baseline = y + unit * .5f - (mPaint.ascent() + mPaint.descent()) / 2f;
                    // The model already selected any RTL mirrored glyph. Draw
                    // this isolated legend LTR to avoid mirroring it again.
                    canvas.drawTextRun(text, 0, text.length(), 0, text.length(),
                            x + width / 2f, baseline, false, mPaint);
                }
                x += width;
            }
        }
    }

    private void color(int color) {
        mPaint.setColor(color);
        mPaint.setAlpha(Color.alpha(color) * mAlpha / 255);
    }

    private static int blend(int background, int foreground, float amount) {
        return Color.rgb((int)(Color.red(background) * (1f-amount) + Color.red(foreground)*amount),
                (int)(Color.green(background)*(1f-amount) + Color.green(foreground)*amount),
                (int)(Color.blue(background)*(1f-amount) + Color.blue(foreground)*amount));
    }

    @Override public void setAlpha(int alpha) { mAlpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) {
        mPaint.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
