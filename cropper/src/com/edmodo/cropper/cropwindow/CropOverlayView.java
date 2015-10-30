/*
 * Copyright 2013, Edmodo, Inc. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this work except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE file, or at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" 
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language 
 * governing permissions and limitations under the License. 
 */

package com.edmodo.cropper.cropwindow;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.edmodo.cropper.R;
import com.edmodo.cropper.cropwindow.edge.Edge;
import com.edmodo.cropper.cropwindow.handle.Handle;
import com.edmodo.cropper.util.AspectRatioUtil;
import com.edmodo.cropper.util.HandleUtil;
import com.edmodo.cropper.util.PaintUtil;

/**
 * A custom View representing the crop window and the shaded background outside the crop window.
 */
public class CropOverlayView extends View {

    // Private Constants ///////////////////////////////////////////////////////////////////////////

    // 'mGuidelinesMode' enumerations
    @SuppressWarnings("unused")
    private static final int GUIDELINES_OFF = 0;
    private static final int GUIDELINES_ON_TOUCH = 1;
    private static final int GUIDELINES_ON = 2;

    // Member Variables ////////////////////////////////////////////////////////////////////////////

    // The Paint used to draw the white rectangle around the crop area.
    private Paint mBorderPaint;

    // The Paint used to draw the guidelines within the crop area when pressed.
    private Paint mGuidelinePaint;

    // The Paint used to draw the corners of the Border
    private Paint mCornerPaint;

    // The Paint used to darken the surrounding areas outside the crop area.
    private Paint mSurroundingAreaOverlayPaint;

    // The radius (in pixels) of the touchable area around the handle.
    // We are basing this value off of the recommended 48dp touch target size.
    private float mHandleRadius;

    // An edge of the crop window will snap to the corresponding edge of a
    // specified bounding box when the crop window edge is less than or equal to
    // this distance (in pixels) away from the bounding box edge.
    private float mSnapRadius;

    // Thickness of the line (in pixels) used to draw the corner handle.
    private float mCornerThickness;

    // Thickness of the line (in pixels) used to draw the border of the crop window.
    private float mBorderThickness;

    // Length of one side of the corner handle.
    private float mCornerLength;

    // The bounding box around the Bitmap that we are cropping.
    @NonNull
    private Rect mBitmapRect = new Rect();

    // Holds the x and y offset between the exact touch location and the exact
    // handle location that is activated. There may be an offset because we
    // allow for some leeway (specified by 'mHandleRadius') in activating a
    // handle. However, we want to maintain these offset values while the handle
    // is being dragged so that the handle doesn't jump.
    @NonNull
    private PointF mTouchOffset = new PointF();

    // The Handle that is currently pressed; null if no Handle is pressed.
    private Handle mPressedHandle;

    // Flag indicating if the crop area should always be a certain aspect ratio (indicated by mTargetAspectRatio).
    private boolean mFixAspectRatio;

    // Current aspect ratio of the image.
    private int mAspectRatioX = 1;
    private int mAspectRatioY = 1;

    // Mode indicating how/whether to show the guidelines; must be one of GUIDELINES_OFF, GUIDELINES_ON_TOUCH, GUIDELINES_ON.
    private int mGuidelinesMode;

    // Whether the Crop View has been initialized for the first time
    private boolean initializedCropWindow = false;

    // Constructors ////////////////////////////////////////////////////////////////////////////////

    public CropOverlayView(Context context) {
        super(context);
        init(context);
    }

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CropOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {

        final Resources resources = context.getResources();

        mBorderPaint = PaintUtil.newBorderPaint(resources);
        mGuidelinePaint = PaintUtil.newGuidelinePaint(resources);
        mSurroundingAreaOverlayPaint = PaintUtil.newSurroundingAreaOverlayPaint(resources);
        mCornerPaint = PaintUtil.newCornerPaint(resources);

        mHandleRadius = resources.getDimension(R.dimen.target_radius);
        mSnapRadius = resources.getDimension(R.dimen.snap_radius);

        mBorderThickness = resources.getDimension(R.dimen.border_thickness);
        mCornerThickness = resources.getDimension(R.dimen.corner_thickness);
        mCornerLength = resources.getDimension(R.dimen.corner_length);

        mGuidelinesMode = CropOverlayView.GUIDELINES_ON;
    }

    // View Methods ////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {

        // Initialize the crop window here because we need the size of the view
        // to have been determined.
        initCropWindow(mBitmapRect);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawDarkenedSurroundingArea(canvas);
        drawGuidelines(canvas);
        drawBorder(canvas);
        drawCorners(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        // If this View is not enabled, don't allow for touch interactions.
        if (!isEnabled()) {
            return false;
        }

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:
                onActionDown(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                onActionUp();
                return true;

            case MotionEvent.ACTION_MOVE:
                onActionMove(event.getX(), event.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            default:
                return false;
        }
    }

    // Public Methods //////////////////////////////////////////////////////////////////////////////

    /**
     * Informs the CropOverlayView of the image's position relative to the ImageView. This is
     * necessary to call in order to draw the crop window.
     *
     * @param bitmapRect the image's bounding box
     */
    public void setBitmapRect(@NonNull Rect bitmapRect) {
        mBitmapRect = bitmapRect;
        initCropWindow(mBitmapRect);
    }

    /**
     * Resets the crop overlay view.
     */
    public void resetCropOverlayView() {
        if (initializedCropWindow) {
            initCropWindow(mBitmapRect);
            invalidate();
        }
    }

    /**
     * Sets the guidelines for the CropOverlayView to be either on, off, or to show when resizing
     * the application.
     *
     * @param guidelinesMode integer that signals whether the guidelines should be on, off, or only
     *                       showing when resizing.
     */
    public void setGuidelinesMode(@IntRange(from = 0, to = 2) int guidelinesMode) {

        mGuidelinesMode = guidelinesMode;

        if (initializedCropWindow) {
            initCropWindow(mBitmapRect);
            invalidate();
        }
    }

    /**
     * Sets whether the aspect ratio is fixed or not; true fixes the aspect ratio, while false
     * allows it to be changed.
     *
     * @param fixAspectRatio Boolean that signals whether the aspect ratio should be maintained.
     */
    public void setFixedAspectRatio(boolean fixAspectRatio) {

        mFixAspectRatio = fixAspectRatio;

        if (initializedCropWindow) {
            initCropWindow(mBitmapRect);
            invalidate();
        }
    }

    /**
     * Sets the X value of the aspect ratio; is defaulted to 1.
     *
     * @param aspectRatioX int that specifies the new X value of the aspect ratio
     */
    public void setAspectRatioX(int aspectRatioX) {

        if (aspectRatioX <= 0) {
            throw new IllegalArgumentException("Cannot set aspect ratio value to a number less than or equal to 0.");
        }

        mAspectRatioX = aspectRatioX;

        if (initializedCropWindow) {
            initCropWindow(mBitmapRect);
            invalidate();
        }
    }

    /**
     * Sets the Y value of the aspect ratio; is defaulted to 1.
     *
     * @param aspectRatioY int that specifies the new Y value of the aspect ratio
     */
    public void setAspectRatioY(int aspectRatioY) {

        if (aspectRatioY <= 0) {
            throw new IllegalArgumentException("Cannot set aspect ratio value to a number less than or equal to 0.");

        }
        
        mAspectRatioY = aspectRatioY;

        if (initializedCropWindow) {
            initCropWindow(mBitmapRect);
            invalidate();
        }
    }

    /**
     * Sets all initial values, but does not call initCropWindow to reset the views. Used once at
     * the very start to initialize the attributes.
     *
     * @param guidelinesMode Integer that signals whether the guidelines should be on, off, or only
     *                       showing when resizing.
     * @param fixAspectRatio Boolean that signals whether the aspect ratio should be maintained.
     * @param aspectRatioX   float that specifies the new X value of the aspect ratio
     * @param aspectRatioY   float that specifies the new Y value of the aspect ratio
     */
    public void setInitialAttributeValues(@IntRange(from = 0, to = 2) int guidelinesMode,
                                          boolean fixAspectRatio,
                                          int aspectRatioX,
                                          int aspectRatioY) {

        if (aspectRatioX <= 0 || aspectRatioY <= 0) {
            throw new IllegalArgumentException("Cannot set aspect ratio value to a number less than or equal to 0.");
        }

        mGuidelinesMode = guidelinesMode;
        mFixAspectRatio = fixAspectRatio;
        mAspectRatioX = aspectRatioX;
        mAspectRatioY = aspectRatioY;
    }

    // Private Methods /////////////////////////////////////////////////////////////////////////////

    /**
     * Set the initial crop window size and position. This is dependent on the size and position of
     * the image being cropped.
     *
     * @param bitmapRect the bounding box around the image being cropped
     */
    private void initCropWindow(@NonNull Rect bitmapRect) {

        // Tells the attribute functions the crop window has already been
        // initialized
        if (!initializedCropWindow) {
            initializedCropWindow = true;
        }

        if (mFixAspectRatio) {

            // If the image aspect ratio is wider than the crop aspect ratio,
            // then the image height is the determining initial length. Else,
            // vice-versa.
            if (AspectRatioUtil.calculateAspectRatio(bitmapRect) > getTargetAspectRatio()) {

                Edge.TOP.setCoordinate(bitmapRect.top);
                Edge.BOTTOM.setCoordinate(bitmapRect.bottom);

                final float centerX = getWidth() / 2f;

                // Limits the aspect ratio to no less than 40 wide or 40 tall
                final float cropWidth = Math.max(Edge.MIN_CROP_LENGTH_PX,
                                                 AspectRatioUtil.calculateWidth(Edge.TOP.getCoordinate(),
                                                                                Edge.BOTTOM.getCoordinate(),
                                                                                getTargetAspectRatio()));

                final float halfCropWidth = cropWidth / 2f;
                Edge.LEFT.setCoordinate(centerX - halfCropWidth);
                Edge.RIGHT.setCoordinate(centerX + halfCropWidth);

            } else {

                Edge.LEFT.setCoordinate(bitmapRect.left);
                Edge.RIGHT.setCoordinate(bitmapRect.right);

                final float centerY = getHeight() / 2f;

                // Limits the aspect ratio to no less than 40 wide or 40 tall
                final float cropHeight = Math.max(Edge.MIN_CROP_LENGTH_PX,
                                                  AspectRatioUtil.calculateHeight(Edge.LEFT.getCoordinate(),
                                                                                  Edge.RIGHT.getCoordinate(),
                                                                                  getTargetAspectRatio()));

                final float halfCropHeight = cropHeight / 2f;
                Edge.TOP.setCoordinate(centerY - halfCropHeight);
                Edge.BOTTOM.setCoordinate(centerY + halfCropHeight);
            }

        } else { // ... do not fix aspect ratio...

            // Initialize crop window to have 10% padding w/ respect to image.
            final float horizontalPadding = 0.1f * bitmapRect.width();
            final float verticalPadding = 0.1f * bitmapRect.height();

            Edge.LEFT.setCoordinate(bitmapRect.left + horizontalPadding);
            Edge.TOP.setCoordinate(bitmapRect.top + verticalPadding);
            Edge.RIGHT.setCoordinate(bitmapRect.right - horizontalPadding);
            Edge.BOTTOM.setCoordinate(bitmapRect.bottom - verticalPadding);
        }
    }

    private void drawGuidelines(@NonNull Canvas canvas) {

        if (!shouldGuidelinesBeShown()) {
            return;
        }

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        // Draw vertical guidelines.
        final float oneThirdCropWidth = Edge.getWidth() / 3;

        final float x1 = left + oneThirdCropWidth;
        canvas.drawLine(x1, top, x1, bottom, mGuidelinePaint);
        final float x2 = right - oneThirdCropWidth;
        canvas.drawLine(x2, top, x2, bottom, mGuidelinePaint);

        // Draw horizontal guidelines.
        final float oneThirdCropHeight = Edge.getHeight() / 3;

        final float y1 = top + oneThirdCropHeight;
        canvas.drawLine(left, y1, right, y1, mGuidelinePaint);
        final float y2 = bottom - oneThirdCropHeight;
        canvas.drawLine(left, y2, right, y2, mGuidelinePaint);
    }

    private void drawDarkenedSurroundingArea(@NonNull Canvas canvas) {

        final Rect bitmapRect = mBitmapRect;

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        /*-
          -------------------------------------
          |                top                |
          -------------------------------------
          |      |                    |       |
          |      |                    |       |
          | left |                    | right |
          |      |                    |       |
          |      |                    |       |
          -------------------------------------
          |              bottom               |
          -------------------------------------
         */

        // Draw "top", "bottom", "left", then "right" quadrants according to diagram above.
        canvas.drawRect(bitmapRect.left, bitmapRect.top, bitmapRect.right, top, mSurroundingAreaOverlayPaint);
        canvas.drawRect(bitmapRect.left, bottom, bitmapRect.right, bitmapRect.bottom, mSurroundingAreaOverlayPaint);
        canvas.drawRect(bitmapRect.left, top, left, bottom, mSurroundingAreaOverlayPaint);
        canvas.drawRect(right, top, bitmapRect.right, bottom, mSurroundingAreaOverlayPaint);
    }

    private void drawBorder(@NonNull Canvas canvas) {

        canvas.drawRect(Edge.LEFT.getCoordinate(),
                        Edge.TOP.getCoordinate(),
                        Edge.RIGHT.getCoordinate(),
                        Edge.BOTTOM.getCoordinate(),
                        mBorderPaint);
    }

    private void drawCorners(@NonNull Canvas canvas) {

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        // Absolute value of the offset by which to draw the corner line such that its inner edge is flush with the border's inner edge.
        final float lateralOffset = (mCornerThickness - mBorderThickness) / 2f;
        // Absolute value of the offset by which to start the corner line such that the line is drawn all the way to form a corner edge with the adjacent side.
        final float startOffset = mCornerThickness - (mBorderThickness / 2f);

        // Top-left corner: left side
        canvas.drawLine(left - lateralOffset, top - startOffset, left - lateralOffset, top + mCornerLength, mCornerPaint);
        // Top-left corner: top side
        canvas.drawLine(left - startOffset, top - lateralOffset, left + mCornerLength, top - lateralOffset, mCornerPaint);

        // Top-right corner: right side
        canvas.drawLine(right + lateralOffset, top - startOffset, right + lateralOffset, top + mCornerLength, mCornerPaint);
        // Top-right corner: top side
        canvas.drawLine(right + startOffset, top - lateralOffset, right - mCornerLength, top - lateralOffset, mCornerPaint);

        // Bottom-left corner: left side
        canvas.drawLine(left - lateralOffset, bottom + startOffset, left - lateralOffset, bottom - mCornerLength, mCornerPaint);
        // Bottom-left corner: bottom side
        canvas.drawLine(left - startOffset, bottom + lateralOffset, left + mCornerLength, bottom + lateralOffset, mCornerPaint);

        // Bottom-right corner: right side
        canvas.drawLine(right + lateralOffset, bottom + startOffset, right + lateralOffset, bottom - mCornerLength, mCornerPaint);
        // Bottom-right corner: bottom side
        canvas.drawLine(right + startOffset, bottom + lateralOffset, right - mCornerLength, bottom + lateralOffset, mCornerPaint);
    }

    private boolean shouldGuidelinesBeShown() {
        return ((mGuidelinesMode == GUIDELINES_ON)
                || ((mGuidelinesMode == GUIDELINES_ON_TOUCH) && (mPressedHandle != null)));
    }

    /**
     * Handles a {@link MotionEvent#ACTION_DOWN} event.
     *
     * @param x the x-coordinate of the down action
     * @param y the y-coordinate of the down action
     */
    private void onActionDown(float x, float y) {

        final float left = Edge.LEFT.getCoordinate();
        final float top = Edge.TOP.getCoordinate();
        final float right = Edge.RIGHT.getCoordinate();
        final float bottom = Edge.BOTTOM.getCoordinate();

        mPressedHandle = HandleUtil.getPressedHandle(x, y, left, top, right, bottom, mHandleRadius);

        // Calculate the offset of the touch point from the precise location of the handle.
        // Save these values in member variable 'mTouchOffset' so that we can maintain this offset as we drag the handle.
        if (mPressedHandle != null) {
            HandleUtil.getOffset(mPressedHandle, x, y, left, top, right, bottom, mTouchOffset);
            invalidate();
        }
    }

    /**
     * Handles a {@link MotionEvent#ACTION_UP} or {@link MotionEvent#ACTION_CANCEL} event.
     */
    private void onActionUp() {
        if (mPressedHandle != null) {
            mPressedHandle = null;
            invalidate();
        }
    }

    /**
     * Handles a {@link MotionEvent#ACTION_MOVE} event.
     *
     * @param x the x-coordinate of the move event
     * @param y the y-coordinate of the move event
     */
    private void onActionMove(float x, float y) {

        if (mPressedHandle == null) {
            return;
        }

        // Adjust the coordinates for the finger position's offset (i.e. the distance from the initial touch to the precise handle location).
        // We want to maintain the initial touch's distance to the pressed handle so that the crop window size does not "jump".
        x += mTouchOffset.x;
        y += mTouchOffset.y;

        // Calculate the new crop window size/position.
        if (mFixAspectRatio) {
            mPressedHandle.updateCropWindow(x, y, getTargetAspectRatio(), mBitmapRect, mSnapRadius);
        } else {
            mPressedHandle.updateCropWindow(x, y, mBitmapRect, mSnapRadius);
        }
        invalidate();
    }

    private float getTargetAspectRatio() {
        return mAspectRatioX / (float) mAspectRatioY;
    }
}
