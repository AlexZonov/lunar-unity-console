//
//  GestureCaptureOverlay.java
//
//  Lunar Unity Mobile Console
//  https://github.com/SpaceMadness/lunar-unity-console
//
//  Copyright 2015-2021 Alex Lementuev, SpaceMadness.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//


package spacemadness.com.lunarconsole.console;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;

import androidx.annotation.Nullable;

/**
 * Transparent full-screen overlay that captures touches for gesture recognition
 * without replacing Unity's (or any other) {@link OnTouchListener} on the player view.
 * <p>
 * Every event goes to the gesture listener, then is dispatched the way the parent would do it
 * without the overlay: ACTION_DOWN goes to the visible siblings below, top to bottom, and the first
 * one that consumes it receives the rest of the gesture. If none does, the gesture goes to
 * {@link Activity#onTouchEvent(MotionEvent)}, where games-activity 3.x reads input.
 */
final class GestureCaptureOverlay extends View {
    private final WeakReference<Activity> activityRef;

    @Nullable
    private OnTouchListener gestureListener;

    @Nullable
    private View touchTarget;

    GestureCaptureOverlay(Activity activity) {
        super(activity);
        this.activityRef = new WeakReference<>(activity);
        setWillNotDraw(true);
    }

    void setGestureListener(@Nullable OnTouchListener listener) {
        this.gestureListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        OnTouchListener listener = gestureListener;
        if (listener != null) {
            listener.onTouch(this, event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchTarget = findTouchTarget(event);
        } else if (touchTarget != null) {
            dispatchToSibling(touchTarget, event, false);
        }

        if (touchTarget == null) {
            Activity activity = activityRef.get();
            if (activity != null) {
                activity.onTouchEvent(event);
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchTarget = null;
        }

        // Claim the touch target so we receive the full gesture sequence (DOWN/MOVE/UP).
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        touchTarget = null;
    }

    @Nullable
    private View findTouchTarget(MotionEvent down) {
        ViewParent parent = getParent();
        if (!(parent instanceof ViewGroup)) {
            return null;
        }

        ViewGroup group = (ViewGroup) parent;
        for (int i = group.indexOfChild(this) - 1; i >= 0; --i) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == VISIBLE && dispatchToSibling(child, down, true)) {
                return child;
            }
        }
        return null;
    }

    private boolean dispatchToSibling(View child, MotionEvent event, boolean hitTest) {
        // Both views are offset by the same parent scroll, so it cancels out.
        MotionEvent childEvent = MotionEvent.obtain(event);
        try {
            childEvent.offsetLocation(getLeft() - child.getLeft(), getTop() - child.getTop());
            if (hitTest && !isInside(child, childEvent)) {
                return false;
            }
            return child.dispatchTouchEvent(childEvent);
        } finally {
            childEvent.recycle();
        }
    }

    private static boolean isInside(View view, MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        return x >= 0 && y >= 0 && x < view.getWidth() && y < view.getHeight();
    }
}
