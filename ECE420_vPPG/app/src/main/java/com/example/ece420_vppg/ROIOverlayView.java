package com.example.ece420_vppg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class ROIOverlayView extends View{

    private final Paint roiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ROIOverlayView(Context context, AttributeSet attrs){
        super(context, attrs);

        // ROI square
        roiPaint.setColor(Color.GREEN);
        roiPaint.setStrokeWidth(3f);
        roiPaint.setStyle(Paint.Style.STROKE);

        // ROI label
        textPaint.setColor(Color.GREEN);
        textPaint.setTextSize(28f);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setFakeBoldText(true);
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();

        // intial display size in pixels
        float roiSize = 20f;
        float half = roiSize / 2f;

        // positions relative to the live video feed
        float[][] positions = {
                {0.50f, 0.50f}, // ROI 1, center
                {0.15f, 0.15f}, // ROI 2, top-left
                {0.85f, 0.15f}, // ROI 3, top-right
                {0.15f, 0.85f}, // ROI 4, bottom-left
                {0.85f, 0.85f}, // ROI 5, bottom-right
        };

        for (int i = 0; i < positions.length; i++){

            float centerX = positions[i][0] * width;
            float centerY = positions[i][1] * height;

            // draw ROI square
            canvas.drawRect(
                    centerX - half,         // left
                    centerY - half,             // top
                    centerX + half,             // right
                    centerY + half,             // bottom
                    roiPaint
            );

            // draw ROI label underneath square
            canvas.drawText(
                    "ROI " + (i + 1),
                    centerX - half - 20f,
                    centerY + half + 35f,
                    textPaint
            );
        }
    }
}
