#include "trabajo_fisico.hpp"
#include <cassert>
#include <iostream>
#include <vector>
int main(){
  xgpu::PhysicalWorkAuthority a;
  std::vector<std::uint32_t> vertices{36,24};
  std::vector<xgpu::PhysicalCandidate> in{{1,10,20,0,10.f,8.f,true,true},{2,11,20,1,.25f,.25f,true,true},{3,12,21,0,0.f,0.f,false,false}};
  auto p=a.plan(in,vertices,101,true,true,true,.5f,.5f);
  assert(p.source_objects==3&&p.camera_candidates==2&&p.shadow_candidates==2);
  assert(p.camera_indices.size()==1&&p.shadow_indices.size()==1);
  assert(p.camera_rejected==1&&p.shadow_rejected==1);
  assert(p.changed_objects==3&&p.reused_objects==0);
  assert(p.baseline_instance_bytes==448&&p.planned_instance_bytes==88);
  assert(p.camera_baseline_vertices==60&&p.shadow_baseline_vertices==60);
  assert(p.camera_planned_vertices==36&&p.shadow_planned_vertices==36);
  auto q=a.plan(in,vertices,102,true,true,true,.5f,.5f);
  assert(q.changed_objects==0&&q.reused_objects==3);
  in[0].transform_revision++;
  auto r=a.plan(in,vertices,103,false,true,false,.5f,.5f);
  assert(r.changed_objects==1&&r.reused_objects==2&&r.planned_instance_bytes==448);
  std::cout<<"physical work authority passed\n";
}
