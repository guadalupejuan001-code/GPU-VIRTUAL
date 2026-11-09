#include "trabajo_grafico.hpp"
#include <cmath>
#include <iostream>
#include <stdexcept>
using namespace xgpu;
static int checks=0;
static void ok(bool v,const char* m){++checks;if(!v)throw std::runtime_error(m);}
int main(){
  GraphicsWorkRelation racing{12000,37,97,4095,1.0f/4096.0f,6,2,0x1234,7,true};
  ok(valid(racing),"racing relation valid");
  ok(phase_period(racing)==4096,"phase period");
  ok(exact_identity_period(racing)==12288,"material/lane identity extends period");
  ok(exact_representative_count(racing)==12000,"must not collapse different racing materials");
  racing.count=24000;ok(exact_representative_count(racing)==12288,"exact repeats may collapse only after complete identity period");
  GraphicsWorkRelation unknown=racing;unknown.identity_period=0;ok(exact_representative_count(unknown)==unknown.count,"unknown identity never collapses");
  GraphicsWorkDelta d{4,4,9,9,false,false,false,false};ok(exact_reuse_allowed(d),"unchanged dependencies reuse");
  d.lighting_changed=true;ok(!exact_reuse_allowed(d),"lighting invalidates reuse");
  auto r=pwr(1000,700);ok(r.valid&&std::abs(r.ratio-.3)<1e-12,"PWR category arithmetic");
  auto none=pwr(0,0);ok(!none.valid,"zero denominator is unavailable, not 100 percent");
  std::cout<<"GPU Virtual graphics-work authority: "<<checks<<" checks passed\n";
}
