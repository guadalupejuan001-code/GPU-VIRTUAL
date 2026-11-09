#include "programa_ejecucion.hpp"
#include "compact_relation.hpp"
#include "snapshot_gpu.hpp"
#include "referencia_ejecucion.hpp"
#include <cfenv>
#include <fstream>
#include <iostream>
#include <random>

namespace {
using O=xgpu::StateOp;using I=xgpu::StateInstruction;
std::uint64_t checks=0,steps=0,snapshots=0;
void check(bool ok,const char* why) {++checks;if(!ok) throw std::runtime_error(why);}
template<class F> void rejected(F fn) {bool failed=false;try{fn();}catch(const std::runtime_error&){failed=true;}check(failed,"se esperaba rechazo C12");}
xgpu::Execution make(std::size_t n) {return xgpu::Execution(n,{},xgpu::ExecutionProfile::C12);}
void equivalent(const xgpu::Execution& a,const execution_reference::Dense& b) {
    check(a.revision()==b.revision && a.scheduler_cursor()==b.cursor && a.finished()==b.finished(),"control de autoridad distinto");
    check(a.dispatches().size()==b.jobs.size() && a.global().count==b.memory.size(),"dominios distintos");
    for(std::size_t i=0;i<b.memory.size();++i) check(a.global().at(i)==b.memory[i],"memoria global distinta");
    const auto& c=a.counters();
    check(c.steps==b.steps && c.global_reads==b.global_reads && c.global_writes==b.global_writes &&
        c.shared_reads==b.shared_reads && c.shared_writes==b.shared_writes && c.atomics==b.atomics && c.barrier_releases==b.releases,"contadores semanticos distintos");
    for(std::size_t j=0;j<b.jobs.size();++j) {
        const auto& d=a.dispatch(j);const auto& r=b.jobs[j];
        check(d.pc_cursor==r.cursor && d.dependencies==r.deps,"planificador distinto");
        for(std::size_t i=0;i<r.n;++i) {
            check(d.lane_pc(i)==r.pc[i] && d.lane_waiting(i)==r.waiting[i],"PC o espera distintos");
            check(d.output_valid.contains(i)==r.valid[i] && d.output->at(i)==r.output[i],"salida distinta");
            for(unsigned reg=0;reg<8;++reg) check(d.registers[reg]->at(i)==r.regs[reg][i],"registro distinto");
        }
        for(std::size_t i=0;i<r.shared.size();++i) check(d.shared->at(i)==r.shared[i],"memoria compartida distinta");
        for(std::size_t i=0;i<r.epochs.size();++i)
            check((d.barrier_epochs.count(i)?d.barrier_epochs.at(i):0)==r.epochs[i],"epoca distinta");
    }
}
void differential(xgpu::Execution a,execution_reference::Dense b,bool continuations=false) {
    unsigned count=0;
    while(true) {
        equivalent(a,b);
        const auto saved=a.snapshot();auto restored=xgpu::Execution::restore(saved);++snapshots;
        check(saved==restored.snapshot(),"DAG o alias cambia al recuperar");equivalent(restored,b);
        if(continuations && count%7==0) {
            auto expected=a;auto reference=b;
            check(expected.run(1000) && restored.run(1000),"continuacion no termina");reference.run(1000);
            equivalent(restored,reference);
            check(restored.snapshot()==expected.snapshot(),"cache derivada altera continuacion/counters");
        }
        if(a.finished()) break;
        check(++count<1000,"bucle inesperado");a.step();b.step();++steps;
    }
}
void floating_bits() {
    check(std::fesetround(FE_TONEAREST)==0,"referencia float no permite nearest");
    const auto verify=[](std::uint32_t a,std::uint32_t b) {
        check(xgpu::binary32::add(a,b)==execution_reference::host_float(O::FloatAdd,a,b),"suma binary32 distinta");
        check(xgpu::binary32::multiply(a,b)==execution_reference::host_float(O::FloatMultiply,a,b),"producto binary32 distinto");
        check(xgpu::binary32::from_unsigned(a)==execution_reference::host_float(O::FloatFromUnsigned,a,0),"conversion binary32 distinta");
        check(xgpu::binary32::less(a,b)==execution_reference::host_float(O::FloatLess,a,b),"comparacion binary32 distinta");
    };
    std::vector<std::uint32_t> edges{0,1,2,0x7fffff,0x800000,0x800001,0x3f000000,0x3f7fffff,
        0x3f800000,0x3f800001,0x4b800000,0x4b800001,0x7f7fffff,0x7f800000,0x7f800001,
        0x7fc12345,0x80000000,0x80000001,0x807fffff,0x80800000,0xbf800000,0xcb800000,
        0xff7fffff,0xff800000,0xff800001,0xffc12345,0xffffff,0x1000000,0x1000001,UINT32_MAX};
    for(auto a:edges) for(auto b:edges) verify(a,b);
    std::mt19937 rng(0xF3200012u);
    for(unsigned i=0;i<1000000;++i) verify(rng(),rng());
    check(xgpu::binary32::add(xgpu::binary32::add(0x4b800000,0x3f800000),0xcb800000)==0,"orden float A cambiado");
    check(xgpu::binary32::add(0x4b800000,xgpu::binary32::add(0x3f800000,0xcb800000))==0x3f800000,"orden float B cambiado");
}
void random_programs() {
    std::mt19937 rng(0xC1200012u);
    for(unsigned trial=0;trial<120;++trial) {
        const auto n=1+rng()%33;xgpu::Kernel kernel{{{O::Index,0}}};
        for(unsigned k=0;k<35;++k) {
            I i{};i.dst=1+rng()%6;i.a=rng()%7;i.b=rng()%7;
            switch(rng()%20) {
                case 0:i.op=O::Constant;i.value=rng();break;
                case 1:i.op=O::LocalIndex;break;case 2:i.op=O::GroupIndex;break;
                case 3:i.op=O::Add;break;case 4:i.op=O::Multiply;break;
                case 5:i.op=O::BitAnd;break;case 6:i.op=O::BitOr;break;case 7:i.op=O::BitXor;break;
                case 8:i.op=O::Equal;break;case 9:i.op=O::Less;break;
                case 10:case 11:
                    kernel.code.push_back({O::Constant,7,0,0,0,static_cast<xgpu::Word>(rng()%32)});
                    i.b=7;i.op=k%2?O::ShiftLeft:O::ShiftRight;break;
                case 12:i.op=O::LoadGlobal;i.a=0;break;
                case 13:i.op=O::StoreGlobal;i.a=0;break;
                case 14:i.op=O::FloatFromUnsigned;break;
                case 15:i.op=O::FloatAdd;break;case 16:i.op=O::FloatMultiply;break;case 17:i.op=O::FloatLess;break;
                case 18:i.op=O::Patch;i.k=rng()%n;i.value=rng();break;
                case 19:
                    i.op=O::BranchIndexLess;i.k=rng()%(n+1);i.value=static_cast<xgpu::Word>(kernel.code.size()+2);
                    kernel.code.push_back(i);i={O::Constant,static_cast<unsigned>(1+rng()%6),0,0,0,static_cast<xgpu::Word>(rng())};break;
            }
            kernel.code.push_back(i);
        }
        kernel.code.push_back({O::Output,1});kernel.code.push_back({O::Halt});
        auto a=make(n);execution_reference::Dense b(n);a.enqueue(kernel,n,8);b.enqueue(kernel,n,8);
        differential(std::move(a),std::move(b));
    }
}
void memory_versions() {
    constexpr unsigned n=64;auto a=make(n);execution_reference::Dense b(n);
    xgpu::Field initial(n);std::mt19937 rng(121212);
    for(unsigned i=0;i<n;++i) {auto w=static_cast<xgpu::Word>(rng());initial=initial.patched(i,w);b.memory[i]=w;}
    a.initialize_global(initial);++b.revision;
    xgpu::Kernel k{{{O::Index,0},{O::Constant,1,0,0,0,63},{O::BitXor,2,0,1},
        {O::LoadGlobal,3,2},{O::Constant,4,0,0,0,7},{O::Add,5,3,4},
        {O::StoreGlobal,0,2,5},{O::LoadGlobal,6,2},{O::Constant,4,0,0,0,19},
        {O::StoreGlobal,0,0,4},{O::Output,3},{O::Halt}}};
    a.enqueue(k,n,8);b.enqueue(k,n,8);differential(a,b,true);
    check(a.run(),"lecturas versionadas no terminan");
    for(unsigned i=0;i<n;++i) {
        check(a.dispatch(0).output->at(i)==initial.at(i^63),"lectura antigua observa memoria posterior");
        check(a.dispatch(0).registers[6]->at(i)==xgpu::add_word(initial.at(i^63),7),"lectura intermedia perdida");
        check(a.global().at(i)==19,"escritura nueva incorrecta");
    }
    check(a.counters().scalar_lane_evaluations==0,"permutacion demostrada expandida");
    // Indexed atomics still exercise the bounded fallback and operand aliases.
    xgpu::Kernel atomic{{{O::Index,0},{O::AtomicAddGlobal,0,0,0},{O::Output,0},{O::Halt}}};
    a=make(n);b=execution_reference::Dense(n);a.enqueue(atomic,n,8);b.enqueue(atomic,n,8);
    differential(a,b,true);
    auto consumer=xgpu::Kernel{{{O::Index,0},{O::LoadGlobal,1,0},{O::Output,1},{O::Halt}}};
    a.enqueue(consumer,n,8,0,{0});b.enqueue(consumer,n,8,0,{0});differential(a,b,true);
}
void control_and_atomics() {
    const std::string text="xgpu 3\ndominio 35\ngrupo 8\ncompartida 8\nglobal 35\n"
        "indice r0\nlocal r1\nguarda_compartida r1 r0\nconstante r2 4\nmenor r3 r1 r2\n"
        "si r3 otro\nconstante r4 0\nconstante r5 3\natomica_global r6 r4 r5\nsalta juntos\n"
        "marca otro\nconstante r6 99\nmarca juntos\nbarrera\ncarga_compartida r7 r1\nsalida r6\nfin\n";
    auto source=xgpu::parse_execution(text);auto a=xgpu::make_execution(source);execution_reference::Dense b(source.global_words);
    b.enqueue(source.kernel,source.count,source.local_size,source.shared_words);differential(a,b,true);
    check(a.run(),"control periodico no termina");
    check(a.counters().period_values_checked==8,"predicado periodico no utilizo su periodo");
    check(a.counters().scalar_lane_evaluations==0,"rango atomico o memoria de grupo se expandieron");
    // Divergent loops with waiting groups, imported from a real C11 example.
    std::ifstream file("examples/estado-divergente.xgpu");std::string code((std::istreambuf_iterator<char>(file)),{});
    auto loop=xgpu::parse_execution(code);loop.profile=xgpu::ExecutionProfile::C12;
    a=xgpu::make_execution(loop);b=execution_reference::Dense(loop.global_words);
    b.enqueue(loop.kernel,loop.count,loop.local_size,loop.shared_words);differential(a,b,true);
}
void large_relations_and_float() {
    constexpr xgpu::Index n=65536;
    xgpu::Kernel k{{{O::Index,0},{O::FloatFromUnsigned,1,0},{O::Constant,2,0,0,0,0x3e000000},
        {O::FloatMultiply,3,1,2},{O::Constant,4,0,0,0,0x40600000},{O::FloatAdd,5,3,4},
        {O::FloatAdd,6,3,4},{O::FloatMultiply,7,5,6},{O::Output,7},{O::Halt}}};
    auto a=make(n);a.enqueue(k,n,64);check(a.run(),"float relacional no termina");
    check(a.dispatch(0).registers[5]==a.dispatch(0).registers[6],"expresion repetida no comparte nodo");
    xgpu::ValueReader reader(a.dispatch(0).output);
    for(xgpu::Index lane=0;lane<n;++lane) {
        auto v=execution_reference::host_float(O::FloatFromUnsigned,static_cast<xgpu::Word>(lane),0);
        v=execution_reference::host_float(O::FloatMultiply,v,0x3e000000);
        v=execution_reference::host_float(O::FloatAdd,v,0x40600000);
        check(reader.at(lane)==execution_reference::host_float(O::FloatMultiply,v,v),"materializacion float cambia bits");
    }
    check(a.counters().scalar_lane_evaluations==0 && a.snapshot().size()<4096,"float fue expandido para ejecutarse");
    constexpr auto big=xgpu::domain_limit;
    auto huge=make(1);
    huge.enqueue({{{O::Constant,0},{O::Constant,1,0,0,0,3},{O::AtomicAddGlobal,2,0,1},{O::Output,2},{O::Halt}}},big,64);
    check(huge.run(),"atomicas estructuradas grandes no terminan");
    for(const auto lane:std::vector<xgpu::Index>{0,1,big/2,big-1})
        check(huge.dispatch(0).output->at(lane)==static_cast<xgpu::Word>(lane*3),"prefijo atomico grande incorrecto");
    check(huge.global().at(0)==0 && huge.counters().atomics==big,"suma atomica modular grande incorrecta");
    check(huge.counters().scalar_lane_evaluations==0,"atomicas grandes se expandieron");
    check(xgpu::Execution::restore(huge.snapshot()).snapshot()==huge.snapshot(),"atomicas grandes no recuperan");
    // Shared operands must be evaluated once per node, not by exponential recursive expansion.
    auto repeated=make(4);xgpu::Kernel doubling{{{O::LocalIndex,0},{O::FloatFromUnsigned,0,0}}};
    for(unsigned i=0;i<120;++i) doubling.code.push_back({O::FloatAdd,0,0,0});
    doubling.code.push_back({O::Output,0});doubling.code.push_back({O::Halt});
    repeated.enqueue(doubling,4,4);check(repeated.run(),"DAG compartido profundo no termina");
    xgpu::Word expected=execution_reference::host_float(O::FloatFromUnsigned,3,0);
    for(unsigned i=0;i<120;++i) expected=execution_reference::host_float(O::FloatAdd,expected,expected);
    check(repeated.dispatch(0).output->at(3)==expected,"consulta profunda duplicada incorrecta");
    xgpu::ValueReader stream(repeated.dispatch(0).output);
    check(stream.at(3)==expected,"plan profundo cambia bits");
}
void compact_affine_descriptor() {
    constexpr xgpu::Index n=4096;
    xgpu::Field affine(n,xgpu::Mode({17,13}));
    auto relation=xgpu::field_root(affine);
    auto mask=xgpu::field_root(xgpu::Field(n,xgpu::Mode({4095})));
    relation=xgpu::Value::node(xgpu::ValueKind::And,n,relation,mask,0,xgpu::LaneMask(n,true));
    xgpu::CompactAffineMask compact{};
    check(xgpu::describe_compact_affine_mask(relation,compact),"relacion afin compacta no reconocida");
    check(compact.count==n && compact.base==17 && compact.stride==13 && compact.mask==4095 && compact.masked,
          "descriptor afin compacto incorrecto");
    for(const auto lane:std::vector<xgpu::Index>{0,1,7,63,1024,n-1})
        check(relation->at(lane)==((17u+static_cast<xgpu::Word>(lane)*13u)&4095u),
              "descriptor afin no conserva la relacion");
    auto detailed=affine.patched(3,99);
    xgpu::CompactAffineMask rejected_descriptor{};
    check(!xgpu::describe_compact_affine_mask(xgpu::field_root(std::move(detailed)),rejected_descriptor),
          "campo con excepciones se describio como afin puro");
}
void failures_and_archives() {
    auto a=make(4);a.enqueue({{{O::Index,0},{O::Constant,1,0,0,0,31},{O::Add,1,0,1},{O::ShiftLeft,2,0,1},{O::Halt}}},4);
    a.step();a.step();a.step();auto before=a.snapshot();rejected([&]{a.step();});check(a.snapshot()==before,"fallo de shift publica estado");
    a=make(3);a.enqueue({{{O::Index,0},{O::LoadGlobal,1,0},{O::Halt}}},4);a.step();before=a.snapshot();
    rejected([&]{a.step();});check(a.snapshot()==before,"lectura diferida fuera de memoria se acepto");
    auto limited=make(2);xgpu::Kernel deep{{{O::Index,0},{O::LocalIndex,1}}};
    for(unsigned i=0;i<130;++i) deep.code.push_back({O::BitXor,1,1,0});
    deep.code.push_back({O::Halt});
    limited.enqueue(deep,2,2);bool failed=false;
    for(unsigned i=0;i<200 && !limited.finished();++i) {
        before=limited.snapshot();try{limited.step();}catch(const std::runtime_error&){check(limited.snapshot()==before,"presupuesto publica estado");failed=true;break;}
    }
    check(failed,"profundidad de relaciones no acotada");
    auto good=make(8);good.enqueue({{{O::LocalIndex,0},{O::Output,0},{O::Halt}}},8,4);good.run();const auto bytes=good.snapshot();
    for(std::size_t p=0;p<bytes.size();p+=7) {auto bad=bytes;bad[p]^=1;rejected([&]{xgpu::Execution::restore(bad);});}
    rejected([&]{xgpu::Execution::restore(bytes+"tail");});
    auto payload=bytes.substr(bytes.find('\n',bytes.find('\n')+1)+1);
    const std::string local="1 8 4 65536 65536 8 ";const auto where=payload.find(local);
    check(where!=std::string::npos,"no se encontro nodo de prueba");
    payload.replace(where,local.size(),"1 8 4 1 65536 8 ");
    auto cyclic="XGPU-C12 1\n"+std::to_string(payload.size())+" "+std::to_string(xgpu::ExecutionCodec::checksum(payload))+"\n"+payload;
    rejected([&]{xgpu::Execution::restore(cyclic);});
    auto fork=good;fork.enqueue({{{O::Constant,0,0,0,0,42},{O::Output,0},{O::Halt}}},8,4,0,{0});
    check(fork.run() && good.snapshot()==bytes,"bifurcacion altera estado anterior");
}
}
int main() {
    try {
        floating_bits();random_programs();memory_versions();control_and_atomics();large_relations_and_float();compact_affine_descriptor();failures_and_archives();
        std::cout<<"{\"profile\":\"XGPU-C12\",\"checks\":"<<checks<<",\"random_kernels\":120,\"float_random_pairs\":1000000,\"differential_steps\":"<<steps
                 <<",\"snapshots\":"<<snapshots<<",\"hardware_gpu_executed\":false,\"failures\":0}\n";
        return 0;
    } catch(const std::exception& error) {std::cerr<<"C12 FALLO despues de "<<checks<<" checks: "<<error.what()<<'\n';return 1;}
}
