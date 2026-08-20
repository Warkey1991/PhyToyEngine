#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace phytoy {

std::array<uint8_t, 32> sha256(std::span<const uint8_t> input);

}  // namespace phytoy

