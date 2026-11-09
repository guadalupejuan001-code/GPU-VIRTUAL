#include "physical_projection.hpp"
#include <cassert>
#include <cstring>
#include <iostream>
static std::uint32_t bits(float x){std::uint32_t u;std::memcpy(&u,&x,4);return u;}
int main(){
  using O=xgpu::StateOp;
  xgpu::Field f(12000,xgpu::Mode({37,97}));
  xgpu::Kernel k{{{O::Index,0},{O::LoadGlobal,1,0},{O::Constant,2,0,0,0,4095},{O::BitAnd,3,1,2},{O::FloatFromUnsigned,4,3},{O::Constant,5,0,0,0,bits(1.f/4096.f)},{O::FloatMultiply,6,4,5},{O::Output,6},{O::Halt}}};
  auto p=xgpu::project_masked_linear_float(f,k);
  assert(p.valid&&p.count==12000&&p.base_mod==37&&p.stride_mod==97&&p.mask==4095);
  for(std::uint32_t i=0;i<12000;i+=17) assert(p.word(i)==((37u+97u*i)&4095u));
  auto bad=k;bad.code[3].op=O::BitOr;
  assert(!xgpu::project_masked_linear_float(f,bad).valid);
  std::cout<<"physical projection 708/708 passed\n";
}
