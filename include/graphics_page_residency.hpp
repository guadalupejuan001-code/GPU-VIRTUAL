#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <map>
#include <optional>
#include <stdexcept>
#include <tuple>
#include <utility>
#include <vector>

namespace xgpu {

struct GraphicsPageVersion {
  std::uint64_t page;
  std::uint64_t revision;

  friend bool operator<(GraphicsPageVersion a, GraphicsPageVersion b) noexcept {
    return std::tie(a.page, a.revision) < std::tie(b.page, b.revision);
  }
  friend bool operator==(GraphicsPageVersion a, GraphicsPageVersion b) noexcept {
    return a.page == b.page && a.revision == b.revision;
  }
};

struct GraphicsPageRequest {
  std::uint32_t table_index;
  GraphicsPageVersion page;
};

struct GraphicsPageUpload {
  GraphicsPageVersion page;
  std::uint32_t slot;
};

struct GraphicsPageBinding {
  std::uint32_t table_index;
  GraphicsPageVersion page;
  std::uint32_t slot;
};

struct GraphicsPageFrame {
  std::vector<GraphicsPageUpload> uploads;
  std::vector<GraphicsPageBinding> bindings;
  std::vector<GraphicsPageBinding> table_updates;
};

// One atlas and one ordered GPU queue. Page versions name immutable bytes.
class GraphicsPageResidency {
 public:
  explicit GraphicsPageResidency(std::uint32_t capacity) : slots_(capacity) {
    if (!capacity) throw std::invalid_argument("graphics page pool is empty");
  }
  GraphicsPageResidency(const GraphicsPageResidency&) = delete;
  GraphicsPageResidency& operator=(const GraphicsPageResidency&) = delete;

  // A null plan means in-flight work holds slots needed by this frame.
  // A successful plan remains valid until submit_frame or discard_frame.
  [[nodiscard]] const GraphicsPageFrame* prepare_frame(
      const std::vector<GraphicsPageRequest>& requests, std::uint64_t completed_fence,
      std::uint64_t frame_fence) {
    if (pending_) throw std::logic_error("graphics page frame is already prepared");
    if (completed_fence < completed_fence_ || frame_fence <= completed_fence ||
        frame_fence <= last_queued_fence_)
      throw std::invalid_argument("graphics page fences are out of order");

    Pending next;
    next.fence = frame_fence;
    auto& bindings = next.frame.bindings;
    bindings.reserve(requests.size());
    for (const auto& request : requests) {
      const auto found = resident_.find(request.page);
      const auto slot = found == resident_.end() ? kNoSlot : found->second;
      bindings.push_back({request.table_index, request.page, slot});
      if (slot == kNoSlot) next.new_pages.try_emplace(request.page, kNoSlot);
    }
    std::sort(bindings.begin(), bindings.end(), [](const auto& a, const auto& b) {
      return a.table_index < b.table_index;
    });
    if (std::adjacent_find(bindings.begin(), bindings.end(), [](const auto& a, const auto& b) {
          return a.table_index == b.table_index;
        }) != bindings.end())
      throw std::invalid_argument("duplicate graphics page table index");

    if (!next.new_pages.empty() && !assign_slots(next, completed_fence)) {
      completed_fence_ = completed_fence;
      return nullptr;
    }
    auto previous = published_.begin();
    for (const auto& binding : bindings) {
      while (previous != published_.end() && previous->table_index < binding.table_index) ++previous;
      if (previous == published_.end() || previous->table_index != binding.table_index ||
          !(previous->page == binding.page) || previous->slot != binding.slot)
        next.frame.table_updates.push_back(binding);
    }
    completed_fence_ = completed_fence;
    return &pending_.emplace(std::move(next)).frame;
  }

  // The renderer has queued the uploads, table updates, draw and planned fence in order.
  void submit_frame() {
    auto& next = prepared();
    for (const auto& upload : next.frame.uploads) {
      invalidate(upload.slot);
      slots_[upload.slot].page = upload.page;
    }
    resident_.merge(next.new_pages);
    record_use(next);
    published_.swap(next.frame.bindings);
    pending_.reset();
  }

  // Any attempted upload may have overwritten its slot. If commands are still
  // queued, the caller must also have queued the planned fence after them.
  void discard_frame(bool commands_pending) {
    const auto& next = prepared();
    for (const auto& upload : next.frame.uploads) invalidate(upload.slot);
    if (commands_pending) record_use(next);
    published_.clear();
    pending_.reset();
  }

 private:
  using SlotIndex = std::uint32_t;
  static constexpr auto kNoSlot = std::numeric_limits<SlotIndex>::max();

  struct Slot {
    std::optional<GraphicsPageVersion> page;
    std::uint64_t last_use = 0;
  };

  struct Pending {
    GraphicsPageFrame frame;
    std::map<GraphicsPageVersion, SlotIndex> new_pages;
    std::uint64_t fence = 0;
  };

  bool assign_slots(Pending& next, std::uint64_t completed_fence) const {
    std::vector<bool> retained(slots_.size());
    std::size_t retained_count = 0;
    for (const auto& binding : next.frame.bindings) {
      if (binding.slot == kNoSlot || retained[binding.slot]) continue;
      retained[binding.slot] = true;
      ++retained_count;
    }
    const auto missing = next.new_pages.size();
    if (missing > slots_.size() - retained_count)
      throw std::length_error("graphics page frame exceeds atlas capacity");

    std::vector<SlotIndex> available;
    available.reserve(slots_.size() - retained_count);
    for (std::size_t i = 0; i < slots_.size(); ++i) {
      if (!retained[i] && slots_[i].last_use <= completed_fence)
        available.push_back(static_cast<SlotIndex>(i));
    }
    if (available.size() < missing) return false;
    std::partial_sort(available.begin(), available.begin() + missing, available.end(),
                      [this](SlotIndex a, SlotIndex b) {
      return std::make_tuple(slots_[a].page.has_value(), slots_[a].last_use, a) <
             std::make_tuple(slots_[b].page.has_value(), slots_[b].last_use, b);
    });

    next.frame.uploads.reserve(missing);
    auto slot = available.begin();
    for (auto& [page, index] : next.new_pages) {
      index = *slot++;
      next.frame.uploads.push_back({page, index});
    }
    for (auto& binding : next.frame.bindings) {
      if (binding.slot == kNoSlot) binding.slot = next.new_pages.find(binding.page)->second;
    }
    return true;
  }

  Pending& prepared() {
    if (!pending_) throw std::logic_error("no graphics page frame is prepared");
    return *pending_;
  }

  void invalidate(SlotIndex slot) {
    auto& state = slots_[slot];
    if (state.page) resident_.erase(*state.page);
    state.page.reset();
  }

  void record_use(const Pending& next) {
    for (const auto& binding : next.frame.bindings) slots_[binding.slot].last_use = next.fence;
    last_queued_fence_ = next.fence;
  }

  std::vector<Slot> slots_;
  std::map<GraphicsPageVersion, SlotIndex> resident_;
  std::vector<GraphicsPageBinding> published_;
  std::optional<Pending> pending_;
  std::uint64_t completed_fence_ = 0;
  std::uint64_t last_queued_fence_ = 0;
};

}  // namespace xgpu
