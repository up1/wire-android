/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.drawing;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.Toolbar;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import com.waz.api.ImageAsset;
import com.waz.api.ImageAssetFactory;
import com.waz.api.LoadHandle;
import com.waz.api.MemoryImageCache;
import com.waz.zclient.OnBackPressedListener;
import com.waz.zclient.R;
import com.waz.zclient.controllers.accentcolor.AccentColorObserver;
import com.waz.zclient.controllers.drawing.DrawingController;
import com.waz.zclient.controllers.globallayout.KeyboardVisibilityObserver;
import com.waz.zclient.pages.BaseFragment;
import com.waz.zclient.ui.colorpicker.ColorPickerLayout;
import com.waz.zclient.ui.colorpicker.ColorPickerScrollView;
import com.waz.zclient.ui.colorpicker.EmojiBottomSheetDialog;
import com.waz.zclient.ui.colorpicker.EmojiSize;
import com.waz.zclient.ui.sketch.DrawingCanvasView;
import com.waz.zclient.ui.text.TypefaceTextView;
import com.waz.zclient.ui.utils.KeyboardUtils;
import com.waz.zclient.ui.views.CursorIconButton;
import com.waz.zclient.utils.LayoutSpec;
import com.waz.zclient.utils.TrackingUtils;
import com.waz.zclient.utils.ViewUtils;
import com.waz.zclient.utils.debug.ShakeEventListener;
import net.hockeyapp.android.ExceptionHandler;

import java.util.Locale;

public class DrawingFragment extends BaseFragment<DrawingFragment.Container> implements OnBackPressedListener,
                                                                                        ColorPickerLayout.OnColorSelectedListener,
                                                                                        DrawingCanvasView.DrawingCanvasCallback,
                                                                                        ViewTreeObserver.OnScrollChangedListener,
                                                                                        AccentColorObserver,
                                                                                        ColorPickerLayout.OnWidthChangedListener,
                                                                                        KeyboardVisibilityObserver {

    public static final String TAG = DrawingFragment.class.getName();
    private static final String SAVED_INSTANCE_BITMAP = "SAVED_INSTANCE_BITMAP";
    private static final String ARGUMENT_BACKGROUND_IMAGE = "ARGUMENT_BACKGROUND_IMAGE";
    private static final String ARGUMENT_DRAWING_DESTINATION = "ARGUMENT_DRAWING_DESTINATION";

    private static final float TEXT_ALPHA_INVISIBLE = 0F;
    private static final float TEXT_ALPHA_MOVE = 0.2F;
    private static final float TEXT_ALPHA_VISIBLE = 1F;

    private static final int TEXT_COLOR_NO_BACKGROUND_IMAGE = Color.BLACK;
    private static final int TEXT_COLOR_WITH_BACKGROUND_IMAGE = Color.WHITE;

    private ShakeEventListener shakeEventListener;
    private SensorManager sensorManager;

    private DrawingCanvasView drawingCanvasView;
    private ColorPickerLayout colorLayout;
    private HorizontalScrollView colorPickerScrollContainer;
    private Toolbar toolbar;

    private ColorPickerScrollView colorPickerScrollBar;

    private TypefaceTextView drawingViewTip;
    private View drawingTipBackground;

    private CursorIconButton sendDrawingButton;
    private EditText sketchEditTextView;
    private TextView actionButtonText;
    private TextView actionButtonEmoji;
    private TextView actionButtonSketch;
    private int defaultTextColor;

    private ImageAsset backgroundImage;
    private LoadHandle bitmapLoadHandle;

    private DrawingController.DrawingDestination drawingDestination;
    private boolean includeBackgroundImage;
    private EmojiSize currentEmojiSize = EmojiSize.SMALL;

    public static DrawingFragment newInstance(ImageAsset backgroundAsset, DrawingController.DrawingDestination drawingDestination) {
        DrawingFragment fragment = new DrawingFragment();
        Bundle bundle = new Bundle();
        bundle.putParcelable(ARGUMENT_BACKGROUND_IMAGE, backgroundAsset);
        bundle.putString(ARGUMENT_DRAWING_DESTINATION, drawingDestination.toString());
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (LayoutSpec.isTablet(getActivity())) {
            ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, getActivity());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        backgroundImage = args.getParcelable(ARGUMENT_BACKGROUND_IMAGE);
        drawingDestination = DrawingController.DrawingDestination.valueOf(args.getString(ARGUMENT_DRAWING_DESTINATION));
        sensorManager = (SensorManager) getActivity().getSystemService(Activity.SENSOR_SERVICE);
        defaultTextColor = ContextCompat.getColor(getContext(), R.color.text__primary_light);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_drawing, container, false);
        drawingCanvasView = ViewUtils.getView(rootView, R.id.dcv__canvas);
        drawingCanvasView.setDrawingCanvasCallback(this);
        drawingCanvasView.setDrawingColor(getControllerFactory().getAccentColorController().getColor());
        drawingCanvasView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        hideTip();
                        break;
                }
                return false;
            }
        });

        colorPickerScrollContainer = ViewUtils.getView(rootView, R.id.hsv_color_picker_scroll_view);

        colorLayout = ViewUtils.getView(rootView, R.id.cpdl__color_layout);
        colorLayout.setOnColorSelectedListener(this);
        int[] colors = getResources().getIntArray(R.array.draw_color);
        colorLayout.setAccentColors(colors, getControllerFactory().getAccentColorController().getColor());
        colorLayout.getViewTreeObserver().addOnScrollChangedListener(this);

        colorPickerScrollBar = ViewUtils.getView(rootView, R.id.cpsb__color_picker_scrollbar);
        colorPickerScrollBar.setScrollBarColor(getControllerFactory().getAccentColorController().getColor());

        TypefaceTextView conversationTitle = ViewUtils.getView(rootView, R.id.tv__drawing_toolbar__title);
        conversationTitle.setText(getStoreFactory().getConversationStore().getCurrentConversation().getName().toUpperCase(Locale.getDefault()));
        toolbar = ViewUtils.getView(rootView, R.id.t_drawing_toolbar);
        toolbar.inflateMenu(R.menu.toolbar_sketch);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.close:
                        if (getControllerFactory() == null || getControllerFactory().isTornDown()) {
                            return false;
                        }
                        getControllerFactory().getDrawingController().hideDrawing(drawingDestination, false);
                        return true;
                }
                return false;
            }
        });
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawingCanvasView != null) {
                    drawingCanvasView.undo();
                }
            }
        });
        toolbar.setNavigationIcon(R.drawable.toolbar_action_undo_disabled);

        actionButtonText = ViewUtils.getView(rootView, R.id.gtv__drawing_button__text);
        actionButtonText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTextClick();
            }
        });
        actionButtonEmoji = ViewUtils.getView(rootView, R.id.gtv__drawing_button__emoji);
        actionButtonEmoji.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEmojiClick();
            }
        });
        actionButtonSketch = ViewUtils.getView(rootView, R.id.gtv__drawing_button__sketch);
        actionButtonSketch.setTextColor(getControllerFactory().getAccentColorController().getColor());
        actionButtonSketch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSketchClick();
            }
        });

        drawingTipBackground = ViewUtils.getView(rootView, R.id.v__tip_background);
        drawingViewTip = ViewUtils.getView(rootView, R.id.ttv__drawing__view__tip);
        drawingTipBackground.setVisibility(View.INVISIBLE);

        sendDrawingButton = ViewUtils.getView(rootView, R.id.tv__send_button);
        sendDrawingButton.setOnClickListener(sketchButtonsOnClickListener);
        sendDrawingButton.setClickable(false);

        sketchEditTextView = ViewUtils.getView(rootView, R.id.et__sketch_text);
        sketchEditTextView.setAlpha(TEXT_ALPHA_INVISIBLE);
        sketchEditTextView.setVisibility(View.INVISIBLE);
        sketchEditTextView.setOnTouchListener(new View.OnTouchListener() {
            private float initialX;
            private float initialY;
            private FrameLayout.LayoutParams params;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (drawingCanvasView.getCurrentMode() == DrawingCanvasView.Mode.TEXT) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = event.getX();
                            initialY = event.getY();
                            params = (FrameLayout.LayoutParams) sketchEditTextView.getLayoutParams();
                            sketchEditTextView.setAlpha(TEXT_ALPHA_MOVE);
                            break;
                        case MotionEvent.ACTION_MOVE:
                            params.leftMargin += (int) (event.getX() - initialX);
                            params.topMargin += (int) (event.getY() - initialY);
                            sketchEditTextView.setLayoutParams(params);
                            break;
                        case MotionEvent.ACTION_UP:
                            closeKeyboard();
                            break;
                    }
                }
                return drawingCanvasView.onTouchEvent(event);
            }
        });

        if (savedInstanceState != null) {
            Bitmap savedBitmap = savedInstanceState.getParcelable(SAVED_INSTANCE_BITMAP);
            if (savedBitmap != null) {
                // Use saved background image if exists
                drawingCanvasView.setBackgroundBitmap(savedBitmap);
                sketchEditTextView.setTextColor(TEXT_COLOR_WITH_BACKGROUND_IMAGE);
            } else {
                setBackgroundBitmap();
            }
        } else {
            setBackgroundBitmap();
        }

        return rootView;
    }

    private void hideTip() {
        if (drawingViewTip.getVisibility() == View.GONE) {
            return;
        }
        drawingViewTip.setVisibility(View.GONE);
        drawingTipBackground.setVisibility(View.GONE);
        drawingCanvasView.setOnTouchListener(null);
    }


    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(SAVED_INSTANCE_BITMAP, getBitmapDrawing());
        super.onSaveInstanceState(outState);
    }

    public void setBackgroundBitmap() {
        if (getActivity() == null || backgroundImage == null) {
            sketchEditTextView.setTextColor(TEXT_COLOR_NO_BACKGROUND_IMAGE);
            return;
        }
        drawingViewTip.setText(getResources().getString(R.string.drawing__tip__picture__message));
        drawingTipBackground.setVisibility(View.VISIBLE);
        drawingViewTip.setTextColor(getResources().getColor(R.color.drawing__tip__font__color_image));
        sketchEditTextView.setTextColor(TEXT_COLOR_WITH_BACKGROUND_IMAGE);
        cancelLoadHandle();
        bitmapLoadHandle = backgroundImage.getSingleBitmap(ViewUtils.getOrientationDependentDisplayWidth(getActivity()), new ImageAsset.BitmapCallback() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, boolean isPreview) {
                if (getActivity() == null || drawingCanvasView == null || isPreview) {
                    return;
                }
                includeBackgroundImage = true;
                drawingCanvasView.setBackgroundBitmap(bitmap);
                cancelLoadHandle();
            }

            @Override
            public void onBitmapLoadingFailed() {
                cancelLoadHandle();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        colorLayout.setOnWidthChangedListener(this);
        getStoreFactory().getInAppNotificationStore().setUserSendingPicture(true);
        shakeEventListener = new ShakeEventListener();
        shakeEventListener.setOnShakeListener(new ShakeEventListener.OnShakeListener() {
            @Override
            public void onShake() {
                if (includeBackgroundImage) {
                    drawingCanvasView.removeBackgroundBitmap();
                    includeBackgroundImage = false;
                } else {
                    drawingCanvasView.drawBackgroundBitmap();
                    includeBackgroundImage = true;
                }
            }
        });
        sensorManager.registerListener(shakeEventListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_NORMAL);
        getControllerFactory().getGlobalLayoutController().addKeyboardVisibilityObserver(this);
        getControllerFactory().getAccentColorController().addAccentColorObserver(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (LayoutSpec.isTablet(getActivity())) {
            ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, getActivity());
        }
    }

    @Override
    public void onStop() {
        getControllerFactory().getAccentColorController().removeAccentColorObserver(this);
        getControllerFactory().getGlobalLayoutController().removeKeyboardVisibilityObserver(this);
        getStoreFactory().getInAppNotificationStore().setUserSendingPicture(false);
        sensorManager.unregisterListener(shakeEventListener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (drawingCanvasView != null) {
            drawingCanvasView.onDestroy();
            drawingCanvasView = null;
        }
        colorLayout = null;
        sendDrawingButton = null;
        cancelLoadHandle();
        super.onDestroyView();
    }

    public void cancelLoadHandle() {
        if (bitmapLoadHandle != null) {
            bitmapLoadHandle.cancel();
            bitmapLoadHandle = null;
        }
    }

    private Bitmap getBitmapDrawing() {
        return drawingCanvasView.getBitmap();
    }

    @Override
    public boolean onBackPressed() {
        if (isShowingKeyboard()) {
            closeKeyboard();
            return true;
        }
        if (drawingCanvasView != null && drawingCanvasView.undo()) {
            return true;
        }
        getControllerFactory().getDrawingController().hideDrawing(drawingDestination, false);
        return true;
    }

    @Override
    public void onScrollWidthChanged(int width) {
        colorPickerScrollBar.setScrollBarSize(width);
    }

    private View.OnClickListener sketchButtonsOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (drawingCanvasView == null) {
                return;
            }
            switch (v.getId()) {
                case R.id.tv__send_button:
                    if (!drawingCanvasView.isEmpty()) {
                        getStoreFactory().getConversationStore().sendMessage(getFinalSketchImage());
                        TrackingUtils.onSentSketchMessage(getControllerFactory().getTrackingController(),
                                                          getStoreFactory().getConversationStore().getCurrentConversation(),
                                                          drawingDestination);

                        getControllerFactory().getDrawingController().hideDrawing(drawingDestination, true);
                    }
                    break;
                default:
                    //nothing
                    break;
            }
        }
    };

    private ImageAsset getFinalSketchImage() {
        Bitmap finalBitmap = getBitmapDrawing();
        try {
            int x;
            int y;
            int width;
            int height;
            if (drawingCanvasView.isBackgroundImageLandscape()) {
                x = 0;
                y = drawingCanvasView.getTopTrimValue(true);
                width = finalBitmap.getWidth();
                height = drawingCanvasView.getBottomTrimValue(true) - y;

                if (height < drawingCanvasView.getLandscapeBackgroundBitmapHeight()) {
                    y = drawingCanvasView.getBackgroundBitmapTop();
                    height = drawingCanvasView.getLandscapeBackgroundBitmapHeight();
                }
            } else {
                x = 0;
                y = drawingCanvasView.getTopTrimValue(false);
                width = finalBitmap.getWidth();
                height = drawingCanvasView.getBottomTrimValue(false) - y;
            }
            MemoryImageCache.reserveImageMemory(width, height);
            finalBitmap = Bitmap.createBitmap(finalBitmap, x, y, width, height);
        } catch (OutOfMemoryError outOfMemoryError) {
            ExceptionHandler.saveException(outOfMemoryError, null);
        }

        return ImageAssetFactory.getImageAsset(finalBitmap, ExifInterface.ORIENTATION_NORMAL);
    }

    @Override
    public void onScrollChanged() {
        if (colorPickerScrollBar == null || colorLayout == null) {
            return;
        }
        colorPickerScrollBar.setLeftX(colorPickerScrollContainer.getScrollX());
    }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        colorPickerScrollBar.setBackgroundColor(color);
        if (!drawingCanvasView.isEmpty()) {
            sendDrawingButton.setSolidBackgroundColor(getControllerFactory().getAccentColorController().getColor());
        }
    }

    private Drawable getTextBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        color = (color & 0x00FFFFFF) | 0x40000000;
        drawable.setColor(color);
        drawable.setCornerRadius(ViewUtils.toPx(getContext(), 24));
        return drawable;
    }

    @Override
    public void onColorSelected(int color, int strokeSize) {
        if (drawingCanvasView == null) {
            return;
        }
        drawingCanvasView.setDrawingColor(color);
        drawingCanvasView.setStrokeSize(strokeSize);
        sketchEditTextView.setBackground(getTextBackground(color));
    }

    public void onEmojiClick() {
        final EmojiBottomSheetDialog dialog = new EmojiBottomSheetDialog(getContext(),
                                                                         currentEmojiSize,
                                                                         new EmojiBottomSheetDialog.EmojiDialogListener() {
                                                                             @Override
                                                                             public void onEmojiSelected(String emoji, EmojiSize emojiSize) {
                                                                                 actionButtonEmoji.setTextColor(getControllerFactory().getAccentColorController().getColor());
                                                                                 actionButtonSketch.setTextColor(defaultTextColor);
                                                                                 actionButtonText.setTextColor(defaultTextColor);
                                                                                 drawingCanvasView.setEmoji(emoji, emojiSize.getEmojiSize(getContext()));
                                                                                 sketchEditTextView.setVisibility(View.INVISIBLE);
                                                                                 currentEmojiSize = emojiSize;
                                                                                 getControllerFactory().getUserPreferencesController().addRecentEmoji(emoji);
                                                                             }
                                                                         },
                                                                         getControllerFactory().getUserPreferencesController().getRecentEmojis(),
                                                                         getControllerFactory().getUserPreferencesController().getUnsupportedEmojis());
        dialog.show();
    }

    private void closeKeyboard() {
        sketchEditTextView.setCursorVisible(false);
        sketchEditTextView.setDrawingCacheEnabled(true);
        Bitmap bitmapDrawingCache = sketchEditTextView.getDrawingCache();
        if (bitmapDrawingCache != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) sketchEditTextView.getLayoutParams();
            drawingCanvasView.drawTextBitmap(bitmapDrawingCache.copy(bitmapDrawingCache.getConfig(), true), params.leftMargin, params.topMargin);
        } else {
            drawingCanvasView.drawTextBitmap(null, 0, 0);
        }
        sketchEditTextView.setDrawingCacheEnabled(false);
        KeyboardUtils.hideKeyboard(getActivity());
        sketchEditTextView.setAlpha(TEXT_ALPHA_INVISIBLE);
    }

    private void showKeyboard() {
        drawingCanvasView.drawTextBitmap(null, 0, 0);
        sketchEditTextView.setAlpha(TEXT_ALPHA_VISIBLE);
        sketchEditTextView.setCursorVisible(true);
        sketchEditTextView.requestFocus();
        KeyboardUtils.showKeyboard(getActivity());
    }

    private boolean isShowingKeyboard() {
        return Float.compare(sketchEditTextView.getAlpha(), TEXT_ALPHA_VISIBLE) == 0 && KeyboardUtils.isKeyboardVisible(getContext());
    }

    private void onSketchClick() {
        drawingCanvasView.setCurrentMode(DrawingCanvasView.Mode.SKETCH);
        actionButtonText.setTextColor(defaultTextColor);
        actionButtonEmoji.setTextColor(defaultTextColor);
        actionButtonSketch.setTextColor(getControllerFactory().getAccentColorController().getColor());
    }

    private void onTextClick() {
        actionButtonText.setTextColor(getControllerFactory().getAccentColorController().getColor());
        actionButtonEmoji.setTextColor(defaultTextColor);
        actionButtonSketch.setTextColor(defaultTextColor);
        drawingCanvasView.setCurrentMode(DrawingCanvasView.Mode.TEXT);
        if (isShowingKeyboard()) {
            closeKeyboard();
        } else {
            sketchEditTextView.setVisibility(View.VISIBLE);
            showKeyboard();
            hideTip();
        }

    }

    @Override
    public void drawingAdded() {
        if (isShowingKeyboard()) {
            closeKeyboard();
        }
        toolbar.setNavigationIcon(R.drawable.toolbar_action_undo);
        sendDrawingButton.setTextColor(getResources().getColor(R.color.drawing__icon__enabled_color));
        sendDrawingButton.setSolidBackgroundColor(getControllerFactory().getAccentColorController().getColor());
        sendDrawingButton.setClickable(true);
    }

    @Override
    public void drawingCleared() {
        toolbar.setNavigationIcon(R.drawable.toolbar_action_undo_disabled);
        sendDrawingButton.setTextColor(getResources().getColor(R.color.drawing__icon__disabled_color));
        sendDrawingButton.setSolidBackgroundColor(Color.TRANSPARENT);
        sendDrawingButton.setClickable(false);
    }

    @Override
    public void reserveBitmapMemory(int width, int height) {
        MemoryImageCache.reserveImageMemory(width, height);
    }

    @Override
    public void onScaleChanged(float scale) {
        float mediumTextSize = getResources().getDimensionPixelSize(com.waz.zclient.ui.R.dimen.wire__text_size__medium);
        float newSize = mediumTextSize * scale;
        sketchEditTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, newSize);
    }

    @Override
    public void onScaleStart() {
        drawingCanvasView.drawTextBitmap(null, 0, 0);
        sketchEditTextView.setAlpha(TEXT_ALPHA_VISIBLE);
    }

    @Override
    public void onScaleEnd() {
        closeKeyboard();
    }

    @Override
    public void onKeyboardVisibilityChanged(boolean keyboardIsVisible, int keyboardHeight, View currentFocus) {
        if (!keyboardIsVisible) {
            closeKeyboard();
        }
    }

    public interface Container { }
}
