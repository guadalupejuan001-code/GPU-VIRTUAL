#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace xgpu {

// Direct3D homogeneous clip positions with an RGBA8 vertex color.
// Vertex and raster order are observable, even when triangles overlap.
struct GuestVertex {
  float x, y, z, w;
  std::uint32_t rgba;
};
static_assert(sizeof(GuestVertex) == 20);

struct GuestTrianglePlan {
  std::uint64_t revision = 0;
  std::size_t source_triangles = 0;
  std::vector<GuestVertex> vertices;
};

class GuestTriangleWork {
 public:
  static constexpr std::size_t max_vertices = 12288;

  GuestTrianglePlan plan(const std::vector<GuestVertex>& vertices) {
    if (vertices.size() > max_vertices || vertices.size() % 3)
      throw std::invalid_argument("Invalid guest triangle count");
    GuestTrianglePlan result;
    result.source_triangles = vertices.size() / 3;
    result.vertices.reserve(vertices.size());
    for (std::size_t i = 0; i < vertices.size(); i += 3) {
      const auto* tri = vertices.data() + i;
      for (unsigned j = 0; j < 3; ++j) {
        const auto& v = tri[j];
        if (!std::isfinite(v.x) || !std::isfinite(v.y) || !std::isfinite(v.z) ||
            !std::isfinite(v.w) || v.w <= 0 || v.w > 1024.0f ||
            std::abs(v.x) > 1024.0f || std::abs(v.y) > 1024.0f || std::abs(v.z) > 1024.0f)
          throw std::invalid_argument("Invalid homogeneous guest vertex");
      }
      const auto outside = [&tri](auto plane) {
        return plane(tri[0]) && plane(tri[1]) && plane(tri[2]);
      };
      // Strict rejection on one common clip plane. Keep all triangles which
      // may intersect the view, including the edges and partially clipped ones.
      if (outside([](auto v) { return v.x < -v.w; }) ||
          outside([](auto v) { return v.x > v.w; }) ||
          outside([](auto v) { return v.y < -v.w; }) ||
          outside([](auto v) { return v.y > v.w; }) ||
          outside([](auto v) { return v.z < 0; }) ||
          outside([](auto v) { return v.z > v.w; })) continue;
      result.vertices.insert(result.vertices.end(), tri, tri + 3);
    }
    result.revision = ++revision_;
    return result;
  }
 private:
  std::uint64_t revision_ = 0;
};

}  // namespace xgpu
