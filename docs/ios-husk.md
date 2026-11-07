# OLFX for iPhone

- UI: SwiftUI (`ios/`), generated with XcodeGen, built by `.github/workflows/ios.yml` (unsigned IPA, sideload with SideStore/AltStore).
- Game runtime: [Husk](https://github.com/Leviidev/Husk) (GPL-2.0-or-later) runs Android 64-bit code on iOS. OLFX embeds/links it, so the iOS app is GPL-2.0 too and cannot go on the App Store.
- Tracking tab "Hand-Tracking": engine picker (Apple Vision / MediaPipe) and 6DoF toggle (ARKit world tracking).
- TODO: link Husk as a submodule, feed hand/6DoF poses into the OpenXR layer inside Husk, MediaPipe iOS engine.
