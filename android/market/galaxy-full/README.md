# Galaxy Full icon artwork

Final source: `camera-master.png`. Store asset: `galaxy-store-icon-512.png`.

The user selected the existing yellow-and-black camera logo, requested removal of the Tovi lettering, and asked for the camera to be centered. The final master was edited with the built-in image_gen tool using `android/黄色立体相机与 Tovi 标志.png` as the high-resolution reference. The earlier new-camera concept is not used.

Launcher density variants preserve this square composition. Regenerate them with `python3 tools/prepare_galaxy_full_icons.py` (Pillow required). Adaptive icons use a separate simple camera silhouette for Android themed-icon mode; regular launcher icons use the approved raster artwork.

## Final edit prompt

Use case: precise-object-edit. Edit target: the supplied high-resolution existing app icon. Make ONLY these two changes: completely remove the yellow 'Tovi' wordmark below the camera, then reposition the EXISTING yellow-and-black camera symbol to the exact horizontal and vertical center of the square canvas. Preserve the camera's exact shape, silhouette, proportions, size, materials, yellow colors, black grip, round black lens with yellow ring and white reflections, shutter button and flash. Do not redesign, reinterpret, add details, or change the camera angle. Preserve the existing near-black square background, seamless where the removed letters were. Final production app icon: one centered existing camera on near-black, no text, no letters, no watermark, no rounded outer tile border, no other objects. Keep the original square canvas.
