//
//  LULittleHelper.m
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


#import "LULittleHelper.h"

#import "Lunar.h"

static UIInterfaceOrientationMask LUInterfaceOrientationMaskFromOrientation(UIInterfaceOrientation orientation)
{
    switch (orientation)
    {
        case UIInterfaceOrientationPortrait:
            return UIInterfaceOrientationMaskPortrait;
        case UIInterfaceOrientationPortraitUpsideDown:
            return UIInterfaceOrientationMaskPortraitUpsideDown;
        case UIInterfaceOrientationLandscapeLeft:
            return UIInterfaceOrientationMaskLandscapeLeft;
        case UIInterfaceOrientationLandscapeRight:
            return UIInterfaceOrientationMaskLandscapeRight;
        default:
            return UIInterfaceOrientationMaskAll;
    }
}

// UIAlertView raises an exception on iOS 26(and on UIScene based apps before that), so the alert
// has to be presented by the controller that shows it: the console lives in its own LUWindow and
// guessing a window instead would pick a wrong one(there are up to 4 of them on the same level).
void LUDisplayAlertView(UIViewController *presenter, NSString *title, NSString *message)
{
    while (presenter.presentedViewController != nil) {
        presenter = presenter.presentedViewController;
    }

    UIAlertController *alert = [UIAlertController alertControllerWithTitle:title
                                                                  message:message
                                                           preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:@"OK"
                                              style:UIAlertActionStyleCancel
                                            handler:nil]];
    [presenter presentViewController:alert animated:YES completion:nil];
}

UIWindowScene* LUGetWindowScene() {
    if (@available(iOS 13.0, *)) {
        UIWindow *unityWindow = UnityGetMainWindow();
        if (unityWindow) {
            return unityWindow.windowScene;
        }
    }
    return nil;
}

CGRect LUGetScreenBounds() {
    UIWindowScene *windowScene = LUGetWindowScene();
    return windowScene ? windowScene.coordinateSpace.bounds : [UIScreen mainScreen].bounds;
}

UIInterfaceOrientation LUGetInterfaceOrientation()
{
    UIWindowScene *windowScene = LUGetWindowScene();
    if (windowScene != nil) {
        return windowScene.interfaceOrientation;
    }

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    return [UIApplication sharedApplication].statusBarOrientation;
#pragma clang diagnostic pop
}

UIInterfaceOrientationMask LUGetSupportedInterfaceOrientations(void)
{
    // Console lives in a separate UIWindow on Unity's UIWindowScene. On iOS 16+
    // that window's root VC orientations affect the whole scene, so mirror Unity
    // instead of hardcoding a mask (e.g. landscape-only would break portrait games).
    UIWindow *unityWindow = UnityGetMainWindow();
    UIViewController *unityRoot = unityWindow.rootViewController;
    // Avoid recursion if the console window is currently key (standalone app stub).
    if (unityRoot != nil && ![unityRoot isKindOfClass:[LUViewController class]]) {
        return [unityRoot supportedInterfaceOrientations];
    }

    return LUInterfaceOrientationMaskFromOrientation(LUGetInterfaceOrientation());
}

BOOL LUIsPortraitInterfaceOrientation(void)
{
    return UIInterfaceOrientationIsPortrait(LUGetInterfaceOrientation());
}

BOOL LUIsLandscapeInterfaceOrientation()
{
    return UIInterfaceOrientationIsLandscape(LUGetInterfaceOrientation());
}
