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
  xgpu::FramebufferWorkAuthority display;
  std::vector<xgpu::FramebufferSpan> rows(129, {64, 0});
  assert(display.plan(64, rows).rectangles.empty());
  rows[31] = rows[32] = {63, 64};
  const auto patch = display.plan(64, rows);
  assert(patch.revision == 1 && patch.pixels == 2 && patch.packet_words == 7 &&
         !patch.full_frame && patch.rectangles.size() == 1 && patch.rectangles[0].x == 63 &&
         patch.rectangles[0].y == 31 && patch.rectangles[0].height == 2);
  for (std::size_t y = 0; y < rows.size(); ++y) rows[y] = {unsigned(y % 2), unsigned(y % 2 + 1)};
  const auto full = display.plan(64, rows);
  assert(full.revision == 2 && full.full_frame && full.pixels == 64 * 129 &&
         full.rectangles.size() == 5 && full.rectangles.back().height == 1);
  rows.back() = {63, 65};
  bool rejected = false;
  try { display.plan(64, rows); } catch (const std::out_of_range&) { rejected = true; }
  assert(rejected);
  std::cout<<"physical work authority passed\n";
}
