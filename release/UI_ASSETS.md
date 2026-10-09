# ToviCam UI artwork

The supplied designs in the workspace `png/` directory are the visual reference. Three unmodified source designs are bundled as `design_plastic_reference.png`, `design_street_reference.png` and `design_camera_reference.png` under `android/sample/src/main/res/drawable-nodpi/`. `CameraArtworkView` decodes named decorative regions with `BitmapRegionDecoder` on its dedicated worker; app text, actions, prices, focus, sliders and ownership badges remain native views.

- Free camera tiles use the camera images in the Plastic design's lower strip.
- Plastic and Street hero artwork use image-only regions without baked titles, prices or action controls.
- Plastic's film image and Street's three examples come from the supplied designs and are explicitly disclosed as design illustrations, including in accessibility descriptions.
- Street and Fisheye tiles combine clean sky regions with their existing transparent camera layers, avoiding the designs' baked padlocks. All lock badges use actual billing entitlement.
- Free-style heroes use the existing high-resolution transparent camera illustrations.

The six `camera_art_*.webp` resources are decorative transparent camera layers. The twelve `style_source_*.webp` / `style_sample_*.webp` resources remain a separate original/effect comparison, behind the native compare action. No design image is used by the capture engine, as a saved photo, or as a fake gallery entry.

The shared bitmap cache is limited to 12 MiB by allocation size. Region decoding runs off the main thread; stale results cannot replace a different artwork mode. Decorative images fill their cards. Actual captured photographs continue to fit their complete boundaries in the unchanged zoom/photo view.
