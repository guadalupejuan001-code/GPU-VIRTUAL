#include "snapshot_gpu.hpp"
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>

using Clock=std::chrono::steady_clock;
double milliseconds(Clock::time_point a,Clock::time_point b) {return std::chrono::duration<double,std::milli>(b-a).count();}
long peak_rss() {
    std::ifstream in("/proc/self/status");
    for(std::string line;std::getline(in,line);) if(line.compare(0,6,"VmHWM:")==0) {
        std::istringstream s(line.substr(6));long value=0;s>>value;return value;
    }
    return 0;
}
int main(int argc,char** argv) {
    try {
        xgpu::require(argc==3,"uso: bench periodic|gather|uniform N");
        const std::string workload=argv[1];const auto n=static_cast<xgpu::Index>(std::stoull(argv[2]));
        xgpu::require(n && n<=65536 && (n&(n-1))==0,"dominio de benchmark invalido");
        using O=xgpu::StateOp;xgpu::Kernel kernel;
        if(workload=="periodic") kernel={{{O::LocalIndex,0},{O::Constant,1,0,0,0,63},{O::BitXor,2,0,1},
            {O::Constant,3,0,0,0,3},{O::Add,4,2,3},{O::Add,5,2,3},{O::Multiply,6,4,5},{O::Output,6},{O::Halt}}};
        else if(workload=="gather") kernel={{{O::Index,0},{O::Constant,1,0,0,0,static_cast<xgpu::Word>(n-1)},
            {O::BitXor,2,0,1},{O::LoadGlobal,3,2},{O::Constant,4,0,0,0,5},{O::Add,5,3,4},
            {O::StoreGlobal,0,2,5},{O::LoadGlobal,6,0},{O::Output,6},{O::Halt}}};
        else {xgpu::require(workload=="uniform","carga desconocida");kernel=xgpu::Kernel::from_c01(xgpu::example(n));}
        const auto begin=Clock::now();
#ifdef XGPU_RELATIONS
        xgpu::Execution state(n,{},xgpu::ExecutionProfile::C12);
#else
        xgpu::Execution state(n);
#endif
        if(workload=="gather") state.initialize_global(xgpu::Field(n,xgpu::Mode({7,3})));
        state.enqueue(kernel,n,64);const auto prepared=Clock::now();
        xgpu::require(state.run(),"benchmark no termina");const auto executed=Clock::now();
        std::vector<xgpu::Word> output(n);
#ifdef XGPU_RELATIONS
        xgpu::ValueReader reader(state.dispatch(0).output);
        for(xgpu::Index i=0;i<n;++i) output[i]=reader.at(i);
#else
        for(xgpu::Index i=0;i<n;++i) output[i]=state.dispatch(0).output->at(i);
#endif
        const auto materialized=Clock::now();
        const auto bytes=state.snapshot();const auto checkpointed=Clock::now();
        std::uint64_t hash=14695981039346656037ULL;
        for(xgpu::Index i=0;i<n;++i) {
            xgpu::Word expected=0;
            if(workload=="periodic") {const auto v=((i%64)^63)+3;expected=static_cast<xgpu::Word>(v*v);}
            else if(workload=="gather") expected=static_cast<xgpu::Word>(3*i+12);
            else {const auto v=static_cast<xgpu::Word>(3*i+12);expected=i==n-1?16u:i<n/2?v:static_cast<xgpu::Word>(std::uint64_t{v}*v);}
            xgpu::require(output[i]==expected,"salida completa no coincide con formula independiente");
            for(unsigned b=0;b<4;++b) {hash^=(output[i]>>(8*b))&255;hash*=1099511628211ULL;}
        }
        std::cout<<std::fixed<<std::setprecision(6)<<"{\"workload\":\""<<workload<<"\",\"n\":"<<n
#ifdef XGPU_RELATIONS
                 <<",\"backend\":\"C12\""
#else
                 <<",\"backend\":\"pinned-C11\""
#endif
                 <<",\"prepare_ms\":"<<milliseconds(begin,prepared)<<",\"execute_ms\":"<<milliseconds(prepared,executed)
                 <<",\"materialize_ms\":"<<milliseconds(executed,materialized)<<",\"total_to_output_ms\":"<<milliseconds(begin,materialized)
                 <<",\"checkpoint_ms\":"<<milliseconds(materialized,checkpointed)<<",\"checkpoint_bytes\":"<<bytes.size()
                 <<",\"scalar_lane_evaluations\":"<<state.counters().scalar_lane_evaluations<<",\"native_peak_rss_kib\":"<<peak_rss()
                 <<",\"complete_output_checked\":true,\"output_hash\":\""<<std::hex<<hash<<"\"}\n";
        return 0;
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
