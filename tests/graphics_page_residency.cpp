#include "graphics_page_residency.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {

using xgpu::GraphicsPageBinding;
using xgpu::GraphicsPageRequest;
using xgpu::GraphicsPageResidency;
using xgpu::GraphicsPageVersion;

void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

template <typename Error, typename Operation>
void rejects(Operation operation, const char* message) {
  try {
    operation();
  } catch (const Error&) {
    return;
  }
  throw std::runtime_error(message);
}

void verify_fences_and_retry() {
  const GraphicsPageVersion original{1ULL << 40, 4};
  const GraphicsPageVersion edited{original.page, 5};
  const GraphicsPageVersion other{original.page + 1, 0};
  GraphicsPageResidency pool(2);

  const std::vector<GraphicsPageRequest> aliases{{3, original}, {2, original}};
  auto plan = pool.prepare_frame(aliases, 0, 1);
  require(plan && plan->uploads.size() == 1 && plan->table_updates.size() == 2 &&
              plan->bindings[0].table_index == 2 && plan->bindings[1].table_index == 3 &&
              plan->bindings[0].slot == plan->bindings[1].slot,
          "two mappings of the same version must share one upload");
  const auto original_slot = plan->uploads.front().slot;
  pool.submit_frame();

  plan = pool.prepare_frame(aliases, 0, 2);
  require(plan && plan->uploads.empty() && plan->table_updates.empty(),
          "an unchanged frame must reuse the queued page and table");
  pool.submit_frame();

  plan = pool.prepare_frame({{2, edited}}, 0, 3);
  require(plan && plan->uploads.size() == 1 && plan->uploads.front().slot != original_slot &&
              plan->table_updates.size() == 1,
          "the live original version must survive an edit");
  pool.submit_frame();

  require(!pool.prepare_frame({{2, other}}, 0, 4), "in-flight slots must be protected");
  rejects<std::length_error>([&] {
    (void)pool.prepare_frame({{0, original}, {1, edited}, {2, other}}, 2, 4);
  }, "over-capacity frame must fail instead of waiting for a fence");
  rejects<std::invalid_argument>([&] {
    (void)pool.prepare_frame({{2, original}, {2, other}}, 2, 4);
  }, "duplicate table index must fail");

  plan = pool.prepare_frame({{2, other}}, 2, 4);
  require(plan && plan->uploads.size() == 1 && plan->uploads.front().slot == original_slot,
          "only the slot whose fence completed may be recycled");
  rejects<std::logic_error>([&] { (void)pool.prepare_frame({}, 2, 5); },
                            "overlapping plans must fail");
  pool.discard_frame(true);
  require(!pool.prepare_frame({{2, other}}, 2, 5), "a failed queued upload must hold its slot");

  plan = pool.prepare_frame({{2, edited}}, 2, 5);
  require(plan && plan->uploads.empty() && plan->table_updates.size() == 1,
          "a retry must restore the table and reuse unaffected storage");
  pool.submit_frame();

  require(!pool.prepare_frame({{2, other}}, 3, 6), "a cancelled upload must await its own fence");
  plan = pool.prepare_frame({{2, other}}, 4, 6);
  require(plan && plan->uploads.size() == 1 && plan->uploads.front().slot == original_slot,
          "a cancelled upload must be loaded again after its fence");
  pool.discard_frame(false);
  plan = pool.prepare_frame({{2, other}}, 4, 6);
  require(plan && plan->uploads.size() == 1, "a discarded local upload must be loaded again");
  pool.submit_frame();
  rejects<std::invalid_argument>([&] { (void)pool.prepare_frame({}, 3, 7); },
                                  "completed fences must be monotonic");
  rejects<std::invalid_argument>([&] { (void)pool.prepare_frame({}, 4, 6); },
                                  "a queued fence cannot be reused");
  plan = pool.prepare_frame({{2, other}}, 6, 7);
  require(plan && plan->uploads.empty() && plan->table_updates.empty(),
          "a successful retry must be committed");
  pool.submit_frame();
}

using Pixel = std::uint32_t;
constexpr int page_side = 64;
constexpr int scene_side = 128;
constexpr int width = 1280;
constexpr int height = 720;
constexpr int table_cols = (width - 1) / page_side + 2;
constexpr int table_rows = (height - 1) / page_side + 2;
constexpr int capacity = 384;
constexpr std::uint64_t page_base = 1ULL << 40;
using Page = std::array<Pixel, page_side * page_side>;
static_assert(sizeof(Page) == 16 * 1024);

struct View {
  int x, y;
  int edited_tile = -1;

  int center_tile() const {
    return ((y + height / 2) / page_side) * scene_side + (x + width / 2) / page_side;
  }
};

std::vector<View> passage() {
  std::vector<View> views;
  for (int i = 0; i < 320; ++i) {
    if (i < 96) {
      views.push_back({1539 + 11 * i, 1989 + 5 * i});
    } else if (i < 160) {
      views.push_back({2584 - 12 * (i - 95), 2464 - 3 * (i - 95)});
    } else if (i < 224) {
      views.push_back({5303 - 7 * (i - 160), 5509 + 9 * (i - 160)});
    } else {
      const int step = std::min(i - 224, 56);
      views.push_back({1792 + 8 * step, 2112 + 4 * step});
    }
  }
  views[28].edited_tile = 90 * scene_side + 90;
  for (auto i : {79, 160, 290}) views[i].edited_tile = views[i].center_tile();
  return views;
}

Pixel pixel(int x, int y, std::uint64_t revision) {
  const auto grain = static_cast<Pixel>(x) * 1664525U ^ static_cast<Pixel>(y) * 1013904223U;
  return 0xff000000U | ((grain + static_cast<Pixel>(revision) * 0x010101U) & 0xffffffU);
}

struct Result {
  std::size_t frames = 0;
  std::size_t full_map_updates = 0;
  std::size_t page_map_updates = 0;
  std::size_t page_uploads = 0;
};

Result render(const std::vector<View>& views) {
  GraphicsPageResidency residency(capacity);
  std::vector<Page> atlas(capacity);
  std::array<GraphicsPageBinding, table_cols * table_rows> table{};
  std::vector<GraphicsPageRequest> requests;
  std::vector<Pixel> image(width * height);
  std::array<std::uint64_t, scene_side * scene_side> revisions{};
  std::array<int, 4> previous_bounds{};
  Result result;

  for (const auto& view : views) {
    if (view.edited_tile >= 0) ++revisions[view.edited_tile];
    const int west = view.x / page_side;
    const int north = view.y / page_side;
    const int east = (view.x + width - 1) / page_side + 1;
    const int south = (view.y + height - 1) / page_side + 1;
    const std::array<int, 4> bounds{west, north, east, south};
    requests.clear();
    for (int y = north; y < south; ++y) {
      for (int x = west; x < east; ++x) {
        const auto index = static_cast<std::uint32_t>((y % table_rows) * table_cols + x % table_cols);
        const int tile = y * scene_side + x;
        requests.push_back({index, {page_base + tile, revisions[tile]}});
        if (bounds == previous_bounds && tile == view.edited_tile) ++result.full_map_updates;
      }
    }
    if (bounds != previous_bounds) result.full_map_updates += requests.size();
    previous_bounds = bounds;

    const auto completed = result.frames;
    const auto plan = residency.prepare_frame(requests, completed, completed + 1);
    require(plan != nullptr, "completed frames must leave reusable slots");
    result.page_uploads += plan->uploads.size();
    result.page_map_updates += plan->table_updates.size();
    for (const auto& upload : plan->uploads) {
      const auto tile = static_cast<int>(upload.page.page - page_base);
      const int left = (tile % scene_side) * page_side;
      const int top = (tile / scene_side) * page_side;
      auto& page = atlas[upload.slot];
      for (int y = 0; y < page_side; ++y)
        for (int x = 0; x < page_side; ++x)
          page[y * page_side + x] = pixel(left + x, top + y, upload.page.revision);
    }
    for (const auto& binding : plan->table_updates) table[binding.table_index] = binding;

    // Draw from the incrementally updated table and atlas, as a renderer would.
    for (const auto& request : requests) {
      const auto& binding = table[request.table_index];
      require(binding.page == request.page && binding.slot < atlas.size(), "invalid texture binding");
      const int tile = static_cast<int>(request.page.page - page_base);
      const int left = (tile % scene_side) * page_side;
      const int top = (tile / scene_side) * page_side;
      const int x0 = std::max(view.x, left);
      const int x1 = std::min(view.x + width, left + page_side);
      const int y0 = std::max(view.y, top);
      const int y1 = std::min(view.y + height, top + page_side);
      for (int y = y0; y < y1; ++y) {
        const auto source = atlas[binding.slot].data() + (y - top) * page_side + (x0 - left);
        auto* destination = image.data() + (y - view.y) * width + (x0 - view.x);
        std::memcpy(destination, source, static_cast<std::size_t>(x1 - x0) * sizeof(Pixel));
      }
    }
    residency.submit_frame();

    // Dense oracle uses coordinates and revisions directly, without the table.
    for (int y = 0; y < height; ++y) {
      for (int x = 0; x < width; ++x) {
        const int sx = view.x + x;
        const int sy = view.y + y;
        const auto revision = revisions[sy / page_side * scene_side + sx / page_side];
        require(image[y * width + x] == pixel(sx, sy, revision), "rendered pixel differs from oracle");
      }
    }
    ++result.frames;
  }
  return result;
}

void report(const char* name, const Result& result) {
  std::cout << "{\"scenario\":\"" << name << "\",\"frames\":" << result.frames
            << ",\"verified_pixels\":" << result.frames * width * height
            << ",\"reference_table_writes\":" << result.full_map_updates
            << ",\"incremental_table_writes\":" << result.page_map_updates
            << ",\"page_upload_bytes\":" << result.page_uploads * sizeof(Page) << '}';
}

}  // namespace

int main() {
  try {
    verify_fences_and_retry();
    const auto motion = render(passage());
    require(motion.page_map_updates < motion.full_map_updates, "sliding view must reuse table entries");
    std::vector<View> cuts;
    const std::array<View, 4> corners{{{129, 129}, {6401, 129}, {129, 6401}, {6401, 6401}}};
    for (int i = 0; i < 12; ++i) cuts.insert(cuts.end(), corners.begin(), corners.end());
    const auto jumps = render(cuts);
    require(jumps.page_map_updates == jumps.full_map_updates, "disjoint views must republish all bindings");

    std::cout << "{\"passed\":true,\"backend\":\"cpu\",\"logical_bytes\":"
              << scene_side * scene_side * sizeof(Page) << ",\"atlas_bytes\":"
              << capacity * sizeof(Page) << ",\"scenarios\":[";
    report("motion_and_edits", motion);
    std::cout << ',';
    report("camera_cuts", jumps);
    std::cout << "]}\n";
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
