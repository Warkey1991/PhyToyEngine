# Android preview GPU regression checks

These standalone programs use synthetic inputs. They do not open a camera, app,
output surface or photo library, and are not included in the application build.
Use an isolated Android emulator or dedicated test device. The regular native
suite also checks radius rounding and the SPIR-V workgroup memory budget.

`test_preview_shaders.cpp` compares the new generic and specialized optics/ISP
pipelines against `optics-baseline.spv` and `isp-baseline.spv`. If baseline files
are absent, it compares against the current generic pipelines. Supply a shader
directory containing `optics.spv` and `isp.spv`, compiled with the same `glslc
--target-env=vulkan1.1 -O` options used by the app, plus `optics_preview.spv` and
`isp_preview.spv` built with `-DPHYTOY_PREVIEW_SPECIALIZATION=1`. The preview
variants specialize immutable profile control flow; capture uses the unchanged
generic binaries. The cases cover every supported
halo, rectangular PSFs, filter boundaries, inactive and negative sharpening,
all Bayer patterns, monochrome, single pixels and partial workgroups. It checks
finite output, output guards and absolute error <= 2e-6, and reports bit changes.

`test_preview_ahb.cpp` links against the core library extracted from the APK being
tested. Supply that APK's profile directory. It checks 18-buffer reuse, 32-entry
LRU eviction, buffer removal/clear, preview size changes including 960x720, and
bit-identical capture output before and after preview, with and without stage
callbacks. It explicitly selects the Vulkan backend.

Example builds from the repository root, with an NDK compiler and extracted APK
runtime libraries in `RUNTIME_DIR`:

```sh
"$TASK_NDK_CXX" -std=c++20 -O2 -Wall -Wextra -Wno-missing-field-initializers \
  -static-libstdc++ tools/test_preview_shaders.cpp -lvulkan -o test_preview_shaders
"$TASK_NDK_CXX" -std=c++20 -O2 -Wall -Wextra tools/test_preview_ahb.cpp \
  -Icore/include -L"$RUNTIME_DIR" -lphytoy_core -landroid -o test_preview_ahb
```

Push the executables, SPIR-V, profiles, `libphytoy_core.so` and
`libc++_shared.so` to one directory on the isolated test device. Run
`test_preview_shaders DIRECTORY`, then `LD_LIBRARY_PATH=DIRECTORY
test_preview_ahb DIRECTORY`. Always use an explicit ADB serial.

The emulator confirms shader equivalence and lifecycle behavior. It does not
measure real camera/HAL behavior, display latency or Samsung GPU performance.
Real-device throughput must use received/rendered/presented counter deltas over
one stable time window, with the same style, scene and thermal conditions.
