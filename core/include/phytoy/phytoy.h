#ifndef PHYTOY_PHYTOY_H
#define PHYTOY_PHYTOY_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  if defined(PHYTOY_CORE_BUILD)
#    define PTE_API __declspec(dllexport)
#  else
#    define PTE_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__) || defined(__clang__)
#  define PTE_API __attribute__((visibility("default")))
#else
#  define PTE_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define PTE_ABI_VERSION 1u

typedef struct pte_engine pte_engine_t;

typedef enum pte_status {
    PTE_STATUS_OK = 0,
    PTE_STATUS_INVALID_ARGUMENT = 1,
    PTE_STATUS_IO_ERROR = 2,
    PTE_STATUS_PROFILE_ERROR = 3,
    PTE_STATUS_BUFFER_TOO_SMALL = 4,
    PTE_STATUS_INTERNAL_ERROR = 5
} pte_status_t;

typedef enum pte_pixel_format {
    PTE_PIXEL_FORMAT_SRGB_F32 = 1,
    PTE_PIXEL_FORMAT_YUV420_BT709_F32 = 2,
    PTE_PIXEL_FORMAT_RAW_BAYER_F32 = 3
} pte_pixel_format_t;

typedef enum pte_stage {
    PTE_STAGE_SCENE_LINEAR = 1,
    PTE_STAGE_TARGET_OPTICS = 2,
    PTE_STAGE_TARGET_SENSOR_DN = 3,
    PTE_STAGE_TARGET_ISP_LINEAR = 4,
    PTE_STAGE_OUTPUT_SRGB = 5
} pte_stage_t;

typedef enum pte_backend {
    PTE_BACKEND_CPU = 1,
    PTE_BACKEND_VULKAN = 2,
    PTE_BACKEND_AUTO = 3
} pte_backend_t;

typedef struct pte_const_plane_f32 {
    const float* data;
    uint32_t row_stride_floats;
} pte_const_plane_f32_t;

typedef struct pte_frame_f32 {
    uint32_t abi_version;
    pte_pixel_format_t format;
    uint32_t width;
    uint32_t height;
    /* sRGB: plane[0] is interleaved RGB. RAW: plane[0] is one channel.
       YUV420: plane[0] is Y and plane[1] is interleaved UV at half size. */
    pte_const_plane_f32_t plane[3];
    uint32_t yuv_full_range;
} pte_frame_f32_t;

typedef struct pte_output_f32 {
    float* data;
    size_t capacity_floats;
    uint32_t row_stride_floats;
} pte_output_f32_t;

typedef void (*pte_stage_callback_f32)(
    void* user_data,
    pte_stage_t stage,
    const float* data,
    uint32_t width,
    uint32_t height,
    uint32_t channels);

typedef struct pte_render_options {
    uint32_t abi_version;
    uint64_t seed;
    pte_stage_callback_f32 stage_callback;
    void* stage_user_data;
} pte_render_options_t;

PTE_API const char* pte_version_string(void);

/* Thread-local error for calls that fail before an engine exists, including create. */
PTE_API const char* pte_last_error(void);

PTE_API pte_status_t pte_engine_create(
    const char* host_profile_package_path,
    const char* toy_profile_package_path,
    pte_engine_t** out_engine);

PTE_API void pte_engine_destroy(pte_engine_t* engine);

PTE_API uint32_t pte_backend_is_available(pte_backend_t backend);

/* Backend selection is sticky for subsequent renders. CPU is the default. */
PTE_API pte_status_t pte_engine_set_backend(pte_engine_t* engine, pte_backend_t backend);

PTE_API pte_status_t pte_engine_render(
    pte_engine_t* engine,
    const pte_frame_f32_t* input,
    const pte_render_options_t* options,
    pte_output_f32_t* output);

PTE_API const char* pte_engine_last_error(const pte_engine_t* engine);

#ifdef __cplusplus
}
#endif

#endif
