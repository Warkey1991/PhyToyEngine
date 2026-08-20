#pragma once

#include "image.hpp"
#include "phytoy/phytoy.h"
#include "profile.hpp"

#include <cstdint>

namespace phytoy {

Image normalize_cpu(const pte_frame_f32_t& frame, const HostProfile& host);

Image render_cpu(
    const pte_frame_f32_t& frame,
    const HostProfile& host,
    const ToyProfile& toy,
    uint64_t seed,
    pte_stage_callback_f32 stage_callback,
    void* stage_user_data);

}  // namespace phytoy
