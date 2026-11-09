#pragma once
#include <algorithm>
#include <cstdint>
#include <limits>
#include <numeric>
#include <stdexcept>

namespace xgpu {

struct GraphicsWorkRelation {
  std::uint32_t count = 0;
  std::uint32_t base = 0;
  std::uint32_t stride = 0;
  std::uint32_t mask = 0;
  float scale = 0.0f;
  std::uint32_t identity_period = 0;
  std::uint32_t logical_objects_per_lane = 1;
  std::uint64_t relation_hash = 0;
  std::uint64_t dependency_revision = 0;
  bool casts_shadow = false;
};

inline bool valid(const GraphicsWorkRelation& r) {
  const std::uint64_t modulus = std::uint64_t(r.mask) + 1u;
  return r.count > 0 && r.stride > 0 && r.scale > 0.0f &&
         modulus <= std::uint64_t(std::numeric_limits<std::uint32_t>::max()) + 1u &&
         (modulus & (modulus - 1u)) == 0u && r.logical_objects_per_lane > 0;
}

inline std::uint32_t phase_period(const GraphicsWorkRelation& r) {
  if (!valid(r)) throw std::invalid_argument("invalid graphics work relation");
  const std::uint64_t modulus64 = std::uint64_t(r.mask) + 1u;
  if (modulus64 > std::numeric_limits<std::uint32_t>::max()) return 0;
  const auto modulus = std::uint32_t(modulus64);
  const auto stride = r.stride % modulus;
  return stride ? modulus / std::gcd(stride, modulus) : 1u;
}

inline std::uint32_t exact_identity_period(const GraphicsWorkRelation& r) {
  const auto phase = phase_period(r);
  if (!phase || !r.identity_period) return 0;
  const auto g = std::gcd(phase, r.identity_period);
  const std::uint64_t lcm = std::uint64_t(phase / g) * r.identity_period;
  return lcm <= std::numeric_limits<std::uint32_t>::max() ? std::uint32_t(lcm) : 0;
}

inline std::uint32_t exact_representative_count(const GraphicsWorkRelation& r) {
  const auto period = exact_identity_period(r);
  return period ? std::min(r.count, period) : r.count;
}

struct GraphicsWorkDelta {
  std::uint64_t previous_relation_hash = 0;
  std::uint64_t current_relation_hash = 0;
  std::uint64_t previous_dependency_revision = 0;
  std::uint64_t current_dependency_revision = 0;
  bool geometry_changed = true;
  bool material_changed = true;
  bool lighting_changed = true;
  bool visibility_changed = true;
};

inline bool exact_reuse_allowed(const GraphicsWorkDelta& d) {
  return d.previous_relation_hash == d.current_relation_hash &&
         d.previous_dependency_revision == d.current_dependency_revision &&
         !d.geometry_changed && !d.material_changed && !d.lighting_changed &&
         !d.visibility_changed;
}

struct PhysicalWorkReduction {
  std::uint64_t baseline = 0;
  std::uint64_t candidate = 0;
  bool valid = false;
  double ratio = 0.0;
};

inline PhysicalWorkReduction pwr(std::uint64_t baseline, std::uint64_t candidate) {
  PhysicalWorkReduction out{baseline, candidate, baseline > 0, 0.0};
  if (out.valid) out.ratio = (double(baseline) - double(candidate)) / double(baseline);
  return out;
}

} // namespace xgpu
