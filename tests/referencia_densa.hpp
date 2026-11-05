#pragma once
#include "estado_gpu.hpp"

// Oraculo de prueba: ejecuta cada invocacion explicitamente; no usa Field/Mode.
namespace reference {
class Dense {
    xgpu::Program program_;
    std::size_t pc_=0;
public:
    std::array<std::vector<std::uint32_t>,8> registers;
    std::vector<std::uint32_t> output;
    bool stored=false;
    explicit Dense(xgpu::Program p) : program_(std::move(p)) {
        program_.validate();
        xgpu::require(program_.count <= 16777216, "limite del oraculo denso");
        for (auto& reg : registers) reg.resize(static_cast<std::size_t>(program_.count),0);
    }
    bool step() {
        if (pc_==program_.code.size()) return false;
        const auto i=program_.code[pc_];
        const auto n=static_cast<std::size_t>(program_.count);
        switch (i.op) {
            case xgpu::Op::Index:
                for (std::size_t j=0;j<n;++j) registers[i.dst][j]=static_cast<std::uint32_t>(j);
                break;
            case xgpu::Op::Constant:
                std::fill(registers[i.dst].begin(),registers[i.dst].end(),i.value); break;
            case xgpu::Op::Add:
                for (std::size_t j=0;j<n;++j)
                    registers[i.dst][j]=static_cast<std::uint32_t>(std::uint64_t(registers[i.a][j])+registers[i.b][j]);
                break;
            case xgpu::Op::Multiply:
                for (std::size_t j=0;j<n;++j)
                    registers[i.dst][j]=static_cast<std::uint32_t>(std::uint64_t(registers[i.a][j])*registers[i.b][j]);
                break;
            case xgpu::Op::Patch: registers[i.dst][static_cast<std::size_t>(i.k)]=i.value; break;
            case xgpu::Op::Choose:
                for (std::size_t j=0;j<n;++j) registers[i.dst][j]=registers[j<i.k ? i.a : i.b][j];
                break;
            case xgpu::Op::Store: output=registers[i.dst]; stored=true; break;
            case xgpu::Op::Halt: break;
        }
        ++pc_;
        return true;
    }
    void run() { while(step()) {} }
};
} // namespace reference
