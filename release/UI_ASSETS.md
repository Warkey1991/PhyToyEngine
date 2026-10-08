# ToviCam camera artwork

The six camera_art_*.png resources are decorative product illustrations generated with the built-in imagegen tool on 2026-10-07, using the supplied png screen designs as visual references. They have transparent RGBA backgrounds and no embedded labels, prices, lock states or brand text. All interactive state and typography is rendered by native views.

The artwork is not a sample of the camera engine's photo output. Existing original/processed demonstration scenes and their illustrative disclosures remain separate. No engine profile, shader or capture asset was changed for this UI refactor.

CameraArtworkView shares a six-entry bitmap cache, decodes at half source resolution on a dedicated UI-art worker and fits the image without distortion. Style cards and purchase pages reuse the same resource; changing page or style does not recreate the camera TextureView or Surface.

Resources: camera_art_dh_color.png, camera_art_dh_mono.png, camera_art_digital.png, camera_art_plastic.png, camera_art_street.png and camera_art_fisheye.png under android/sample/src/main/res/drawable-nodpi.
