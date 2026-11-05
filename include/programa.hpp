#pragma once
#include "estado_gpu.hpp"
#include <istream>

namespace xgpu {
inline Index decimal(const std::string& text, Index maximum) {
    require(!text.empty() && text.size() <= 20, "numero decimal invalido");
    Index value=0;
    for (char c : text) {
        require(c >= '0' && c <= '9', "numero decimal invalido");
        const auto digit=static_cast<Index>(c-'0');
        require(digit <= maximum && value <= (maximum-digit)/10, "numero fuera de rango");
        value=value*10+digit;
    }
    return value;
}
inline unsigned register_id(const std::string& text) {
    require(text.size()==2 && text[0]=='r' && text[1]>='0' && text[1]<='7', "registro invalido");
    return static_cast<unsigned>(text[1]-'0');
}
inline Program parse(std::istream& source) {
    Program p{0,{}};
    std::string line;
    bool header=false, domain=false;
    unsigned line_number=0;
    while (std::getline(source,line)) {
        ++line_number;
        require(line.size() <= 65536, "linea demasiado grande");
        line=line.substr(0,line.find('#'));
        std::istringstream in(line);
        std::vector<std::string> words;
        for (std::string word; in >> word;) words.push_back(word);
        if (words.empty()) continue;
        try {
            if (!header) {
                require(words==std::vector<std::string>({"xgpu","1"}), "se esperaba xgpu 1");
                header=true;
                continue;
            }
            if (!domain) {
                require(words.size()==2 && words[0]=="dominio", "se esperaba dominio");
                p.count=decimal(words[1],domain_limit);
                require(p.count > 0, "dominio vacio");
                domain=true;
                continue;
            }
            require(p.code.size() < 100000, "programa demasiado grande");
            Instruction i{};
            const auto& op=words[0];
            const auto arity=[&words](std::size_t n){ require(words.size()==n, "numero de operandos invalido"); };
            if (op=="indice") { arity(2); i.op=Op::Index; i.dst=register_id(words[1]); }
            else if (op=="constante") {
                arity(3); i.op=Op::Constant; i.dst=register_id(words[1]);
                i.value=static_cast<Word>(decimal(words[2],UINT32_MAX));
            } else if (op=="suma" || op=="producto") {
                arity(4); i.op=op=="suma" ? Op::Add : Op::Multiply;
                i.dst=register_id(words[1]); i.a=register_id(words[2]); i.b=register_id(words[3]);
            } else if (op=="detalle") {
                arity(4); i.op=Op::Patch; i.dst=register_id(words[1]);
                i.k=decimal(words[2],p.count-1); i.value=static_cast<Word>(decimal(words[3],UINT32_MAX));
            } else if (op=="elige") {
                arity(5); i.op=Op::Choose; i.dst=register_id(words[1]);
                i.k=decimal(words[2],p.count); i.a=register_id(words[3]); i.b=register_id(words[4]);
            } else if (op=="salida") { arity(2); i.op=Op::Store; i.dst=register_id(words[1]); }
            else if (op=="fin") { arity(1); i.op=Op::Halt; }
            else throw std::runtime_error("operacion desconocida: "+op);
            p.code.push_back(i);
        } catch (const std::exception& error) {
            throw std::runtime_error("linea "+std::to_string(line_number)+": "+error.what());
        }
    }
    require(header && domain, "cabecera o dominio ausente");
    p.validate();
    return p;
}
} // namespace xgpu
