#include "programa_ejecucion.hpp"
#include "snapshot_gpu.hpp"
#include <fstream>
#include <iostream>
#include <filesystem>
#if defined(__unix__)
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace {
std::string read_file(const std::string& path,std::size_t maximum) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    xgpu::require(static_cast<bool>(file),"no se pudo abrir el archivo");
    const auto size=file.tellg();
    xgpu::require(size>=0 && static_cast<std::uint64_t>(size)<=maximum,"archivo fuera de presupuesto");
    std::string bytes(static_cast<std::size_t>(size),'\0'); file.seekg(0);
    file.read(bytes.data(),static_cast<std::streamsize>(bytes.size()));
    xgpu::require(static_cast<bool>(file),"lectura incompleta"); return bytes;
}
void save(const xgpu::Execution& state,const std::string& path) {
    const auto bytes=state.snapshot();
#if defined(__unix__)
    // Never truncate the previous valid checkpoint before the replacement is complete.
    auto temporary=path+".tmp.XXXXXX";
    int fd=::mkstemp(temporary.data());
    xgpu::require(fd>=0,"no se pudo crear el checkpoint temporal");
    try {
        std::size_t offset=0;
        while(offset<bytes.size()) {
            const auto written=::write(fd,bytes.data()+offset,bytes.size()-offset);
            if(written<0 && errno==EINTR) continue;
            xgpu::require(written>0,"escritura incompleta de checkpoint");
            offset+=static_cast<std::size_t>(written);
        }
        xgpu::require(::fsync(fd)==0,"no se pudo sincronizar el checkpoint");
        const auto closed=::close(fd); fd=-1;
        xgpu::require(closed==0,"no se pudo cerrar el checkpoint");
        xgpu::require(std::rename(temporary.c_str(),path.c_str())==0,"no se pudo publicar el checkpoint");
        auto parent=std::filesystem::path(path).parent_path(); if(parent.empty()) parent=".";
        const int directory=::open(parent.c_str(),O_RDONLY|O_DIRECTORY);
        xgpu::require(directory>=0,"no se pudo abrir el directorio del checkpoint");
        const auto synced=::fsync(directory); const auto directory_closed=::close(directory);
        xgpu::require(synced==0 && directory_closed==0,"no se pudo sincronizar el directorio del checkpoint");
    } catch(...) { if(fd>=0) ::close(fd); std::remove(temporary.c_str()); throw; }
#else
    (void)path;
    throw std::runtime_error("guardado atomico C11 requiere una plataforma POSIX");
#endif
}
void report(const xgpu::Execution& state) {
    const auto& c=state.counters();
    std::cout<<"{\"profile\":\"XGPU-C11\",\"host\":\"CPU\",\"finished\":"<<(state.finished()?"true":"false")
        <<",\"revision\":"<<state.revision()<<",\"dispatches\":"<<state.dispatches().size()
        <<",\"steps\":"<<c.steps<<",\"scalar_lane_evaluations\":"<<c.scalar_lane_evaluations
        <<",\"global_reads\":"<<c.global_reads<<",\"global_writes\":"<<c.global_writes
        <<",\"shared_reads\":"<<c.shared_reads<<",\"shared_writes\":"<<c.shared_writes
        <<",\"atomics\":"<<c.atomics<<",\"barrier_releases\":"<<c.barrier_releases
        <<",\"complete_snapshot_bytes\":"<<state.snapshot().size()<<",\"jobs\":[";
    for(std::size_t id=0;id<state.dispatches().size();++id) {
        if(id) std::cout<<',';
        const auto& d=state.dispatch(id);
        std::cout<<"{\"id\":"<<id<<",\"invocations\":"<<d.count<<",\"ready_program_counters\":"<<d.ready.size()
                 <<",\"waiting_groups\":"<<d.waiting.size()<<",\"halted_invocations\":"<<d.halted.size()<<",\"samples\":[";
        const std::array<xgpu::Index,3> lanes{{0,d.count/2,d.count-1}};
        for(std::size_t i=0;i<lanes.size();++i) {
            if(i) std::cout<<',';
            std::cout<<"{\"lane\":"<<lanes[i]<<",\"pc\":"<<d.lane_pc(lanes[i])<<",\"output\":";
            if(d.output_valid.contains(lanes[i])) std::cout<<d.output->at(lanes[i]); else std::cout<<"null";
            std::cout<<'}';
        }
        std::cout<<"]}";
    }
    std::cout<<"],\"hardware_gpu_executed\":false}\n";
}
}
int main(int argc,char** argv) {
    try {
        if(argc>=3 && (std::string(argv[1])=="run" || std::string(argv[1])=="advance")) {
            const bool partial=std::string(argv[1])=="advance";
            xgpu::require(partial?argc==5:(argc==3 || argc==4),"argumentos de ejecucion invalidos");
            const auto source=xgpu::parse_execution(read_file(argv[2],16*1024*1024));
            auto state=xgpu::make_execution(source);
            const auto budget=partial?xgpu::decimal(argv[3],1000000):1000000;
            const bool finished=state.run(budget);
            if(partial || argc==4) save(state,argv[partial?4:3]);
            report(state); return partial || finished?0:3;
        }
        if(argc>=3 && (std::string(argv[1])=="resume" || std::string(argv[1])=="inspect")) {
            const bool inspect=std::string(argv[1])=="inspect";
            xgpu::require(inspect?argc==3:(argc==3 || argc==4),"argumentos de recuperacion invalidos");
            auto state=xgpu::Execution::restore(read_file(argv[2],xgpu::ExecutionCodec::maximum_bytes));
            const bool finished=inspect?state.finished():state.run();
            if(!inspect && argc==4) save(state,argv[3]);
            report(state); return inspect || finished?0:3;
        }
        std::cerr<<"Uso: gpu_c11 run programa.xgpu [checkpoint.xgs]\n"
                 <<"     gpu_c11 advance programa.xgpu pasos checkpoint.xgs\n"
                 <<"     gpu_c11 resume checkpoint.xgs [resultado.xgs]\n"
                 <<"     gpu_c11 inspect checkpoint.xgs\n";
        return 2;
    } catch(const std::exception& error) { std::cerr<<"XGPU-C11: "<<error.what()<<'\n'; return 1; }
}
