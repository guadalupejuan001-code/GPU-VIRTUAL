#pragma once
#include "programa.hpp"
#include "ejecucion_gpu.hpp"

namespace xgpu {
struct ExecutionSource {
    Index count=0,global_words=0;
    unsigned local_size=64,shared_words=0;
    Kernel kernel;
    ExecutionProfile profile=ExecutionProfile::C11;
};
inline ExecutionSource parse_execution(const std::string& text) {
    require(text.size()<=16*1024*1024,"fuente C11 demasiado grande");
    std::vector<std::vector<std::string>> lines;
    std::istringstream source(text);
    for(std::string line;std::getline(source,line);) {
        require(line.size()<=65536 && lines.size()<200005,"presupuesto de fuente C11");
        line=line.substr(0,line.find('#')); std::istringstream words(line);
        std::vector<std::string> row;
        for(std::string word;words>>word;) row.push_back(word);
        if(!row.empty()) lines.push_back(std::move(row));
    }
    require(!lines.empty(),"fuente C11 vacia");
    if(lines[0]==std::vector<std::string>({"xgpu","1"})) {
        std::istringstream legacy(text); auto program=parse(legacy);
        return {program.count,program.count,64,0,Kernel::from_c01(program)};
    }
    const bool relational=lines[0]==std::vector<std::string>({"xgpu","3"});
    require((relational || lines[0]==std::vector<std::string>({"xgpu","2"})) && lines.size()>=6,"se esperaba xgpu 2/3 y sus dominios");
    ExecutionSource result;
    if(relational) result.profile=ExecutionProfile::C12;
    const auto parameter=[&](std::size_t at,const char* name,Index maximum) {
        require(lines[at].size()==2 && lines[at][0]==name,"declaracion de dominio C11 invalida");
        return decimal(lines[at][1],maximum);
    };
    result.count=parameter(1,"dominio",domain_limit);
    result.local_size=static_cast<unsigned>(parameter(2,"grupo",1024));
    result.shared_words=static_cast<unsigned>(parameter(3,"compartida",65536));
    result.global_words=parameter(4,"global",domain_limit);
    require(result.count && result.local_size && result.global_words,"dominio C11 vacio");
    struct LabelUse { std::size_t pc; std::string label; bool conditional_index; };
    std::map<std::string,std::size_t> labels;
    std::vector<LabelUse> uses;
    const auto valid_label=[](const std::string& name) {
        require(!name.empty() && name.size()<=64,"etiqueta C11 invalida");
        for(char c:name) require((c>='a' && c<='z') || (c>='A' && c<='Z') ||
                                (c>='0' && c<='9') || c=='_',"etiqueta C11 invalida");
    };
    const std::map<std::string,StateOp> binary{
        {"suma",StateOp::Add},{"producto",StateOp::Multiply},{"y",StateOp::BitAnd},
        {"o",StateOp::BitOr},{"xor",StateOp::BitXor},{"desplaza_izq",StateOp::ShiftLeft},
        {"desplaza_der",StateOp::ShiftRight},{"menor",StateOp::Less},{"igual",StateOp::Equal},
        {"suma_f32",StateOp::FloatAdd},{"producto_f32",StateOp::FloatMultiply},{"menor_f32",StateOp::FloatLess}};
    for(std::size_t line=5;line<lines.size();++line) {
        const auto& words=lines[line]; const auto& name=words[0];
        const auto arity=[&](std::size_t n){require(words.size()==n,"operandos C11 invalidos");};
        if(name=="marca") {
            arity(2); valid_label(words[1]);
            require(labels.emplace(words[1],result.kernel.code.size()).second,"etiqueta C11 repetida");
            continue;
        }
        require(result.kernel.code.size()<100000,"kernel C11 demasiado grande");
        StateInstruction i{};
        if(name=="indice" || name=="local" || name=="grupo_id" || name=="salida") {
            arity(2); i.dst=register_id(words[1]);
            i.op=name=="indice"?StateOp::Index:name=="local"?StateOp::LocalIndex:
                 name=="grupo_id"?StateOp::GroupIndex:StateOp::Output;
        } else if(name=="constante") {
            arity(3); i.op=StateOp::Constant; i.dst=register_id(words[1]);
            i.value=static_cast<Word>(decimal(words[2],UINT32_MAX));
        } else if(name=="mueve" || name=="flotante_u32") {
            arity(3); i.op=name=="mueve"?StateOp::Move:StateOp::FloatFromUnsigned; i.dst=register_id(words[1]); i.a=register_id(words[2]);
        } else if(binary.count(name)) {
            arity(4); i.op=binary.at(name); i.dst=register_id(words[1]);
            i.a=register_id(words[2]); i.b=register_id(words[3]);
        } else if(name=="detalle") {
            arity(4); i.op=StateOp::Patch; i.dst=register_id(words[1]);
            i.k=decimal(words[2],result.count-1); i.value=static_cast<Word>(decimal(words[3],UINT32_MAX));
        } else if(name=="elige") {
            arity(5); i.op=StateOp::Choose; i.dst=register_id(words[1]);
            i.k=decimal(words[2],result.count); i.a=register_id(words[3]); i.b=register_id(words[4]);
        } else if(name=="salta" || name=="si" || name=="si_indice_menor") {
            arity(name=="salta"?2:3); valid_label(words.back());
            i.op=name=="salta"?StateOp::Jump:name=="si"?StateOp::Branch:StateOp::BranchIndexLess;
            if(name=="si") i.a=register_id(words[1]);
            if(name=="si_indice_menor") i.k=decimal(words[1],result.count);
            uses.push_back({result.kernel.code.size(),words.back(),name=="si_indice_menor"});
        } else if(name=="carga_global" || name=="carga_compartida") {
            arity(3); i.op=name=="carga_global"?StateOp::LoadGlobal:StateOp::LoadShared;
            i.dst=register_id(words[1]); i.a=register_id(words[2]);
        } else if(name=="guarda_global" || name=="guarda_compartida") {
            arity(3); i.op=name=="guarda_global"?StateOp::StoreGlobal:StateOp::StoreShared;
            i.a=register_id(words[1]); i.b=register_id(words[2]);
        } else if(name=="atomica_global" || name=="atomica_compartida") {
            arity(4); i.op=name=="atomica_global"?StateOp::AtomicAddGlobal:StateOp::AtomicAddShared;
            i.dst=register_id(words[1]); i.a=register_id(words[2]); i.b=register_id(words[3]);
        } else if(name=="barrera" || name=="fin") {
            arity(1); i.op=name=="barrera"?StateOp::Barrier:StateOp::Halt;
        } else throw std::runtime_error("operacion C11 desconocida: "+name);
        require(relational || static_cast<unsigned>(i.op)<=27,"operacion requiere xgpu 3");
        result.kernel.code.push_back(i);
    }
    for(const auto& use:uses) {
        require(labels.count(use.label),"etiqueta C11 inexistente");
        auto& i=result.kernel.code[use.pc];
        if(use.conditional_index) i.value=static_cast<Word>(labels.at(use.label));
        else i.k=labels.at(use.label);
    }
    // The constructor also validates shared-memory geometry and instruction domains.
    DispatchState checked(result.kernel,result.count,result.local_size,result.shared_words);
    return result;
}
inline Execution make_execution(const ExecutionSource& source,ExecutionLimits limits={}) {
    Execution execution(source.global_words,limits,source.profile);
    execution.enqueue(source.kernel,source.count,source.local_size,source.shared_words);
    return execution;
}
} // namespace xgpu
