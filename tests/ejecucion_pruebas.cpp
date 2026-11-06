#include "programa_ejecucion.hpp"
#include "snapshot_gpu.hpp"
#include "referencia_ejecucion.hpp"
#include <fstream>
#include <iostream>
#include <random>

namespace {
using O=xgpu::StateOp;
using I=xgpu::StateInstruction;
std::uint64_t checks=0,differential_steps=0,restored_states=0,waiting_snapshots=0;
std::size_t million_bytes=0,large_bytes=0;
void check(bool ok,const char* message) {
    ++checks; if(!ok) throw std::runtime_error(message);
}
template<class F> void rejected(F fn) {
    bool failed=false; try {fn();} catch(const std::runtime_error&) {failed=true;}
    check(failed,"se esperaba rechazo C11");
}
void equivalent(const xgpu::Execution& state,const execution_reference::Dense& dense) {
    check(state.revision()==dense.revision,"revision escalar distinta");
    check(state.scheduler_cursor()==dense.cursor,"cursor de despachos distinto");
    check(state.finished()==dense.finished(),"finalizacion distinta");
    check(state.dispatches().size()==dense.jobs.size(),"cola de despachos distinta");
    check(state.global().count==dense.memory.size(),"dominio global distinto");
    for(std::size_t at=0;at<dense.memory.size();++at)
        check(state.global().at(at)==dense.memory[at],"memoria global distinta");
    const auto& c=state.counters();
    check(c.steps==dense.steps && c.global_reads==dense.global_reads && c.global_writes==dense.global_writes &&
          c.shared_reads==dense.shared_reads && c.shared_writes==dense.shared_writes &&
          c.atomics==dense.atomics && c.barrier_releases==dense.releases,"contadores semanticos distintos");
    for(std::size_t id=0;id<dense.jobs.size();++id) {
        const auto& d=state.dispatch(id); const auto& ref=dense.jobs[id];
        check(d.count==ref.n && d.pc_cursor==ref.cursor && d.dependencies==ref.deps,"control de despacho distinto");
        check(d.shared->count==ref.shared.size(),"dominio compartido distinto");
        for(std::size_t at=0;at<ref.shared.size();++at)
            check(d.shared->at(at)==ref.shared[at],"memoria compartida distinta");
        for(std::size_t lane=0;lane<ref.n;++lane) {
            check(d.lane_pc(lane)==ref.pc[lane],"PC por ejecucion distinto");
            check(d.lane_waiting(lane)==ref.waiting[lane],"espera por ejecucion distinta");
            check(d.output_valid.contains(lane)==ref.valid[lane],"validez de salida distinta");
            check(d.output->at(lane)==ref.output[lane],"salida distinta");
            for(unsigned r=0;r<8;++r) check(d.registers[r]->at(lane)==ref.regs[r][lane],"registro por ejecucion distinto");
        }
        for(std::size_t group=0;group<ref.epochs.size();++group) {
            const auto epoch=d.barrier_epochs.count(group)?d.barrier_epochs.at(group):0;
            check(epoch==ref.epochs[group],"epoca de grupo distinta");
        }
    }
}
void differential(xgpu::Execution state,execution_reference::Dense dense,bool future_checks=false) {
    std::size_t steps=0;
    while(true) {
        equivalent(state,dense);
        const auto snapshot=state.snapshot();
        auto restored=xgpu::Execution::restore(snapshot); ++restored_states;
        check(restored.snapshot()==snapshot,"archivo no conserva alias y estado canonico");
        equivalent(restored,dense);
        bool waiting=false;
        for(const auto& d:state.dispatches()) waiting=waiting || !d.waiting.empty();
        if(waiting) ++waiting_snapshots;
        if(future_checks && (waiting || steps%7==0)) {
            auto expected=state; auto scalar=dense;
            check(expected.run(5000) && restored.run(5000),"continuacion C11 no termina"); scalar.run(5000);
            equivalent(restored,scalar);
            check(restored.snapshot()==expected.snapshot(),"continuacion futura cambia tras recuperar");
        }
        if(state.finished()) { check(!state.step(),"GPU terminada sigue avanzando"); break; }
        check(++steps<=5000,"bucle inesperado en prueba");
        check(state.step()==dense.step(),"paso C11 distinto"); ++differential_steps;
    }
}
void masks() {
    std::mt19937 rng(110001);
    for(unsigned trial=0;trial<400;++trial) {
        const xgpu::Index n=1+rng()%97;
        xgpu::LaneMask a(n),b(n); std::vector<bool> av(n),bv(n);
        for(unsigned k=0;k<20;++k) {
            auto begin=rng()%(n+1),end=rng()%(n+1); if(begin>end) std::swap(begin,end);
            auto& mask=k%2?a:b; auto& values=k%2?av:bv;
            mask=mask.joined(xgpu::LaneMask::interval(n,begin,end));
            for(auto at=begin;at<end;++at) values[at]=true;
        }
        const auto both=a.intersected(b),either=a.joined(b),difference=a.subtracted(b);
        for(xgpu::Index lane=0;lane<n;++lane) {
            check(both.contains(lane)==(av[lane] && bv[lane]),"interseccion de mascaras distinta");
            check(either.contains(lane)==(av[lane] || bv[lane]),"union de mascaras distinta");
            check(difference.contains(lane)==(av[lane] && !bv[lane]),"resta de mascaras distinta");
        }
    }
    xgpu::LaneMask full(xgpu::domain_limit,true);
    auto last=xgpu::LaneMask::interval(xgpu::domain_limit,xgpu::domain_limit-1,xgpu::domain_limit);
    check(full.subtracted(last).size()==xgpu::domain_limit-1,"mascara de 32 bits incorrecta");
    rejected([]{xgpu::LaneMask::interval(4,3,5);});
}
void original_profile() {
    for(xgpu::Index n:{1u,7u,257u}) {
        auto p=xgpu::example(n); xgpu::Execution state(n); state.enqueue(xgpu::Kernel::from_c01(p),n);
        execution_reference::Dense dense(n); dense.enqueue(xgpu::Kernel::from_c01(p),n);
        differential(std::move(state),std::move(dense),true);
    }
    constexpr xgpu::Index n=1048576;
    auto p=xgpu::example(n); xgpu::Machine original(p); original.run();
    xgpu::Execution state(n); state.enqueue(xgpu::Kernel::from_c01(p),n);
    check(state.run(),"C01 importado no termina");
    for(xgpu::Index lane=0;lane<n;++lane)
        check(state.dispatch(0).output->at(lane)==original.output().at(lane),"C01 importado cambio salida");
    check(state.counters().scalar_lane_evaluations==0,"C01 importado expandio invocaciones");
    million_bytes=state.snapshot().size(); check(million_bytes<4096,"checkpoint C01 integrado se expandio");
    check(xgpu::Execution::restore(state.snapshot()).snapshot()==state.snapshot(),"C01 integrado no recupera");
}
const char* synchronization_source=R"(xgpu 2
dominio 11
grupo 4
compartida 4
global 11
indice r0
local r1
constante r2 1
guarda_compartida r1 r0
constante r3 0
constante r4 3
constante r5 2
menor r6 r1 r5
si r6 bucle
salta reunion
marca bucle
suma r3 r3 r2
menor r6 r3 r4
si r6 bucle
marca reunion
barrera
carga_compartida r7 r1
barrera
guarda_global r0 r7
salida r7
fin
)";
void synchronization() {
    auto source=xgpu::parse_execution(synchronization_source);
    auto state=xgpu::make_execution(source);
    execution_reference::Dense dense(source.global_words);
    dense.enqueue(source.kernel,source.count,source.local_size,source.shared_words);
    differential(state,dense,true);
    check(waiting_snapshots>0,"no se probo una barrera parcialmente alcanzada");
    check(state.run(),"sincronizacion no termina");
    for(xgpu::Index lane=0;lane<source.count;++lane)
        check(state.dispatch(0).output->at(lane)==lane,"barrera pierde memoria anterior");
    check(state.counters().barrier_releases==6,"grupos parciales o epocas incorrectos");
}
void atomics_and_dependencies() {
    for(bool shared:{false,true}) {
        xgpu::Kernel kernel{{{O::Constant,0},{O::Constant,1,0,0,0,1},
            {shared?O::AtomicAddShared:O::AtomicAddGlobal,2,0,1},
            {O::Output,2},{O::Barrier},{O::Halt}}};
        xgpu::Execution state(1); execution_reference::Dense dense(1);
        if(!shared) { state.initialize_global(xgpu::Field(1,xgpu::Mode({UINT32_MAX-3}))); dense.memory[0]=UINT32_MAX-3; ++dense.revision; }
        state.enqueue(kernel,19,8,shared?1:0); dense.enqueue(kernel,19,8,shared?1:0);
        differential(state,dense,true);
        check(state.run(),"atomicas no terminan");
        for(xgpu::Index lane=0;lane<19;++lane)
            check(state.dispatch(0).output->at(lane)==(shared?static_cast<xgpu::Word>(lane%8):
                  static_cast<xgpu::Word>(UINT32_MAX-3+lane)),"orden atomico no es el declarado");
    }
    xgpu::Kernel producer{{{O::Index,0},{O::Constant,1,0,0,0,3},{O::Multiply,2,0,1},
        {O::StoreGlobal,0,0,2},{O::Halt}}};
    xgpu::Kernel consumer{{{O::Index,0},{O::LoadGlobal,1,0},{O::Constant,2,0,0,0,7},
        {O::Add,3,1,2},{O::Output,3},{O::Halt}}};
    xgpu::Execution state(67); execution_reference::Dense dense(67);
    state.enqueue(producer,67,8); dense.enqueue(producer,67,8);
    state.enqueue(consumer,67,8,0,{0}); dense.enqueue(consumer,67,8,0,{0});
    differential(state,dense,true);
    auto fork=state; check(fork.run(),"cola dependiente no termina");
    for(xgpu::Index lane=0;lane<67;++lane) check(fork.dispatch(1).output->at(lane)==3*lane+7,"dependencia no conserva datos");
    check(!state.finished() && state.global().at(4)==0,"bifurcacion modifico autoridad original");
    // Independent dispatches interleave; the scheduler cursor must survive snapshots.
    xgpu::Kernel atomic{{{O::Constant,0},{O::Constant,1,0,0,0,1},{O::AtomicAddGlobal,2,0,1},{O::Output,2},{O::Halt}}};
    xgpu::Execution parallel(1); execution_reference::Dense serial(1);
    for(unsigned k=0;k<3;++k) { parallel.enqueue(atomic,5,4); serial.enqueue(atomic,5,4); }
    differential(parallel,serial,true);
}
void random_kernels() {
    std::mt19937 rng(0xC1100001u);
    for(unsigned trial=0;trial<160;++trial) {
        const xgpu::Index n=1+rng()%65;
        xgpu::Kernel kernel{{{O::Index,0}}};
        for(unsigned step=0;step<60;++step) {
            I i{}; i.dst=1+rng()%6; i.a=rng()%7; i.b=rng()%7;
            switch(rng()%16) {
                case 0:i.op=O::Constant; i.value=rng(); break;
                case 1:i.op=O::Move; break;
                case 2:i.op=O::Add; break;
                case 3:i.op=O::BitAnd; break;
                case 4:i.op=O::BitOr; break;
                case 5:i.op=O::BitXor; break;
                case 6:i.op=O::Less; break;
                case 7:i.op=O::Equal; break;
                case 8:case 9:
                    kernel.code.push_back({O::Constant,7,0,0,0,static_cast<xgpu::Word>(rng()%32)});
                    i.b=7; i.op=step%2?O::ShiftLeft:O::ShiftRight; break;
                case 10:
                    kernel.code.push_back({O::Constant,7,0,0,0,static_cast<xgpu::Word>(rng())});
                    i.b=7; i.op=O::Multiply; break;
                case 11:i.op=O::Patch; i.k=rng()%n; i.value=rng(); break;
                case 12:i.op=O::Choose; i.k=rng()%(n+1); break;
                case 13:i.op=O::StoreGlobal; i.a=0; break;
                case 14:i.op=O::LoadGlobal; i.a=0; break;
                case 15:
                    i.op=O::BranchIndexLess; i.k=rng()%(n+1);
                    i.value=static_cast<xgpu::Word>(kernel.code.size()+2);
                    kernel.code.push_back(i);
                    i={O::Constant,static_cast<unsigned>(1+rng()%6),0,0,0,static_cast<xgpu::Word>(rng())}; break;
            }
            kernel.code.push_back(i);
            if(step%11==0) kernel.code.push_back({O::Output,i.dst});
        }
        kernel.code.push_back({O::Output,1}); kernel.code.push_back({O::Halt});
        xgpu::Execution state(n); execution_reference::Dense dense(n);
        state.enqueue(kernel,n,8); dense.enqueue(kernel,n,8);
        differential(std::move(state),std::move(dense));
    }
}
void scattered_memory() {
    constexpr xgpu::Index n=64;
    xgpu::Field initial(n); std::mt19937 rng(110064);
    execution_reference::Dense dense(n);
    for(xgpu::Index lane=0;lane<n;++lane) {
        const auto value=static_cast<xgpu::Word>(rng());
        initial=xgpu::Field(initial.patched(lane,value)); dense.memory[lane]=value;
    }
    xgpu::Execution state(n); state.initialize_global(initial); ++dense.revision;
    xgpu::Kernel kernel{{{O::Index,0},{O::Constant,1,0,0,0,63},{O::BitXor,2,0,1},
        {O::LoadGlobal,3,2},{O::Constant,4,0,0,0,7},{O::Add,5,3,4},
        {O::StoreGlobal,0,2,5},{O::AtomicAddGlobal,0,0,0},{O::Output,0},{O::Halt}}};
    state.enqueue(kernel,n,8); dense.enqueue(kernel,n,8);
    differential(state,dense,true);
    check(state.run(),"memoria dispersa no termina");
    for(xgpu::Index lane=0;lane<n;++lane) {
        const auto before=static_cast<xgpu::Word>(std::uint64_t(initial.at(lane))+7);
        check(state.dispatch(0).output->at(lane)==before,"alias de direccion atomica altero operandos");
        check(state.global().at(lane)==static_cast<xgpu::Word>(std::uint64_t(before)+lane),"escritura dispersa distinta");
    }
    xgpu::Kernel shared{{{O::Index,0},{O::LocalIndex,1},{O::GroupIndex,2},
        {O::StoreShared,0,1,0},{O::Barrier},{O::Constant,3,0,0,0,7},
        {O::BitXor,4,1,3},{O::LoadShared,5,4},{O::Output,5},{O::Halt}}};
    xgpu::Execution groups(n); execution_reference::Dense group_reference(n);
    groups.enqueue(shared,n,8,8); group_reference.enqueue(shared,n,8,8);
    differential(groups,group_reference,true);
    check(groups.run(),"permutacion compartida no termina");
    for(xgpu::Index lane=0;lane<n;++lane)
        check(groups.dispatch(0).output->at(lane)==(lane/8)*8+((lane%8)^7),"lectura compartida consulta otro grupo");
}
template<class F> void unchanged_rejection(xgpu::Execution state,F action) {
    const auto before=state.snapshot(); rejected([&]{action(state);});
    check(state.snapshot()==before,"rechazo publico parte de memoria/control");
}
void failures() {
    const auto bad_memory=[](O op) {
        xgpu::Execution e(4); e.enqueue({{{O::Index,0},{O::Constant,1,0,0,0,91},{op,2,0,1},{O::Halt}}},5,4);
        e.step(); e.step(); return e;
    };
    unchanged_rejection(bad_memory(O::StoreGlobal),[](xgpu::Execution& e){e.step();});
    unchanged_rejection(bad_memory(O::LoadGlobal),[](xgpu::Execution& e){e.step();});
    unchanged_rejection(bad_memory(O::AtomicAddGlobal),[](xgpu::Execution& e){e.step();});
    xgpu::Execution collision(2);
    collision.enqueue({{{O::Constant,0},{O::Constant,1,0,0,0,1},{O::StoreGlobal,0,0,1},{O::Halt}}},4);
    collision.step(); collision.step();
    unchanged_rejection(collision,[](xgpu::Execution& e){e.step();});
    xgpu::Execution shift(4);
    shift.enqueue({{{O::Index,0},{O::Constant,1,0,0,0,32},{O::ShiftLeft,2,0,1},{O::Halt}}},4);
    shift.step(); shift.step(); unchanged_rejection(shift,[](xgpu::Execution& e){e.step();});
    xgpu::Execution limited(4,{1,1,1}); limited.enqueue({{{O::LocalIndex,0},{O::Halt}}},4,2);
    unchanged_rejection(limited,[](xgpu::Execution& e){e.step();});
    unchanged_rejection(limited,[](xgpu::Execution& e){e.enqueue({{{O::Halt}}},4);});
    xgpu::Execution dependency(4); dependency.enqueue({{{O::Halt}}},4);
    unchanged_rejection(dependency,[](xgpu::Execution& e){e.enqueue({{{O::Halt}}},4,2,0,{1});});
    unchanged_rejection(dependency,[](xgpu::Execution& e){e.enqueue({{{O::Halt}}},4,2,0,{0,0});});
    unchanged_rejection(dependency,[](xgpu::Execution& e){e.enqueue({{{O::Halt}}},4,0);});
    unchanged_rejection(dependency,[](xgpu::Execution& e){e.initialize_global(xgpu::Field(4));});
    xgpu::Execution group_limit(4,{4,1,4}); group_limit.enqueue({{{O::Barrier},{O::Halt}}},4,2);
    unchanged_rejection(group_limit,[](xgpu::Execution& e){e.step();});
    // One lane exits while its peers wait: a checkpoint must preserve the deadlock.
    xgpu::Execution deadlock(4);
    deadlock.enqueue({{{O::BranchIndexLess,0,0,0,1,2},{O::Halt},{O::Barrier},{O::Halt}}},4,4);
    deadlock.step(); deadlock.step(); deadlock.step();
    check(deadlock.dispatch(0).waiting.size()==1,"espera incompleta no conservada");
    unchanged_rejection(deadlock,[](xgpu::Execution& e){e.step();});
    auto restored=xgpu::Execution::restore(deadlock.snapshot());
    unchanged_rejection(restored,[](xgpu::Execution& e){e.step();});
    xgpu::Execution incompatible(4);
    incompatible.enqueue({{{O::BranchIndexLess,0,0,0,1,2},{O::Barrier},{O::Barrier},{O::Halt}}},4,4);
    incompatible.step(); incompatible.step();
    unchanged_rejection(incompatible,[](xgpu::Execution& e){e.step();});
    xgpu::Execution looping(1); looping.enqueue({{{O::Jump,0,0,0,0},{O::Halt}}},1);
    check(!looping.run(19) && looping.counters().steps==19,"presupuesto de pasos no conserva ejecucion parcial");
    check(xgpu::Execution::restore(looping.snapshot()).snapshot()==looping.snapshot(),"bucle parcial no recupera");
    rejected([]{xgpu::Kernel{{{static_cast<O>(999)},{O::Halt}}}.validate(2);});
    rejected([]{xgpu::Kernel{{{O::Jump,0,0,0,3},{O::Halt}}}.validate(2);});
    rejected([]{xgpu::Kernel{{{O::Halt,8}}}.validate(2);});
    rejected([]{xgpu::parse_execution("xgpu 2\ndominio 4\ngrupo 2\ncompartida 0\nglobal 4\nsalta ausente\nfin\n");});
}
std::string wrap(const std::string& payload) {
    return "XGPU-C11 1\n"+std::to_string(payload.size())+" "+std::to_string(xgpu::ExecutionCodec::checksum(payload))+"\n"+payload;
}
void corrupt_archives() {
    xgpu::Execution e(4); e.enqueue({{{O::Halt}}},4,2);
    const auto good=e.snapshot();
    for(std::size_t length=0;length<good.size();++length)
        rejected([&]{xgpu::Execution::restore(good.substr(0,length));});
    for(std::size_t at=0;at<good.size();++at) {
        auto changed=good; changed[at]^=1;
        rejected([&]{xgpu::Execution::restore(changed);});
    }
    rejected([&]{xgpu::Execution::restore(good+"tail");});
    const auto body=good.substr(good.find('\n',good.find('\n')+1)+1);
    auto bad=body; bad.replace(0,bad.find(' '),"0");
    rejected([&]{xgpu::Execution::restore(wrap(bad));});
    bad=body;
    const auto mask=bad.find("\n1\n0 1 0 4\n"); check(mask!=std::string::npos,"no se encontro mascara de prueba");
    bad.replace(mask,std::string("\n1\n0 1 0 4\n").size(),"\n1\n0 1 1 4\n");
    rejected([&]{xgpu::Execution::restore(wrap(bad));});
    rejected([&]{xgpu::Execution::restore(wrap(body+"garbage\n"));});
    rejected([&]{xgpu::Execution::restore(wrap("65536 65536 256\n-1 0\n"));});
}
void structured_domain() {
    const auto n=xgpu::domain_limit;
    xgpu::Kernel kernel{{{O::Index,0},{O::Constant,1,0,0,0,3},
        {O::BranchIndexLess,0,0,0,n/2,5},{O::Multiply,2,0,1},{O::Jump,0,0,0,6},
        {O::Add,2,0,1},{O::StoreGlobal,0,0,2},{O::LoadGlobal,3,0},{O::Output,3},{O::Halt}}};
    xgpu::Execution state(n); state.enqueue(kernel,n,64);
    check(state.run(),"dominio estructurado de 32 bits no termina");
    for(const auto lane:std::vector<xgpu::Index>{0,1,n/2-1,n/2,n-2,n-1}) {
        const auto expected=static_cast<xgpu::Word>(lane<n/2?lane+3:lane*3);
        check(state.dispatch(0).output->at(lane)==expected && state.global().at(lane)==expected,"frontera de dominio grande distinta");
    }
    check(state.counters().scalar_lane_evaluations==0,"dominio estructurado se expandio");
    large_bytes=state.snapshot().size(); check(large_bytes<4096,"dominio estructurado ocupa demasiado archivo");
    check(xgpu::Execution::restore(state.snapshot()).snapshot()==state.snapshot(),"dominio estructurado no restaura");
}
}
int main() {
    try {
        masks(); original_profile(); synchronization(); atomics_and_dependencies();
        random_kernels(); scattered_memory(); failures(); corrupt_archives(); structured_domain();
        std::cout<<"{\"profile\":\"XGPU-C11\",\"checks\":"<<checks<<",\"random_kernels\":160"
                 <<",\"differential_steps\":"<<differential_steps<<",\"restored_states\":"<<restored_states
                 <<",\"waiting_snapshots\":"<<waiting_snapshots<<",\"million_snapshot_bytes\":"<<million_bytes
                 <<",\"large_structured_domain\":4294967296,\"large_snapshot_bytes\":"<<large_bytes
                 <<",\"large_domain_validation\":\"boundary queries, not exhaustive\""
                 <<",\"hardware_gpu_executed\":false,\"ether_integrated\":false,\"failures\":0}\n";
        return 0;
    } catch(const std::exception& error) {
        std::cerr<<"C11 FALLO despues de "<<checks<<" comprobaciones: "<<error.what()<<'\n'; return 1;
    }
}
