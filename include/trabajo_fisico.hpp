#pragma once
#include <algorithm>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace xgpu {
// GPU Virtual authority for work that may reach a physical raster GPU.
// It does not draw. It records exact dependency revisions, filters work whose
// projected contribution is below the explicitly configured visual criterion,
// and publishes measurable denominators for physical-work reduction.
struct PhysicalCandidate {
  std::uint64_t id=0;
  std::uint64_t transform_revision=0;
  std::uint64_t material_revision=0;
  std::uint32_t mesh=0;
  float camera_diameter_px=0;
  float shadow_diameter_texels=0;
  bool camera_visible=false;
  bool shadow_visible=false;
};

struct PhysicalWorkPlan {
  std::uint64_t revision=0;
  std::uint64_t dependency_revision=0;
  std::vector<std::uint32_t> camera_indices;
  std::vector<std::uint32_t> shadow_indices;
  std::uint64_t source_objects=0;
  std::uint64_t camera_candidates=0,shadow_candidates=0;
  std::uint64_t camera_rejected=0,shadow_rejected=0;
  std::uint64_t changed_objects=0,reused_objects=0;
  std::uint64_t baseline_vertex_invocations=0,planned_vertex_invocations=0;
  std::uint64_t baseline_fragment_estimate=0,planned_fragment_estimate=0;
  std::uint64_t baseline_instance_bytes=0,planned_instance_bytes=0;
  std::uint64_t camera_baseline_vertices=0,camera_planned_vertices=0;
  std::uint64_t shadow_baseline_vertices=0,shadow_planned_vertices=0;
  std::uint64_t camera_baseline_fragments=0,camera_planned_fragments=0;
  std::uint64_t shadow_baseline_fragments=0,shadow_planned_fragments=0;
  std::uint64_t camera_baseline_bytes=0,camera_planned_bytes=0;
  std::uint64_t shadow_baseline_bytes=0,shadow_planned_bytes=0;
  std::uint64_t baseline_draw_work=0,planned_draw_work=0;
  bool compact_enabled=true,reuse_enabled=true,visibility_enabled=true;
};

struct FramebufferSpan {
  std::uint32_t left, right; // Exclusive end. An empty row has left >= right.
};
struct FramebufferRect {
  std::uint32_t x, y, width, height;
};
struct FramebufferWorkPlan {
  std::uint64_t revision = 0;
  std::vector<FramebufferRect> rectangles;
  std::size_t pixels = 0, packet_words = 0;
  bool full_frame = false;
};

// Exact 2D transfer policy for a materialized guest display. The consumer
// supplies spans of pixels which differ from the last published frame.
// Preserve each changed pixel, merge identical adjacent spans, and cap
// the work of rebuilding a fragmented update at one complete frame.
class FramebufferWorkAuthority {
 public:
  static constexpr std::uint32_t kStripeHeight = 32;
  static constexpr std::size_t kRectangleLimit = 128;
  static constexpr std::size_t kRectangleHeaderWords = 4;

  FramebufferWorkPlan plan(std::uint32_t width, const std::vector<FramebufferSpan>& rows) {
    if (!width || rows.empty() || rows.size() > UINT32_MAX ||
        std::uint64_t(width) * rows.size() > std::numeric_limits<std::size_t>::max() -
            (1 + kRectangleHeaderWords * (kRectangleLimit + 1)) ||
        rows.size() > kStripeHeight * kRectangleLimit)
      throw std::invalid_argument("framebuffer work geometry");
    FramebufferWorkPlan out;
    bool fragmented = false;
    for (std::uint32_t y = 0; y < rows.size(); ++y) {
      const auto [left, right] = rows[y];
      if (left >= right) continue;
      if (right > width) throw std::out_of_range("framebuffer work span");
      if (fragmented) continue;
      out.pixels += right - left;
      if (!out.rectangles.empty()) {
        auto& last = out.rectangles.back();
        if (last.x == left && last.width == right - left && last.y + last.height == y) {
          ++last.height;
          continue;
        }
      }
      out.rectangles.push_back({left, y, right - left, 1});
      fragmented = out.rectangles.size() > kRectangleLimit;
    }
    if (out.rectangles.empty()) return out;
    const auto height = static_cast<std::uint32_t>(rows.size());
    const auto full_pixels = std::size_t(width) * height;
    const auto full_stripes = (height + kStripeHeight - 1) / kStripeHeight;
    if (fragmented || out.pixels + kRectangleHeaderWords * out.rectangles.size() >=
                          full_pixels + kRectangleHeaderWords * full_stripes ||
        out.pixels == full_pixels) {
      out.rectangles.clear();
      for (std::uint32_t y = 0; y < height; y += kStripeHeight)
        out.rectangles.push_back({0, y, width, std::min(kStripeHeight, height - y)});
      out.pixels = full_pixels;
      out.full_frame = true;
    }
    out.packet_words = 1 + kRectangleHeaderWords * out.rectangles.size() + out.pixels;
    out.revision = ++revision_;
    return out;
  }
 private:
  std::uint64_t revision_ = 0;
};

class PhysicalWorkAuthority {
 public:
  struct Revision { std::uint64_t transform=0,material=0; };
  void reset(){previous_.clear();revision_=0;}
  PhysicalWorkPlan plan(const std::vector<PhysicalCandidate>& input,
                        const std::vector<std::uint32_t>& mesh_vertices,
                        std::uint64_t dependency_revision,
                        bool compact_enabled,bool reuse_enabled,bool visibility_enabled,
                        float camera_pixel_threshold=.5f,float shadow_texel_threshold=.5f) {
    if(camera_pixel_threshold<0||shadow_texel_threshold<0)throw std::invalid_argument("negative contribution threshold");
    PhysicalWorkPlan out;out.revision=++revision_;out.dependency_revision=dependency_revision;
    out.source_objects=input.size();out.compact_enabled=compact_enabled;out.reuse_enabled=reuse_enabled;out.visibility_enabled=visibility_enabled;
    std::unordered_map<std::uint64_t,Revision> next;next.reserve(input.size()*2+1);
    for(std::uint32_t i=0;i<input.size();++i){
      const auto& c=input[i];if(c.mesh>=mesh_vertices.size())throw std::out_of_range("physical work mesh");
      const Revision r{c.transform_revision,c.material_revision};next.emplace(c.id,r);
      const auto found=previous_.find(c.id);const bool same=found!=previous_.end()&&found->second.transform==r.transform&&found->second.material==r.material;
      if(reuse_enabled&&same)++out.reused_objects;else ++out.changed_objects;
      const std::uint64_t vertices=mesh_vertices[c.mesh];
      if(c.camera_visible){
        ++out.camera_candidates;out.baseline_vertex_invocations+=vertices;out.camera_baseline_vertices+=vertices;out.baseline_draw_work+=vertices;out.camera_baseline_bytes+=112ULL;
        const auto area=estimate_area(c.camera_diameter_px);out.baseline_fragment_estimate+=area;out.camera_baseline_fragments+=area;
        if(!visibility_enabled||c.camera_diameter_px>=camera_pixel_threshold){out.camera_indices.push_back(i);out.planned_vertex_invocations+=vertices;out.camera_planned_vertices+=vertices;out.planned_draw_work+=vertices;out.planned_fragment_estimate+=area;out.camera_planned_fragments+=area;out.camera_planned_bytes+=(compact_enabled&&c.mesh!=3?44ULL:112ULL);}else ++out.camera_rejected;
      }
      if(c.shadow_visible){
        ++out.shadow_candidates;out.baseline_vertex_invocations+=vertices;out.shadow_baseline_vertices+=vertices;out.baseline_draw_work+=vertices;out.shadow_baseline_bytes+=112ULL;
        const auto area=estimate_area(c.shadow_diameter_texels);out.baseline_fragment_estimate+=area;out.shadow_baseline_fragments+=area;
        if(!visibility_enabled||c.shadow_diameter_texels>=shadow_texel_threshold){out.shadow_indices.push_back(i);out.planned_vertex_invocations+=vertices;out.shadow_planned_vertices+=vertices;out.planned_draw_work+=vertices;out.planned_fragment_estimate+=area;out.shadow_planned_fragments+=area;out.shadow_planned_bytes+=(compact_enabled&&c.mesh!=3?44ULL:112ULL);}else ++out.shadow_rejected;
      }
    }
    // Previous instancing transported 28 floats/object. Compact transport uses
    // 11 exact floats (transform/scale/color). Billboard mesh 3 retains its exact
    // camera-facing matrix because a yaw-only payload would not be equivalent.
    out.baseline_instance_bytes=out.camera_baseline_bytes+out.shadow_baseline_bytes;
    out.planned_instance_bytes=out.camera_planned_bytes+out.shadow_planned_bytes;
    previous_.swap(next);
    return out;
  }
 private:
  static std::uint64_t estimate_area(float diameter){
    if (!(diameter > 0)) return 0;
    const double r = double(diameter) * .5;
    const double a = 3.14159265358979323846 * r * r;
    return a >= double(std::numeric_limits<std::uint64_t>::max())
        ? std::numeric_limits<std::uint64_t>::max()
        : std::uint64_t(a + .5);
  }
  std::unordered_map<std::uint64_t,Revision> previous_;
  std::uint64_t revision_=0;
};
} // namespace xgpu
