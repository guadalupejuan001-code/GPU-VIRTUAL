#pragma once
#include "relaciones_gpu.hpp"

namespace xgpu {

// A compact description for the common relation
//   (base + lane * stride) & mask
// when it can be proven from the immutable relation graph without expanding
// one value per lane. Consumers may pass this descriptor to a physical GPU,
// serializer, visibility stage, or another backend while the full FieldRoot
// remains the authority.
struct CompactAffineMask {
    Index count = 0;
    Word base = 0;
    Word stride = 0;
    Word mask = UINT32_MAX;
    std::uint64_t relation_hash = 0;
    bool masked = false;
};

inline bool describe_compact_affine_mask(const FieldRoot& root, CompactAffineMask& out) {
    if (!root) return false;

    FieldRoot polynomial = root;
    Word mask = UINT32_MAX;
    bool masked = false;

    if (root->kind() == ValueKind::And) {
        const auto pick = [&](const FieldRoot& candidate, const FieldRoot& constant) {
            if (!candidate || !constant || !constant->constant()) return false;
            polynomial = candidate;
            mask = constant->at(0);
            masked = true;
            return true;
        };
        if (!pick(root->left(), root->right()) && !pick(root->right(), root->left())) return false;
    }

    if (!polynomial || polynomial->kind() != ValueKind::Polynomial || !polynomial->is_field()) return false;
    const auto& field = polynomial->field();
    if (field.bands.size() != 1 || !field.details.empty()) return false;
    const auto& band = field.bands.front();
    if (band.begin != 0 || band.end != field.count) return false;
    const auto& coefficients = band.mode.coefficients;
    if (coefficients.empty() || coefficients.size() > 2) return false;

    out.count = field.count;
    out.base = coefficients[0];
    out.stride = coefficients.size() == 2 ? coefficients[1] : 0;
    out.mask = mask;
    out.relation_hash = root->hash();
    out.masked = masked;
    return true;
}

} // namespace xgpu
