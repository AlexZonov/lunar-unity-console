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

import java.lang.ref.WeakReference;

import androidx.annotation.Nullable;

/**
 * Transparent full-screen overlay that captures touches for gesture recognition
 * without replacing Unity's (or any other) {@link OnTouchListener} on the player view.
 * <p>
 * Events are forwarded to {@link Activity#onTouchEvent(MotionEvent)} so Unity still
 * receives input for both Activity and GameActivity entry points.
 */
final class GestureCaptureOverlay extends View {
    private final WeakReference<Activity> activityRef;

    @Nullable
    private OnTouchListener gestureListener;

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

        Activity activity = activityRef.get();
        if (activity != null) {
            activity.onTouchEvent(event);
        }

        // Claim the touch target so we receive the full gesture sequence (DOWN/MOVE/UP).
        return true;
    }
}
