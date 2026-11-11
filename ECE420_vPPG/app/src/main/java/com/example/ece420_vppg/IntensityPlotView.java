package com.example.ece420_vppg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;

public class IntensityPlotView extends View{

    // at 30FPS, we want to plot 20 seconds
    private static final int BUFFER_SIZE = 600;
    private static final int NUM_ROIS = 5;

    // 5 buffers with 600 samples each
    private final double[][] samples = new double[5][BUFFER_SIZE];

    private int writeIndex = 0;
    private int sampleCount = 0;

    // different color for each ROI
    private final int[] colors = {
            Color.rgb(220,160,0),       // ROI 1, yellow
            Color.rgb(0,0,255),       // ROI 2, blue
            Color.rgb(255,0,0),       // ROI 3, red
            Color.rgb(255,0,255),      // ROI 4, purple
            Color.rgb(0,255,0)        // ROI 5, green
    };

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public IntensityPlotView(Context context, AttributeSet attrs){
        super(context, attrs);

        linePaint.setStrokeWidth(2.5f);
        linePaint.setStyle(Paint.Style.STROKE);

        textPaint.setTextSize(22f);
        textPaint.setStyle(Paint.Style.FILL);
    }

    // add the new weighted RGB intensity sample
    public void addSample(double[] averages){

        // check if we fed an average array that is full
        if (averages.length != NUM_ROIS){
            return;
        }

        // store each ROI sample at the same index
        for (int i = 0; i < NUM_ROIS; i++){
            samples[i][writeIndex] = averages[i];
        }

        // advance the circular buffer index
        writeIndex = (writeIndex + 1) % BUFFER_SIZE;

        if (sampleCount < BUFFER_SIZE){
            sampleCount++;
        }

        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);

        if (sampleCount < 2){
            return;
        }

        int width = getWidth();
        int height = getHeight();

        float padding = 16f;

        // find min/max for automatic vertical scaling for all ROI signals
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int roi = 0; roi < NUM_ROIS; roi++){
            for (int i = 0; i < sampleCount; i++){
                min = Math.min(min, samples[roi][i]);
                max = Math.max(max, samples[roi][i]);
            }
        }

        // prevent divide by zero
        if (max - min < 1e-6){
            max = min + 1.0;
        }

        int oldestIndex = (sampleCount == BUFFER_SIZE) ? writeIndex : 0;

        // draw each ROI waveform
        for (int roi = 0; roi < NUM_ROIS; roi++){

            linePaint.setColor(colors[roi]);

            Path path = new Path();

            for (int i = 0; i < sampleCount; i++){

                int index = (oldestIndex + i) % BUFFER_SIZE;
                double intensity = samples[roi][index];

                float x = padding + (float) i / (BUFFER_SIZE - 1) * (width - 2 * padding);
                float y = height - padding - (float) ((intensity - min) / (max - min)) * (height - 2 * padding);

                if (i == 0){
                    path.moveTo(x,y);
                } else {
                    path.lineTo(x,y);
                }
            }

            canvas.drawPath(path, linePaint);
        }

        // create color legend
        for (int roi = 0; roi < NUM_ROIS; roi++){

            textPaint.setColor(colors[roi]);

            canvas.drawText(
                    "ROI " + (roi + 1),
                    padding + roi * (width - 2 * padding) / NUM_ROIS,
                    30f,
                    textPaint
            );
        }
    }
}
