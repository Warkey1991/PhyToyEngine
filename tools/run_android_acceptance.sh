#!/bin/sh
set -eu

ADB_BIN=${ADB_BIN:-adb}
ANDROID_BUILD_DIR=${ANDROID_BUILD_DIR:-build-android-arm64}
HOST_BUILD_DIR=${HOST_BUILD_DIR:-build}
REPORT_DIR=${REPORT_DIR:-reports/synthetic_alpha_candidate}
STATISTICS_ONLY=${STATISTICS_ONLY:-0}
DEVICE_DIR=/data/local/tmp/phytoy_synthetic_alpha

mkdir -p "$REPORT_DIR"
python3 tools/collect_android_device_info.py \
  --adb "$ADB_BIN" \
  --contract acceptance/synthetic_alpha_v1.json \
  --output "$REPORT_DIR/device.json"
"$ADB_BIN" shell mkdir -p "$DEVICE_DIR"
"$ADB_BIN" push "$ANDROID_BUILD_DIR/libphytoy_core.so" "$DEVICE_DIR/libphytoy_core.so"
"$ADB_BIN" push "$ANDROID_BUILD_DIR/phytoy_benchmark" "$DEVICE_DIR/phytoy_benchmark"
"$ADB_BIN" push "$HOST_BUILD_DIR/profiles/host_generic_srgb.ptp" "$DEVICE_DIR/host.ptp"
"$ADB_BIN" push "$HOST_BUILD_DIR/profiles/toy_phytoy_digital_01_v1.ptp" "$DEVICE_DIR/toy.ptp"
"$ADB_BIN" push "$HOST_BUILD_DIR/profiles/toy_fixed_focus_conformance.ptp" "$DEVICE_DIR/conformance.ptp"
"$ADB_BIN" shell chmod 755 "$DEVICE_DIR/phytoy_benchmark"

run_benchmark() {
    output_file=$1
    shift
    "$ADB_BIN" shell "cd $DEVICE_DIR && LD_LIBRARY_PATH=$DEVICE_DIR ./phytoy_benchmark --host host.ptp --toy toy.ptp --backend vulkan --input ahb $*" > "$output_file"
}

run_conformance() {
    name=$1
    backend=$2
    input=$3
    "$ADB_BIN" shell "cd $DEVICE_DIR && LD_LIBRARY_PATH=$DEVICE_DIR ./phytoy_benchmark --host host.ptp --toy conformance.ptp --backend $backend --input $input --width 22 --height 18 --warmup 1 --frames 1 --seed 1233 --dump-stages $DEVICE_DIR/$name" \
      > "$REPORT_DIR/${name}_runtime.json"
    "$ADB_BIN" pull "$DEVICE_DIR/$name" "$REPORT_DIR/$name" >/dev/null
    PYTHONPATH=reference python3 tools/compare_device_stages.py \
      --synthetic --width 22 --height 18 \
      --stages "$REPORT_DIR/$name" \
      --host-profile profiles/authoring/host_generic_srgb.json \
      --toy-profile profiles/authoring/toy_fixed_focus_conformance.json \
      --seed 1234 \
      --contract acceptance/synthetic_alpha_v1.json \
      > "$REPORT_DIR/${name}_conformance.json"
}

if [ "$STATISTICS_ONLY" != 1 ]; then
    run_benchmark "$REPORT_DIR/preview_720p.json" --width 1280 --height 720 --warmup 10 --frames 300 --seed 20260821
    run_benchmark "$REPORT_DIR/final_12mp.json" --width 4000 --height 3000 --warmup 2 --frames 5 --seed 20260821
    run_benchmark "$REPORT_DIR/long_run_720p.json" --width 1280 --height 720 --warmup 10 --frames 10000 --seed 20260821

    # Compare all five deterministic stages against the same Python truth model. The
    # AHardwareBuffer case covers the external-image normalization pass as well as
    # optics, sensor, and ISP; warmup populates its import and debug-resource caches.
    "$ADB_BIN" shell rm -rf \
      "$DEVICE_DIR/conformance_cpu" \
      "$DEVICE_DIR/conformance_vulkan_f32" \
      "$DEVICE_DIR/conformance_vulkan_ahb"
    run_conformance conformance_cpu cpu f32
    run_conformance conformance_vulkan_f32 vulkan f32
    run_conformance conformance_vulkan_ahb vulkan ahb
fi

run_sensor_statistics() {
    name=$1
    backend=$2
    input=$3
    runtime_report="$REPORT_DIR/${name}_runtime.json"
    "$ADB_BIN" shell "cd $DEVICE_DIR && LD_LIBRARY_PATH=$DEVICE_DIR ./phytoy_benchmark --host host.ptp --toy toy.ptp --backend $backend --input $input --width 8 --height 8 --warmup 1 --frames 128 --seed 0 --constant-input-u8 82 --sensor-statistics" \
      > "$runtime_report"
    PYTHONPATH=reference python3 tools/compare_device_sensor_statistics.py \
      --report "$runtime_report" \
      --host-profile profiles/authoring/host_generic_srgb.json \
      --toy-profile profiles/authoring/toy_phytoy_digital_01_v1.json \
      --contract acceptance/synthetic_alpha_v1.json \
      > "$REPORT_DIR/${name}_comparison.json"
}

run_sensor_statistics statistics_cpu cpu f32
run_sensor_statistics statistics_vulkan_f32 vulkan f32
run_sensor_statistics statistics_vulkan_ahb vulkan ahb

run_repeatability() {
    name=$1
    backend=$2
    input=$3
    "$ADB_BIN" shell "cd $DEVICE_DIR && LD_LIBRARY_PATH=$DEVICE_DIR ./phytoy_benchmark --host host.ptp --toy toy.ptp --backend $backend --input $input --width 8 --height 8 --warmup 1 --frames 4 --seed 424242 --constant-input-u8 82 --sensor-statistics --fixed-seed" \
      > "$REPORT_DIR/${name}.json"
}

run_repeatability repeatability_cpu cpu f32
run_repeatability repeatability_vulkan_f32 vulkan f32
run_repeatability repeatability_vulkan_ahb vulkan ahb

python3 tools/evaluate_acceptance.py \
  --contract acceptance/synthetic_alpha_v1.json \
  --preview "$REPORT_DIR/preview_720p.json" \
  --final "$REPORT_DIR/final_12mp.json" \
  --long-run "$REPORT_DIR/long_run_720p.json" \
  --cpu-conformance "$REPORT_DIR/conformance_cpu_conformance.json" \
  --vulkan-f32-conformance "$REPORT_DIR/conformance_vulkan_f32_conformance.json" \
  --vulkan-ahb-conformance "$REPORT_DIR/conformance_vulkan_ahb_conformance.json" \
  --cpu-statistics "$REPORT_DIR/statistics_cpu_comparison.json" \
  --vulkan-f32-statistics "$REPORT_DIR/statistics_vulkan_f32_comparison.json" \
  --vulkan-ahb-statistics "$REPORT_DIR/statistics_vulkan_ahb_comparison.json" \
  --cpu-repeatability "$REPORT_DIR/repeatability_cpu.json" \
  --vulkan-f32-repeatability "$REPORT_DIR/repeatability_vulkan_f32.json" \
  --vulkan-ahb-repeatability "$REPORT_DIR/repeatability_vulkan_ahb.json" \
  > "$REPORT_DIR/evaluation.json"
