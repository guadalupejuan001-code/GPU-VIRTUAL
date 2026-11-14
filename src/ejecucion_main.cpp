#include "programa_ejecucion.hpp"
#include "snapshot_gpu.hpp"
#include "transformacion_reversible.hpp"
#include <fstream>
#include <iostream>
#include <filesystem>
#include <limits>
#include <sstream>
#include <string_view>
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
void save_bytes(const std::string& bytes,const std::string& path) {
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
void save(const xgpu::Execution& state,const std::string& path) {save_bytes(state.snapshot(),path);}

using Reversible = xgpu::ReversibleBitTransform;

std::vector<Reversible::Gate> read_reversible_gates(std::string_view program, std::size_t bits) {
    std::istringstream input{std::string(program)};
    std::vector<Reversible::Gate> gates;
    bool in_batch = false;
    for (std::string line; std::getline(input, line);) {
        if (const auto comment = line.find('#'); comment != std::string::npos) line.resize(comment);
        std::istringstream words(line);
        std::string op, first, second, extra;
        if (!(words >> op)) continue;
        if (op == "begin" || op == "end") {
            xgpu::require(!(words >> extra) && in_batch == (op == "end"),
                          "marcador de lote XI desparejado");
            in_batch = !in_batch;
            continue;
        }
        const bool binary = op == "cx";
        xgpu::require(binary || op == "x", "el perfil reversible XI admite x y cx");
        xgpu::require(static_cast<bool>(words >> first) &&
                      (!binary || static_cast<bool>(words >> second)) && !(words >> extra),
                      "operandos invalidos en el circuito XI");
        const auto index = [&](const std::string& value) {
            return static_cast<std::size_t>(xgpu::decimal(value, bits - 1));
        };
        if (binary) gates.push_back({Reversible::Operation::Cnot, index(second), index(first)});
        else gates.push_back({Reversible::Operation::X, index(first)});
    }
    xgpu::require(!in_batch, "lote XI sin fin");
    return gates;
}

Reversible::Bits bits_from_hex(std::string_view hex, std::size_t bits) {
    if (hex.substr(0, 2) == "0x") hex.remove_prefix(2);
    xgpu::require(!hex.empty() && hex.size() <= (bits + 3) / 4, "entrada hexadecimal fuera de ancho");
    Reversible::Bits input((bits + 63) / 64);
    const std::string_view digits = "0123456789abcdefABCDEF";
    std::size_t nibble = 0;
    for (auto current = hex.rbegin(); current != hex.rend(); ++current, ++nibble) {
        auto value = digits.find(*current);
        xgpu::require(value != std::string_view::npos, "entrada hexadecimal invalida");
        if (value > 15) value -= 6;
        input[nibble / 16] |= std::uint64_t(value) << ((nibble % 16) * 4);
    }
    return input;
}

std::string bits_to_hex(const Reversible::Bits& value, std::size_t bits) {
    constexpr std::string_view digits = "0123456789abcdef";
    std::string hex((bits + 3) / 4, '0');
    for (std::size_t i = 0; i < hex.size(); ++i)
        hex[hex.size() - 1 - i] = digits[(value[i / 16] >> ((i % 16) * 4)) & 15U];
    return hex;
}

void report(const xgpu::Execution& state) {
    const auto& c=state.counters();
    std::cout<<"{\"profile\":\""<<(state.profile()==xgpu::ExecutionProfile::C12?"XGPU-C12":"XGPU-C11")
        <<"\",\"host\":\"CPU\",\"finished\":"<<(state.finished()?"true":"false")
        <<",\"revision\":"<<state.revision()<<",\"dispatches\":"<<state.dispatches().size()
        <<",\"steps\":"<<c.steps<<",\"scalar_lane_evaluations\":"<<c.scalar_lane_evaluations
        <<",\"global_reads\":"<<c.global_reads<<",\"global_writes\":"<<c.global_writes
        <<",\"shared_reads\":"<<c.shared_reads<<",\"shared_writes\":"<<c.shared_writes
        <<",\"atomics\":"<<c.atomics<<",\"barrier_releases\":"<<c.barrier_releases
        <<",\"relation_nodes_created\":"<<c.relation_nodes_created<<",\"relation_reuses\":"<<c.relation_reuses
        <<",\"relation_scalar_checks\":"<<c.relation_scalar_checks<<",\"period_values_checked\":"<<c.period_values_checked
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
        if (argc >= 5 && std::string_view(argv[1]) == "reversible-xi") {
            const auto width = static_cast<std::size_t>(xgpu::decimal(argv[2],
                                                std::numeric_limits<std::size_t>::max() - 63));
            xgpu::require(width > 0, "ancho reversible invalido");
            const auto gates = read_reversible_gates(read_file(argv[3], 16 * 1024 * 1024), width);
            const Reversible transform(width, gates);
            std::vector<std::string> outputs;
            outputs.reserve(static_cast<std::size_t>(argc - 4));
            for (int i = 4; i < argc; ++i)
                outputs.push_back(bits_to_hex(transform.apply(bits_from_hex(argv[i], width)), width));
            const auto counts = transform.operation_counts();
            std::cout << "{\"bits\":" << width << ",\"gates\":" << gates.size()
                      << ",\"packed_terms\":" << counts.packed_terms
                      << ",\"parity_rows\":" << counts.parity_rows << ",\"outputs_hex\":[";
            for (std::size_t i = 0; i < outputs.size(); ++i)
                std::cout << (i ? ",\"" : "\"") << outputs[i] << '"';
            std::cout << "]}\n";
            return 0;
        }
        if ((argc == 4 || argc == 6) && std::string(argv[1]) == "sum-u32") {
            const auto state = xgpu::Execution::restore(read_file(argv[2], xgpu::ExecutionCodec::maximum_bytes));
            const auto id = xgpu::decimal(argv[3], 255);
            const auto& job = state.dispatch(static_cast<std::size_t>(id));
            const auto begin = argc == 6 ? xgpu::decimal(argv[4], job.count) : 0;
            const auto count = argc == 6 ? xgpu::decimal(argv[5], job.count - begin) : job.count;
            const auto requested = xgpu::LaneMask::interval(job.count, begin, begin + count);
            xgpu::require(requested.subtracted(job.output_valid).empty(), "la salida solicitada aun esta incompleta");
            const auto result = xgpu::ValueReader(job.output).sum_unsigned(begin, count);
            std::cout << "{\"job\":" << id << ",\"begin\":" << begin << ",\"words\":" << count
                      << ",\"evaluated_words\":" << result.evaluated_words
                      << ",\"sum_u64\":\"" << result.value << "\"}\n";
            return 0;
        }
        if(argc==5 && std::string(argv[1])=="export") {
            const auto state=xgpu::Execution::restore(read_file(argv[2],xgpu::ExecutionCodec::maximum_bytes));
            const auto id=xgpu::decimal(argv[3],255);const auto& job=state.dispatch(static_cast<std::size_t>(id));
            xgpu::require(job.output_valid.full(),"la salida del despacho aun esta incompleta");
            xgpu::require(job.count<=16777216,"exportacion supera 64 MiB");
            xgpu::ValueReader reader(job.output);std::string bytes(static_cast<std::size_t>(job.count)*4,'\0');
            reader.copy_little_endian(0, static_cast<std::size_t>(job.count), bytes.data());
            save_bytes(bytes,argv[4]);
            std::cout<<"{\"job\":"<<id<<",\"words\":"<<job.count<<",\"output_bytes\":"<<bytes.size()<<"}\n";
            return 0;
        }
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
        std::cerr<<"     gpu_c11 export checkpoint.xgs despacho salida.bin\n";
        std::cerr<<"     gpu_c12 sum-u32 checkpoint.xgs despacho [inicio cantidad]\n";
        std::cerr<<"     gpu_c12 reversible-xi bits circuito.xi hex [hex ...]\n";
        return 2;
    } catch(const std::exception& error) { std::cerr<<"XGPU-C11: "<<error.what()<<'\n'; return 1; }
}
