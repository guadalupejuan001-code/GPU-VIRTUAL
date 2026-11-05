#include "programa.hpp"
#include "referencia_densa.hpp"
#include <fstream>
#include <iostream>
#include <random>

namespace {
std::uint64_t checks=0;
void check(bool ok, const char* message) {
    ++checks;
    if(!ok) throw std::runtime_error(message);
}
template<class F> void rejected(F action) {
    bool failed=false;
    try { action(); } catch(const std::runtime_error&) { failed=true; }
    check(failed,"se esperaba rechazo explicito");
}
void equivalent(const xgpu::Machine& compact, const reference::Dense& dense) {
    check(compact.has_output()==dense.stored,"presencia de salida diferente");
    for(unsigned r=0;r<8;++r)
        for(xgpu::Index i=0;i<compact.count();++i)
            check(compact.reg(r).at(i)==dense.registers[r][static_cast<std::size_t>(i)],"registro diferente");
    if(dense.stored)
        for(xgpu::Index i=0;i<compact.count();++i)
            check(compact.output().at(i)==dense.output[static_cast<std::size_t>(i)],"salida diferente");
}
void small_examples() {
    for(xgpu::Index n : {1u,2u,7u,257u}) {
        const auto p=xgpu::example(n);
        xgpu::Machine compact(p);
        reference::Dense dense(p);
        equivalent(compact,dense);
        while(!compact.finished()) {
            check(compact.step()==dense.step(),"cantidad de pasos diferente");
            equivalent(compact,dense);
            const auto text=compact.snapshot();
            const auto restored=xgpu::Machine::restore(text);
            check(restored.snapshot()==text,"snapshot no conserva el estado exacto");
            equivalent(restored,dense);
            auto resumed=restored;
            auto dense_resumed=dense;
            resumed.run(); dense_resumed.run();
            equivalent(resumed,dense_resumed);
        }
        const auto before=compact.snapshot();
        check(!compact.step(),"maquina finalizada continua avanzando");
        compact.materialize(0,n);
        check(compact.snapshot()==before,"consulta modifico el estado");
    }
}
void random_programs() {
    std::mt19937 random(0x58475055u);
    for(unsigned trial=0;trial<160;++trial) {
        const xgpu::Index n=1+(random()%65);
        xgpu::Program p{n,{}};
        std::array<unsigned,8> degrees{};
        for(unsigned step=0;step<80;++step) {
            xgpu::Instruction i{};
            i.dst=random()%8; i.a=random()%8; i.b=random()%8;
            switch(random()%6) {
                case 0: i.op=xgpu::Op::Index; degrees[i.dst]=1; break;
                case 1: i.op=xgpu::Op::Constant; i.value=random(); degrees[i.dst]=0; break;
                case 2:
                    i.op=xgpu::Op::Add; degrees[i.dst]=std::max(degrees[i.a],degrees[i.b]); break;
                case 3:
                    if(degrees[i.a]+degrees[i.b] > 12) {
                        i.op=xgpu::Op::Add; degrees[i.dst]=std::max(degrees[i.a],degrees[i.b]);
                    } else {
                        i.op=xgpu::Op::Multiply; degrees[i.dst]=degrees[i.a]+degrees[i.b];
                    }
                    break;
                case 4: i.op=xgpu::Op::Patch; i.k=random()%n; i.value=random(); break;
                case 5:
                    i.op=xgpu::Op::Choose; i.k=random()%(n+1);
                    degrees[i.dst]=std::max(degrees[i.a],degrees[i.b]); break;
            }
            p.code.push_back(i);
            if(step%17==0) p.code.push_back({xgpu::Op::Store,i.dst});
        }
        p.code.push_back({xgpu::Op::Store,0});
        p.code.push_back({xgpu::Op::Halt});
        xgpu::Machine compact(p);
        reference::Dense dense(p);
        while(!compact.finished()) {
            compact.step(); dense.step();
            equivalent(compact,dense);
            if(compact.pc()%13==0) {
                auto restored=xgpu::Machine::restore(compact.snapshot());
                check(restored.snapshot()==compact.snapshot(),"reanudacion aleatoria diferente");
                compact=std::move(restored);
            }
        }
    }
}
void bounds_and_failure() {
    rejected([]{xgpu::Field field(0);});
    rejected([]{xgpu::Field field(xgpu::domain_limit+1);});
    xgpu::Field full(xgpu::domain_limit,xgpu::Mode({UINT32_MAX,2}));
    check(full.at(UINT32_MAX)==UINT32_MAX-2,"indice de 32 bits incorrecto");
    rejected([&]{full.at(xgpu::domain_limit);});
    const auto unchanged=full.at(0);
    rejected([&]{full.patched(xgpu::domain_limit,123);});
    check(full.at(0)==unchanged,"rechazo altero campo");
    xgpu::Program p{3,{{xgpu::Op::Index,0}}};
    for(unsigned i=0;i<6;++i) p.code.push_back({xgpu::Op::Multiply,0,0,0});
    p.code.push_back({xgpu::Op::Halt});
    xgpu::Machine machine(p);
    for(unsigned i=0;i<6;++i) machine.step();
    const auto snapshot=machine.snapshot();
    rejected([&]{machine.step();});
    check(machine.snapshot()==snapshot,"rechazo por grado no fue atomico");
    xgpu::Field crowded(xgpu::max_details+1);
    for(xgpu::Index i=0;i<xgpu::max_details;++i) crowded.details[i]=static_cast<xgpu::Word>(i+1);
    crowded.validate();
    rejected([&]{crowded.patched(xgpu::max_details,1);});
    check(crowded.details.size()==xgpu::max_details,"rechazo altero los detalles");
    auto cancelled=xgpu::Field::combine(full,xgpu::Field(full.count),true);
    check(cancelled.bands.size()==1 && cancelled.bands[0].mode.coefficients==std::vector<xgpu::Word>{0},
          "producto por cero no normalizado");
    xgpu::Field sparse(8,xgpu::Mode({2,3}));
    sparse=sparse.patched(5,88);
    check(sparse.details.size()==1,"falta detalle");
    sparse=sparse.patched(5,17);
    check(sparse.details.empty(),"detalle redundante no eliminado");
    sparse=sparse.patched(4,123);
    cancelled=xgpu::Field::combine(sparse,xgpu::Field(8),true);
    check(cancelled.details.empty(),"cancelacion dejo detalle innecesario");
    check(xgpu::Field::choose(0,sparse,xgpu::Field(8)).at(4)==0,"corte cero incorrecto");
    check(xgpu::Field::choose(8,sparse,xgpu::Field(8)).at(4)==123,"corte final incorrecto");
    rejected([&]{xgpu::Field::choose(9,sparse,xgpu::Field(8));});
    rejected([&]{xgpu::Field::combine(sparse,xgpu::Field(9),false);});
    xgpu::Field fragmented(257);
    fragmented.bands.clear();
    for(xgpu::Index i=0;i<257;++i) fragmented.bands.push_back({i,i+1,xgpu::Mode({static_cast<xgpu::Word>(i)})});
    rejected([&]{fragmented.validate();});
    // Alias de salida: publicar un registro conserva ese valor aunque despues cambie.
    xgpu::Machine alias({4,{{xgpu::Op::Constant,0,0,0,0,7},{xgpu::Op::Store,0},
                          {xgpu::Op::Constant,0,0,0,0,9},{xgpu::Op::Halt}}});
    alias.run();
    check(alias.output().at(0)==7 && alias.reg(0).at(0)==9,"salida cambio por alias");
    rejected([&]{alias.materialize(3,2);});
    rejected([&]{alias.materialize(5,0);});
    xgpu::Machine empty_output({4,{{xgpu::Op::Halt}}});
    rejected([&]{empty_output.output();});
    rejected([]{xgpu::Machine bad({4,{{static_cast<xgpu::Op>(9)}}});});
    auto good=xgpu::Machine(xgpu::example(5)).snapshot();
    rejected([&]{xgpu::Machine::restore(good.substr(0,good.size()-4));});
    rejected([&]{xgpu::Machine::restore(good+"garbage");});
    rejected([&]{xgpu::Machine::restore("MAL 1\n");});
    rejected([&]{xgpu::Machine::restore("XGPU-C01 1\n-1 0 0\n");});
    rejected([&]{xgpu::Machine::restore("XGPU-C01 1\n4294967297 0 0\n");});
    for(const auto& text : std::vector<std::string>{
        "xgpu 1\ndominio 2\nprestado r0\nfin\n",
        "xgpu 1\ndominio 2\nconstante r0 4294967296\nfin\n",
        "xgpu 1\ndominio 2\nindice r8\nfin\n",
        "xgpu 1\ndominio 2\nfin\nindice r0\nfin\n",
        "xgpu 1\ndominio 2\nindice r0 extra\nfin\n"}) {
        rejected([&]{std::istringstream source(text); xgpu::parse(source);});
    }
}
void million(const std::string& checkpoint_path) {
    constexpr xgpu::Index n=1048576;
    const auto program=xgpu::example(n);
    xgpu::Machine compact(program);
    for(unsigned i=0;i<5;++i) compact.step();
    const auto checkpoint=compact.snapshot();
    if(!checkpoint_path.empty()) {
        std::ofstream file(checkpoint_path,std::ios::binary);
        file << checkpoint;
        check(static_cast<bool>(file),"fallo al guardar checkpoint");
    }
    compact=xgpu::Machine::restore(checkpoint);
    compact.run();
    reference::Dense dense(program); dense.run();
    for(xgpu::Index i=0;i<n;++i) {
        const std::uint32_t linear=static_cast<std::uint32_t>(3*i+12);
        const std::uint32_t expected=i==n-1 ? 16u : i<n/2 ? linear :
            static_cast<std::uint32_t>(std::uint64_t(linear)*linear);
        check(compact.output().at(i)==expected,"salida cerrada incorrecta");
        check(compact.output().at(i)==dense.output[static_cast<std::size_t>(i)],"salida densa incorrecta");
    }
    check(compact.output().bands.size()==2,"cantidad de regiones inesperada");
    check(compact.output().details.size()==1,"se perdio la diferencia individual");
    check(compact.snapshot().size()<4096,"el estado estructurado se expandio");
}
void irregular_growth() {
    xgpu::Program p{1024,{{xgpu::Op::Constant,0}}};
    std::mt19937 random(20261104);
    for(unsigned i=0;i<1024;++i) p.code.push_back({xgpu::Op::Patch,0,0,0,i,static_cast<xgpu::Word>(random()|1u)});
    p.code.push_back({xgpu::Op::Store,0}); p.code.push_back({xgpu::Op::Halt});
    xgpu::Machine compact(p); compact.run();
    reference::Dense dense(p); dense.run();
    equivalent(compact,dense);
    check(compact.output().details.size()==1024,"estado irregular se simplifico con perdida");
    check(compact.snapshot().size()>1024*4,"crecimiento irregular ocultado");
}
}
int main(int argc,char** argv) {
    try {
        small_examples(); random_programs(); bounds_and_failure();
        million(argc>1 ? argv[1] : ""); irregular_growth();
        std::cout << "{\"profile\":\"XGPU-C01\",\"checks\":" << checks
            << ",\"random_programs\":160,\"million_invocations_exact\":true"
            << ",\"checkpoint_resume\":true,\"uint32_wrap\":true,\"select_regions\":true"
            << ",\"failed_instruction_atomic\":true,\"irregular_details_preserved\":1024"
            << ",\"hardware_gpu_executed\":false,\"ether_integrated\":false,\"failures\":0}\n";
        return 0;
    } catch(const std::exception& error) {
        std::cerr << "FALLO despues de " << checks << " comprobaciones: " << error.what() << '\n';
        return 1;
    }
}
