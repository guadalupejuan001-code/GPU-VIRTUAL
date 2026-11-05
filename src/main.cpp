#include "programa.hpp"
#include "referencia_densa.hpp"
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>
#if defined(__unix__)
#include <sys/resource.h>
#endif

namespace {
using Clock=std::chrono::steady_clock;
double ms(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double,std::milli>(b-a).count();
}
std::uint64_t checksum(const std::vector<xgpu::Word>& words) {
    std::uint64_t hash=14695981039346656037ULL;
    for (auto word : words) for (unsigned b=0;b<4;++b) {
        hash ^= (word>>(b*8)) & 255u;
        hash *= 1099511628211ULL;
    }
    return hash;
}
long peak_rss() {
#if defined(__unix__)
    rusage usage{};
    return getrusage(RUSAGE_SELF,&usage)==0 ? usage.ru_maxrss : 0;
#else
    return 0;
#endif
}
long native_peak_rss() {
    // Linux reinicia VmHWM al ejecutar la imagen nativa; ru_maxrss puede
    // conservar el maximo anterior del proceso que la lanzo.
    std::ifstream status("/proc/self/status");
    for(std::string line; std::getline(status,line);) {
        if(line.compare(0,6,"VmHWM:")==0) {
            std::istringstream value(line.substr(6));
            long kib=0;
            if(value >> kib) return kib;
        }
    }
    return 0;
}
void report(const xgpu::Machine& machine) {
    const auto& output=machine.output();
    std::size_t coefficients=0;
    for(const auto& band: output.bands) coefficients+=band.mode.coefficients.size();
    std::cout << "{\"profile\":\"XGPU-C01\",\"host\":\"CPU\",\"logical_invocations\":" << machine.count()
        << ",\"pc\":" << machine.pc() << ",\"revision\":" << machine.revision()
        << ",\"output_bands\":" << output.bands.size() << ",\"output_coefficients\":" << coefficients
        << ",\"output_details\":" << output.details.size()
        << ",\"complete_snapshot_bytes\":" << machine.snapshot().size()
        << ",\"samples\":[";
    const std::array<xgpu::Index,4> positions{{0, std::min<xgpu::Index>(1,machine.count()-1),machine.count()/2,machine.count()-1}};
    for (unsigned j=0;j<positions.size();++j) {
        if(j) std::cout << ',';
        std::cout << "{\"i\":" << positions[j] << ",\"value\":" << output.at(positions[j]) << '}';
    }
    std::cout << "]}\n";
}
}
int main(int argc,char** argv) {
    try {
        if(argc>=3 && std::string(argv[1])=="run") {
            std::ifstream source(argv[2]);
            xgpu::require(static_cast<bool>(source),"no se puede abrir el programa");
            xgpu::Machine machine(xgpu::parse(source));
            machine.run();
            if(argc==4) {
                std::ofstream out(argv[3],std::ios::binary);
                out << machine.snapshot();
                xgpu::require(static_cast<bool>(out),"no se pudo guardar el snapshot");
            }
            report(machine);
            return 0;
        }
        if(argc==3 && std::string(argv[1])=="resume") {
            std::ifstream source(argv[2],std::ios::binary);
            xgpu::require(static_cast<bool>(source),"no se puede abrir el snapshot");
            std::ostringstream text; text << source.rdbuf();
            auto machine=xgpu::Machine::restore(text.str());
            machine.run(); report(machine); return 0;
        }
        if(argc==4 && std::string(argv[1])=="benchmark") {
            const auto n=xgpu::decimal(argv[3],16777216);
            const auto program=xgpu::example(n);
            const std::string backend=argv[2];
            xgpu::require(backend=="compact" || backend=="dense","backend invalido");
            const auto begin=Clock::now();
            Clock::time_point prepared,executed,materialized;
            std::vector<xgpu::Word> result;
            std::size_t snapshot_bytes=0;
            // Los constructores, ejecucion y salida completa se cronometran por separado.
            // snapshot_bytes se calcula despues del intervalo total cronometrado.
            if(backend=="compact") {
                xgpu::Machine machine(program); prepared=Clock::now();
                machine.run(); executed=Clock::now();
                result=machine.materialize(0,n); materialized=Clock::now();
                snapshot_bytes=machine.snapshot().size();
            } else {
                reference::Dense machine(program); prepared=Clock::now();
                machine.run(); executed=Clock::now();
                // Store ya produjo la salida completa en el ejecutor denso.
                result=std::move(machine.output); materialized=Clock::now();
            }
            std::cout << std::fixed << std::setprecision(6)
                << "{\"backend\":\"" << backend << "\",\"host\":\"CPU\",\"n\":" << n
                << ",\"prepare_ms\":" << ms(begin,prepared)
                << ",\"execute_ms\":" << ms(prepared,executed)
                << ",\"materialize_ms\":" << ms(executed,materialized)
                << ",\"total_to_output_ms\":" << ms(begin,materialized)
                << ",\"output_bytes\":" << result.size()*sizeof(xgpu::Word)
                << ",\"dense_register_value_bytes\":" << (backend=="dense" ? n*8*4 : 0)
                << ",\"complete_snapshot_bytes\":" << snapshot_bytes
                << ",\"peak_process_rss_kib\":" << peak_rss()
                << ",\"native_peak_rss_kib\":" << native_peak_rss()
                << ",\"fnv1a64_output\":\"" << std::hex << checksum(result) << "\"}\n";
            return 0;
        }
        std::cerr << "Uso: gpu_c01 run programa.xgpu [snapshot.xgs]\n"
                  << "     gpu_c01 resume snapshot.xgs\n"
                  << "     gpu_c01 benchmark compact|dense numero_ejecuciones\n";
        return 2;
    } catch(const std::exception& error) {
        std::cerr << "XGPU-C01: " << error.what() << '\n';
        return 1;
    }
}
