#pragma once
#include "control_gpu.hpp"
#include "estado_gpu.hpp"
#include <cstdint>
#include <cstring>
#include <stdexcept>

namespace xgpu {
struct PhysicalMaskedLinearFloat {
  Index count=0;
  Word base_mod=0,stride_mod=0,mask=0,scale_bits=0;
  bool valid=false;
  Word word(Index i) const {
    if(!valid||i>=count) throw std::out_of_range("physical relation index");
    return (base_mod + Word(i)*stride_mod) & mask;
  }
};
inline PhysicalMaskedLinearFloat project_masked_linear_float(const Field& source,const Kernel& kernel) {
  using O=StateOp;
  // Exact compact form consumed by a physical shader: relation, not N materialized outputs.
  if(source.bands.size()!=1 || !source.details.empty()) return {};
  const auto& c=source.bands.front().mode.coefficients;
  if(c.size()!=2 || kernel.code.size()!=9) return {};
  const auto& k=kernel.code;
  if(k[0].op!=O::Index || k[1].op!=O::LoadGlobal || k[2].op!=O::Constant ||
     k[3].op!=O::BitAnd || k[4].op!=O::FloatFromUnsigned || k[5].op!=O::Constant ||
     k[6].op!=O::FloatMultiply || k[7].op!=O::Output || k[8].op!=O::Halt) return {};
  if(k[2].value==0 || ((k[2].value+1)&k[2].value)!=0) return {};
  PhysicalMaskedLinearFloat out;
  out.count=source.count;out.mask=k[2].value;
  out.base_mod=c[0]&out.mask;out.stride_mod=c[1]&out.mask;
  out.scale_bits=k[5].value;out.valid=true;return out;
}
} // namespace xgpu
