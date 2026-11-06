#pragma once
#include "control_gpu.hpp"
#include <functional>

namespace xgpu {
using FieldRoot=std::shared_ptr<const Field>;
inline FieldRoot field_root(Field value) { return std::make_shared<const Field>(std::move(value)); }

inline Field overlay_field(const Field& old,const Field& next,const LaneMask& mask) {
    require(old.count==next.count && old.count==mask.count,"dominio de escritura distinto");
    if(mask.empty()) return old;
    if(mask.full()) return next;
    Field out(old.count); out.bands.clear();
    std::set<Index> cuts{0,old.count};
    for(const auto& f:{&old,&next}) for(const auto& b:f->bands) cuts.insert(b.begin);
    for(const auto& r:mask.ranges) { cuts.insert(r.begin); cuts.insert(r.end); }
    for(auto p=cuts.begin();std::next(p)!=cuts.end();++p) {
        const auto& source=mask.contains(*p)?next:old;
        Field::append(out.bands,*p,*std::next(p),source.mode_at(*p));
        require(out.bands.size()<=max_bands,"presupuesto de bandas en escritura enmascarada");
    }
    for(const auto& item:old.details) if(!mask.contains(item.first)) out.details.insert(item);
    for(const auto& item:next.details) if(mask.contains(item.first)) out.details.insert(item);
    out.validate(); return out;
}
inline void assign_field(FieldRoot& dst,FieldRoot value,const LaneMask& mask) {
    if(mask.full()) dst=std::move(value);
    else if(!mask.empty() && dst!=value) dst=field_root(overlay_field(*dst,*value,mask));
}
inline void set_field_word(Field& field,Index at,Word value) {
    require(at<field.count,"escritura fuera de memoria");
    if(value==field.base_at(at)) field.details.erase(at);
    else field.details[at]=value;
    require(field.details.size()<=max_details,"presupuesto de memoria irregular");
}

struct BarrierWait {
    std::size_t pc;
    std::uint64_t epoch;
    LaneMask lanes;
};
struct DispatchState {
    std::shared_ptr<const Kernel> kernel;
    Index count;
    unsigned local_size,shared_words;
    std::vector<std::size_t> dependencies;
    std::array<FieldRoot,register_count> registers;
    FieldRoot shared,output;
    LaneMask output_valid,halted;
    std::map<std::size_t,LaneMask> ready;
    std::map<Index,BarrierWait> waiting;
    std::map<Index,std::uint64_t> barrier_epochs;
    std::size_t pc_cursor=0;

    DispatchState(Kernel program,Index n,unsigned local,unsigned words,std::vector<std::size_t> deps={})
        : kernel(std::make_shared<const Kernel>(std::move(program))),count(n),local_size(local),
          shared_words(words),dependencies(std::move(deps)),output_valid(n),halted(n) {
        kernel->validate(n);
        require(local && local<=1024 && words<=65536,"geometria de grupo fuera del perfil C11");
        const Index groups=(n+local-1)/local;
        require(words==0 || groups<=domain_limit/words,"memoria compartida fuera del dominio");
        auto zero=field_root(Field(n)); registers.fill(zero); output=zero;
        shared=field_root(Field(std::max<Index>(1,groups*words)));
        ready.emplace(0,LaneMask(n,true));
    }
    bool finished() const { return halted.full(); }
    Index groups() const { return (count+local_size-1)/local_size; }
    LaneMask group_mask(Index group) const {
        require(group<groups(),"grupo fuera del dominio");
        return LaneMask::interval(count,group*local_size,std::min(count,(group+1)*local_size));
    }
    void add_ready(std::size_t pc,const LaneMask& lanes) {
        if(lanes.empty()) return;
        require(pc<kernel->code.size(),"ejecucion fuera del kernel");
        auto p=ready.find(pc);
        if(p==ready.end()) ready.emplace(pc,lanes);
        else p->second=p->second.joined(lanes);
    }
    bool lane_waiting(Index lane) const {
        require(lane<count,"ejecucion fuera del dominio");
        auto p=waiting.find(lane/local_size);
        return p!=waiting.end() && p->second.lanes.contains(lane);
    }
    std::size_t lane_pc(Index lane) const {
        require(lane<count,"ejecucion fuera del dominio");
        if(halted.contains(lane)) return kernel->code.size();
        auto p=waiting.find(lane/local_size);
        if(p!=waiting.end() && p->second.lanes.contains(lane)) return p->second.pc;
        for(const auto& item:ready) if(item.second.contains(lane)) return item.first;
        throw std::runtime_error("ejecucion sin estado de control");
    }
};

struct ExecutionCounters {
    std::uint64_t steps=0,scalar_lane_evaluations=0,global_reads=0,global_writes=0;
    std::uint64_t shared_reads=0,shared_writes=0,atomics=0,barrier_releases=0;
};
struct ExecutionCodec;
class Execution {
    FieldRoot global_;
    std::vector<DispatchState> jobs_;
    std::size_t dispatch_cursor_=0;
    std::uint64_t revision_=0;
    ExecutionLimits limits_;
    ExecutionCounters counters_;
    friend struct ExecutionCodec;

    static void add_counter(std::uint64_t& counter,std::uint64_t amount) {
        require(amount<=UINT64_MAX-counter,"contador C11 agotado"); counter+=amount;
    }
    void scalar_budget(const LaneMask& mask) {
        require(mask.size()<=limits_.scalar_lanes_per_step,"expansion escalar supera presupuesto C11");
        add_counter(counters_.scalar_lane_evaluations,mask.size());
    }
    template<class F> static void each_lane(const LaneMask& mask,F fn) {
        for(const auto& r:mask.ranges) for(Index lane=r.begin;lane<r.end;++lane) fn(lane);
    }
    static bool uniform(const Field& f) {
        return f.bands.size()==1 && f.bands[0].mode.coefficients.size()==1 && f.details.empty();
    }
    static bool identity(const Field& f) {
        return f.bands.size()==1 && f.bands[0].mode.coefficients==std::vector<Word>({0,1}) && f.details.empty();
    }
    FieldRoot scalar_result(const DispatchState& d,unsigned dst,const LaneMask& mask,
                            const std::function<Word(Index)>& fn) {
        scalar_budget(mask);
        Field out=*d.registers[dst];
        each_lane(mask,[&](Index lane){set_field_word(out,lane,fn(lane));});
        out.validate(); return field_root(std::move(out));
    }
    FieldRoot pointwise(DispatchState& d,const StateInstruction& i,const LaneMask& mask) {
        const auto& a=*d.registers[i.a]; const auto& b=*d.registers[i.b];
        const auto fn=[&](Word av,Word bv)->Word {
            switch(i.op) {
                case StateOp::BitAnd:return av&bv;
                case StateOp::BitOr:return av|bv;
                case StateOp::BitXor:return av^bv;
                case StateOp::ShiftLeft:require(bv<32,"desplazamiento fuera de uint32"); return av<<bv;
                case StateOp::ShiftRight:require(bv<32,"desplazamiento fuera de uint32"); return av>>bv;
                case StateOp::Less:return av<bv;
                case StateOp::Equal:return av==bv;
                default:throw std::runtime_error("operacion puntual invalida");
            }
        };
        if(uniform(a) && uniform(b))
            return field_root(Field(d.count,Mode({fn(a.at(0),b.at(0))})));
        if(d.registers[i.a]==d.registers[i.b] && (i.op==StateOp::Less || i.op==StateOp::Equal))
            return field_root(Field(d.count,Mode({i.op==StateOp::Equal?1u:0u})));
        return scalar_result(d,i.dst,mask,[&](Index lane){return fn(a.at(lane),b.at(lane));});
    }
    LaneMask predicate_mask(const Field& field,const LaneMask& active) {
        bool constants=true;
        for(const auto& b:field.bands) if(b.mode.coefficients.size()!=1) constants=false;
        LaneMask result(field.count);
        if(constants) {
            for(const auto& b:field.bands) if(b.mode.coefficients[0]) result.append(b.begin,b.end);
            // Adjacent nonzero bands can differ in value; normalize the mask.
            LaneMask empty(field.count); result=result.joined(empty);
            for(const auto& item:field.details) {
                auto one=LaneMask::interval(field.count,item.first,item.first+1);
                result=item.second?result.joined(one):result.subtracted(one);
            }
            return result.intersected(active);
        }
        scalar_budget(active);
        each_lane(active,[&](Index lane){if(field.at(lane)) result.append(lane,lane+1);});
        result.validate(); return result;
    }
    void barrier(DispatchState& d,std::size_t pc,const LaneMask& active) {
        Index groups_touched=0;
        for(const auto& r:active.ranges) {
            const auto n=(r.end-1)/d.local_size-r.begin/d.local_size+1;
            require(n<=limits_.barrier_groups-groups_touched,"expansion de barreras supera presupuesto");
            groups_touched+=n;
        }
        std::set<Index> groups;
        for(const auto& r:active.ranges)
            for(Index g=r.begin/d.local_size;g<=(r.end-1)/d.local_size;++g) groups.insert(g);
        for(Index group:groups) {
            const auto complete=d.group_mask(group);
            auto lanes=active.intersected(complete);
            const auto epoch=d.barrier_epochs.count(group)?d.barrier_epochs.at(group):0;
            auto p=d.waiting.find(group);
            if(p!=d.waiting.end()) {
                require(p->second.pc==pc && p->second.epoch==epoch,"grupo espera en barreras incompatibles");
                lanes=lanes.joined(p->second.lanes);
            }
            if(lanes.size()==complete.size()) {
                d.add_ready(pc+1,lanes);
                d.waiting.erase(group);
                require(epoch!=UINT64_MAX,"epoca de barrera agotada");
                d.barrier_epochs[group]=epoch+1;
                add_counter(counters_.barrier_releases,1);
            } else {
                d.waiting.insert_or_assign(group,BarrierWait{pc,epoch,std::move(lanes)});
            }
        }
        require(d.waiting.size()<=limits_.barrier_groups && d.barrier_epochs.size()<=limits_.barrier_groups,
                "historial de grupos supera presupuesto C11");
    }
    void memory(DispatchState& d,const StateInstruction& i,const LaneMask& active) {
        const bool shared=i.op==StateOp::LoadShared || i.op==StateOp::StoreShared || i.op==StateOp::AtomicAddShared;
        const bool load=i.op==StateOp::LoadGlobal || i.op==StateOp::LoadShared;
        const bool atomic=i.op==StateOp::AtomicAddGlobal || i.op==StateOp::AtomicAddShared;
        auto& root=shared?d.shared:global_;
        const auto address=d.registers[i.a],value=d.registers[i.b];
        if(shared) require(d.shared_words>0,"kernel sin memoria compartida");
        if(!shared && root->count==d.count && identity(*address) && !atomic) {
            if(load) assign_field(d.registers[i.dst],root,active);
            else assign_field(root,value,active);
        } else if(!shared && load && uniform(*address)) {
            const auto word=root->at(address->at(0));
            assign_field(d.registers[i.dst],field_root(Field(d.count,Mode({word}))),active);
        } else {
            scalar_budget(active);
            // All operand roots above refer to the state before this instruction.
            Field updated=*root,returned=*d.registers[i.dst];
            std::set<Index> written;
            each_lane(active,[&](Index lane){
                const Index offset=address->at(lane);
                if(shared) require(offset<d.shared_words,"direccion compartida fuera del grupo");
                const Index at=shared?(lane/d.local_size)*d.shared_words+offset:offset;
                require(at<root->count,"direccion global fuera de memoria");
                if(load) set_field_word(returned,lane,root->at(at));
                else if(atomic) {
                    const auto old=updated.at(at);
                    set_field_word(returned,lane,old);
                    set_field_word(updated,at,add_word(old,value->at(lane)));
                } else {
                    require(written.insert(at).second,"escrituras no atomicas colisionan en un paso");
                    set_field_word(updated,at,value->at(lane));
                }
            });
            if(load || atomic) { returned.validate(); d.registers[i.dst]=field_root(std::move(returned)); }
            if(!load) { updated.validate(); root=field_root(std::move(updated)); }
        }
        if(load || atomic) add_counter(shared?counters_.shared_reads:counters_.global_reads,active.size());
        if(!load) add_counter(shared?counters_.shared_writes:counters_.global_writes,active.size());
        if(atomic) add_counter(counters_.atomics,active.size());
    }
    bool dependencies_ready(const DispatchState& d) const {
        for(auto dep:d.dependencies) if(!jobs_[dep].finished()) return false;
        return true;
    }
    bool advance() {
        if(finished()) return false;
        std::size_t id=jobs_.size();
        for(std::size_t offset=0;offset<jobs_.size();++offset) {
            const auto candidate=(dispatch_cursor_+offset)%jobs_.size();
            if(!jobs_[candidate].ready.empty() && dependencies_ready(jobs_[candidate])) { id=candidate; break; }
        }
        require(id!=jobs_.size(),"ejecucion bloqueada: barrera incompleta o dependencia sin progreso");
        auto& d=jobs_[id];
        auto p=d.ready.lower_bound(d.pc_cursor);
        if(p==d.ready.end()) p=d.ready.begin();
        const auto pc=p->first; const auto active=p->second;
        d.ready.erase(p);
        const auto i=d.kernel->code[pc];
        FieldRoot result;
        bool normal=true;
        switch(i.op) {
            case StateOp::Index:result=field_root(Field(d.count,Mode({0,1}))); break;
            case StateOp::Constant:result=field_root(Field(d.count,Mode({i.value}))); break;
            case StateOp::Move:result=d.registers[i.a]; break;
            case StateOp::Add:case StateOp::Multiply:
                result=field_root(Field::combine(*d.registers[i.a],*d.registers[i.b],i.op==StateOp::Multiply)); break;
            case StateOp::Patch:
                if(active.contains(i.k)) result=field_root(d.registers[i.dst]->patched(i.k,i.value));
                break;
            case StateOp::Choose:
                result=field_root(Field::choose(i.k,*d.registers[i.a],*d.registers[i.b])); break;
            case StateOp::LocalIndex:case StateOp::GroupIndex:
                result=scalar_result(d,i.dst,active,[&](Index lane){
                    return static_cast<Word>(i.op==StateOp::LocalIndex?lane%d.local_size:lane/d.local_size);
                }); break;
            case StateOp::BitAnd:case StateOp::BitOr:case StateOp::BitXor:
            case StateOp::ShiftLeft:case StateOp::ShiftRight:case StateOp::Less:case StateOp::Equal:
                result=pointwise(d,i,active); break;
            case StateOp::Branch: {
                const auto yes=predicate_mask(*d.registers[i.a],active);
                d.add_ready(static_cast<std::size_t>(i.k),yes);
                d.add_ready(pc+1,active.subtracted(yes)); normal=false; break;
            }
            case StateOp::BranchIndexLess: {
                const auto yes=active.intersected(LaneMask::interval(d.count,0,i.k));
                d.add_ready(i.value,yes); d.add_ready(pc+1,active.subtracted(yes)); normal=false; break;
            }
            case StateOp::Jump:d.add_ready(static_cast<std::size_t>(i.k),active); normal=false; break;
            case StateOp::LoadGlobal:case StateOp::StoreGlobal:case StateOp::AtomicAddGlobal:
            case StateOp::LoadShared:case StateOp::StoreShared:case StateOp::AtomicAddShared:
                memory(d,i,active); break;
            case StateOp::Barrier:barrier(d,pc,active); normal=false; break;
            case StateOp::Output:
                assign_field(d.output,d.registers[i.dst],active); d.output_valid=d.output_valid.joined(active); break;
            case StateOp::Halt:d.halted=d.halted.joined(active); normal=false; break;
        }
        if(result) assign_field(d.registers[i.dst],std::move(result),active);
        if(normal) d.add_ready(pc+1,active);
        d.pc_cursor=(pc+1)%d.kernel->code.size();
        dispatch_cursor_=(id+1)%jobs_.size();
        add_counter(revision_,1); add_counter(counters_.steps,1);
        return true;
    }
public:
    explicit Execution(Index global_words,ExecutionLimits limits={})
        : global_(field_root(Field(global_words))),limits_(limits) { limits_.validate(); }
    std::uint64_t revision() const { return revision_; }
    std::size_t scheduler_cursor() const { return dispatch_cursor_; }
    const ExecutionLimits& limits() const { return limits_; }
    const ExecutionCounters& counters() const { return counters_; }
    const Field& global() const { return *global_; }
    const std::vector<DispatchState>& dispatches() const { return jobs_; }
    const DispatchState& dispatch(std::size_t id) const {
        require(id<jobs_.size(),"despacho fuera de la cola"); return jobs_[id];
    }
    void initialize_global(Field field) {
        require(jobs_.empty() && field.count==global_->count,"inicializacion global fuera de fase");
        field.validate(); auto next=field_root(std::move(field));
        require(revision_!=UINT64_MAX,"revision agotada"); global_=std::move(next); ++revision_;
    }
    std::size_t enqueue(Kernel kernel,Index n,unsigned local=64,unsigned shared_words=0,
                        std::vector<std::size_t> dependencies={}) {
        require(jobs_.size()<limits_.dispatches,"presupuesto de despachos C11");
        require(std::is_sorted(dependencies.begin(),dependencies.end()) &&
                std::adjacent_find(dependencies.begin(),dependencies.end())==dependencies.end(),
                "dependencias repetidas o no canonicas");
        for(auto dep:dependencies) require(dep<jobs_.size(),"dependencia futura o inexistente");
        DispatchState job(std::move(kernel),n,local,shared_words,std::move(dependencies));
        require(revision_!=UINT64_MAX,"revision agotada");
        jobs_.push_back(std::move(job)); ++revision_; return jobs_.size()-1;
    }
    bool finished() const {
        return std::all_of(jobs_.begin(),jobs_.end(),[](const DispatchState& d){return d.finished();});
    }
    bool step() {
        // Copy-on-write of immutable fields; failure cannot partially publish memory/control.
        auto candidate=*this;
        if(!candidate.advance()) return false;
        candidate.validate(); *this=std::move(candidate); return true;
    }
    bool run(std::uint64_t step_budget=1000000) {
        while(step_budget-- && !finished()) step();
        return finished();
    }
    void validate() const {
        limits_.validate(); global_->validate();
        require(jobs_.size()<=limits_.dispatches && counters_.steps<=revision_,"autoridad C11 invalida");
        require(jobs_.empty()?dispatch_cursor_==0:dispatch_cursor_<jobs_.size(),"cursor de despachos invalido");
        for(std::size_t id=0;id<jobs_.size();++id) {
            const auto& d=jobs_[id]; d.kernel->validate(d.count);
            require(d.local_size && d.local_size<=1024 && d.shared_words<=65536,"grupo invalido");
            require(d.shared_words==0 || d.groups()<=domain_limit/d.shared_words,"dominio compartido invalido");
            require(d.shared->count==std::max<Index>(1,d.groups()*d.shared_words),"memoria compartida incompatible");
            require(d.pc_cursor<d.kernel->code.size(),"cursor de programa invalido");
            require(d.dependencies.size()<=id && std::is_sorted(d.dependencies.begin(),d.dependencies.end()) &&
                    std::adjacent_find(d.dependencies.begin(),d.dependencies.end())==d.dependencies.end(),"dependencias invalidas");
            for(auto dep:d.dependencies) require(dep<id,"dependencia ciclica o futura");
            for(const auto& root:d.registers) { require(root && root->count==d.count,"registro incompatible"); root->validate(); }
            require(d.output && d.output->count==d.count,"salida incompatible"); d.output->validate(); d.shared->validate();
            require(d.halted.count==d.count && d.output_valid.count==d.count,"mascara de salida incompatible");
            d.halted.validate(); d.output_valid.validate();
            auto occupied=d.halted;
            const auto include=[&](const LaneMask& mask) {
                require(mask.count==d.count && !mask.empty(),"mascara de control vacia o incompatible"); mask.validate();
                require(occupied.intersected(mask).empty(),"ejecucion tiene varios estados de control");
                occupied=occupied.joined(mask);
            };
            for(const auto& ready:d.ready) {
                require(ready.first<d.kernel->code.size(),"PC fuera del programa"); include(ready.second);
            }
            require(d.waiting.size()<=limits_.barrier_groups && d.barrier_epochs.size()<=limits_.barrier_groups,
                    "grupos fuera del presupuesto");
            for(const auto& epoch:d.barrier_epochs)
                require(epoch.first<d.groups() && epoch.second && epoch.second<=counters_.steps,"epoca invalida");
            for(const auto& wait:d.waiting) {
                const auto& w=wait.second;
                require(wait.first<d.groups() && w.pc<d.kernel->code.size() &&
                        d.kernel->code[w.pc].op==StateOp::Barrier,"espera de barrera invalida");
                const auto group=d.group_mask(wait.first);
                require(w.lanes.subtracted(group).empty() && w.lanes.size()<group.size(),"espera fuera del grupo o no liberada");
                const auto epoch=d.barrier_epochs.count(wait.first)?d.barrier_epochs.at(wait.first):0;
                require(w.epoch==epoch,"generacion de barrera incompatible"); include(w.lanes);
            }
            require(occupied.full(),"faltan ejecuciones en el estado de control");
        }
    }
    std::string snapshot() const;
    static Execution restore(const std::string& bytes);
};
} // namespace xgpu
