//
//  ManagedPlatform.java
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.unity3d.player.UnityPlayer;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import spacemadness.com.lunarconsole.debug.Log;
import spacemadness.com.lunarconsole.utils.UIUtils;

import static spacemadness.com.lunarconsole.debug.Tags.PLUGIN;

import androidx.annotation.Nullable;

public class ManagedPlatform implements Platform {
    private final UnityScriptMessenger scriptMessenger;
    private final WeakReference<Activity> activityRef;
    private final boolean gameActivityEntryPoint;

    @Nullable
    private WeakReference<View> gestureViewRef;

    public ManagedPlatform(Activity activity, String target, String method) {
        this.activityRef = new WeakReference<>(activity);
        this.gameActivityEntryPoint = isGameActivityEntryPoint(activity);
        scriptMessenger = new UnityScriptMessenger(target, method);

        if (gameActivityEntryPoint) {
            Log.d(PLUGIN, "Detected GameActivity entry point");
        }
    }

    @Override
    public View getTouchRecipientView() {
        Activity currentActivity = activityRef.get();
        if (currentActivity == null) {
            Log.e(PLUGIN, "UnityPlayer.currentActivity is null");
            return null;
        }

        UnityPlayer unityPlayer = resolveUnityPlayer(currentActivity);
        if (unityPlayer == null) {
            Log.e(PLUGIN, "UnityPlayer instance is null");
            return null;
        }

        // Cast to Object first to handle newer Unity versions where UnityPlayer may not extend View.
        // This avoids compilation errors while maintaining compatibility with older versions.
        Object unityPlayerObject = unityPlayer;
        if (unityPlayerObject instanceof View) {
            return (View) unityPlayerObject;
        }

        // GameActivity: prefer SurfaceView — that's where input is associated.
        if (gameActivityEntryPoint) {
            View surfaceView = resolveSurfaceView(unityPlayer);
            if (surfaceView != null) {
                Log.d(PLUGIN, "Successfully resolved SurfaceView for GameActivity");
                return surfaceView;
            }
            Log.w(PLUGIN, "GameActivity SurfaceView not found, falling back to FrameLayout");
        }

        // For Unity 6000.0+ and newer versions, get FrameLayout via reflection
        // (method should exist in all supported Unity versions)
        try {
            final Method getFrameLayoutMethod = UnityPlayer.class.getMethod("getFrameLayout");
            View result = (View) getFrameLayoutMethod.invoke(unityPlayer);
            if (result != null) {
                Log.d(PLUGIN, "Successfully resolved FrameLayout via reflection");
                return result;
            }
        } catch (NoSuchMethodException e) {
            Log.w(PLUGIN, "UnityPlayer does not have getFrameLayout method");
            throw new IllegalStateException("UnityPlayer does not have getFrameLayout method", e);
        } catch (Exception e) {
            Log.e(PLUGIN, "Error while invoking getFrameLayout method: %s", e);
            throw new IllegalStateException("Error while invoking getFrameLayout method", e);
        }

        throw new IllegalStateException("Failed to resolve touch recipient view");
    }

    @Override
    public void installGestureTouchListener(View.OnTouchListener listener) {
        View view = getTouchRecipientView();
        if (view == null) {
            Log.w(PLUGIN, "Can't install gesture touch listener: touch view is null");
            return;
        }

        gestureViewRef = new WeakReference<>(view);

        if (gameActivityEntryPoint) {
            // GameActivity uses a plain FrameLayout/SurfaceView that does not claim the touch
            // target (unlike Activity's custom FrameLayout which injects events and returns true).
            // Claim the target so we receive the full gesture sequence, and forward events into
            // GameActivity.onTouchEvent → processMotionEvent so Unity still gets input.
            final WeakReference<Activity> activityWeakRef = activityRef;
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    listener.onTouch(v, event);

                    Activity activity = activityWeakRef.get();
                    if (activity != null) {
                        activity.onTouchEvent(event);
                    }
                    return true;
                }
            });
            Log.d(PLUGIN, "Installed GameActivity gesture touch listener with input forwarding");
        } else {
            view.setOnTouchListener(listener);
        }
    }

    @Override
    public void uninstallGestureTouchListener() {
        View view = gestureViewRef != null ? gestureViewRef.get() : null;
        if (view == null) {
            view = getTouchRecipientView();
        }

        if (view != null) {
            view.setOnTouchListener(null);
        } else {
            Log.w(PLUGIN, "Can't uninstall gesture touch listener: touch view is null");
        }

        gestureViewRef = null;
    }

    @Override
    public void sendUnityScriptMessage(String name, Map<String, Object> data) {
        try {
            scriptMessenger.sendMessage(name, data);
        } catch (Exception e) {
            Log.e(PLUGIN, "Error while sending Unity script message: name=%s param=%s", name, data);
        }
    }

    private static boolean isGameActivityEntryPoint(Activity activity) {
        UnityPlayer unityPlayer = resolveUnityPlayer(activity);
        if (unityPlayer == null) {
            return false;
        }

        // Activity → UnityPlayerForActivityOrService, GameActivity → UnityPlayerForGameActivity.
        // Check the player type so custom activities still resolve correctly without hardcoding
        // GameActivity class names from Unity/Google packages.
        try {
            Class<?> gamePlayerClass = Class.forName("com.unity3d.player.UnityPlayerForGameActivity");
            return gamePlayerClass.isInstance(unityPlayer);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Nullable
    private static View resolveSurfaceView(UnityPlayer unityPlayer) {
        try {
            Method getSurfaceViewMethod = UnityPlayer.class.getMethod("getSurfaceView");
            Object result = getSurfaceViewMethod.invoke(unityPlayer);
            if (result instanceof View) {
                return (View) result;
            }
        } catch (NoSuchMethodException e) {
            Log.d(PLUGIN, "UnityPlayer does not have getSurfaceView method");
        } catch (Exception e) {
            Log.e(PLUGIN, "Error while invoking getSurfaceView method: %s", e);
        }
        return null;
    }

    /**
     * Attempts to resolve the UnityPlayer instance using multiple strategies.
     * First tries IUnityPlayerSupport interface, then reflection, then falls back to UI hierarchy search.
     *
     * @param activity The current activity
     * @return The UnityPlayer instance if found, null otherwise
     */
    @Nullable
    private static UnityPlayer resolveUnityPlayer(Activity activity) {
        // First try the Unity 6000.0+ interface
        UnityPlayer unityPlayer = resolveUnityPlayerViaInterface(activity);
        if (unityPlayer != null) {
            return unityPlayer;
        }

        // Fall back to reflection
        unityPlayer = resolveUnityPlayerWithReflection(activity);
        if (unityPlayer != null) {
            return unityPlayer;
        }

        // Last resort: UI hierarchy search (for older Unity versions where UnityPlayer extends View)
        return resolveUnityPlayerUiSearch(activity);
    }

    /**
     * Attempts to resolve the UnityPlayer instance via the IUnityPlayerSupport interface.
     * This is the preferred method for Unity 6000.0+
     *
     * @param activity The current activity
     * @return The UnityPlayer instance if activity implements IUnityPlayerSupport, null otherwise
     */
    @Nullable
    private static UnityPlayer resolveUnityPlayerViaInterface(Activity activity) {
        try {
            // Check if activity implements IUnityPlayerSupport interface (Unity 6000.0+)
            Class<?> interfaceClass = Class.forName("com.unity3d.player.IUnityPlayerSupport");
            if (interfaceClass.isInstance(activity)) {
                Method getPlayerMethod = interfaceClass.getMethod("getUnityPlayerConnection");
                Object result = getPlayerMethod.invoke(activity);
                if (result instanceof UnityPlayer) {
                    Log.d(PLUGIN, "Successfully resolved UnityPlayer via IUnityPlayerSupport interface");
                    return (UnityPlayer) result;
                }
            }
        } catch (ClassNotFoundException e) {
            // IUnityPlayerSupport doesn't exist in older Unity versions, this is expected
            Log.d(PLUGIN, "IUnityPlayerSupport interface not found, trying legacy methods");
        } catch (NoSuchMethodException e) {
            Log.d(PLUGIN, "getUnityPlayerConnection method not found: %s", e.getMessage());
        } catch (Exception e) {
            Log.e(PLUGIN, "Unable to resolve UnityPlayer via IUnityPlayerSupport interface: %s", e);
        }
        return null;
    }

    /**
     * Attempts to resolve the UnityPlayer instance using reflection.
     * Looks for the mUnityPlayer field in the activity class.
     *
     * @param activity The current activity
     * @return The UnityPlayer instance if found through reflection, null otherwise
     */
    @Nullable
    private static UnityPlayer resolveUnityPlayerWithReflection(Activity activity) {
        try {
            @SuppressLint("PrivateApi")
            Field unityPlayerField = activity.getClass().getDeclaredField("mUnityPlayer");
            unityPlayerField.setAccessible(true);
            Object fieldValue = unityPlayerField.get(activity);
            if (fieldValue instanceof UnityPlayer) {
                return (UnityPlayer) fieldValue;
            } else {
                Log.e(PLUGIN, "Unable to resolve Unity player: mUnityPlayer field is not of type UnityPlayer");
            }
        } catch (NoSuchFieldException e) {
            Log.d(PLUGIN, "Unable to resolve Unity player: could not find mUnityPlayer field: %s", e);
        } catch (IllegalAccessException e) {
            Log.e(PLUGIN, "Unable to resolve Unity player: could not access mUnityPlayer field: %s", e);
        } catch (Exception e) {
            Log.e(PLUGIN, "Unable to resolve Unity player: unexpected error while getting UnityPlayer instance: %s", e);
        }
        return null;
    }

    /**
     * Initiates a UI hierarchy search for the UnityPlayer instance starting from the activity's root view.
     * Note: This method only works for older Unity versions where UnityPlayer extends View.
     *
     * @param activity The current activity
     * @return The UnityPlayer instance if found in the UI hierarchy, null otherwise
     */
    private static UnityPlayer resolveUnityPlayerUiSearch(Activity activity) {
        return resolveUnityPlayerUiSearch(UIUtils.getRootViewGroup(activity));
    }

    /**
     * Recursively searches through the view hierarchy to find the UnityPlayer instance.
     * Note: This method only works for older Unity versions where UnityPlayer extends View.
     *
     * @param root The root ViewGroup to start the search from
     * @return The UnityPlayer instance if found in the view hierarchy, null otherwise
     */
    private static UnityPlayer resolveUnityPlayerUiSearch(ViewGroup root) {
        // Cast to Object for binary compatibility with different Unity versions.
        // In some older Unity versions, UnityPlayer extends View class.
        // In other newer Unity versions, UnityPlayer no longer extends View, so we need to use Object
        // to avoid compilation errors while maintaining runtime compatibility.
        Object rootObject = root;

        if (rootObject instanceof UnityPlayer) {
            return (UnityPlayer) rootObject;
        }

        for (int i = 0; i < root.getChildCount(); ++i) {
            // Same binary compatibility approach for child views.
            Object child = root.getChildAt(i);
            if (child instanceof UnityPlayer) {
                return (UnityPlayer) child;
            }

            if (child instanceof ViewGroup) {
                UnityPlayer player = resolveUnityPlayerUiSearch((ViewGroup) child);
                if (player != null) {
                    return player;
                }
            }
        }

        return null;
    }
}
