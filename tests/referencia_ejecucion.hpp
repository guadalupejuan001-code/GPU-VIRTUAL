#pragma once
#include "control_gpu.hpp"

// Oraculo escalar: arreglos por invocacion, PCs individuales y memoria explicita.
// No usa Field, Mode, LaneMask, Execution ni su serializador para evolucionar.
namespace execution_reference {
struct Dispatch {
    xgpu::Kernel kernel;
    std::size_t n,local,words,cursor=0;
    std::vector<std::size_t> deps,pc;
    std::vector<bool> waiting,valid;
    std::array<std::vector<std::uint32_t>,8> regs;
    std::vector<std::uint32_t> shared,output;
    std::vector<std::uint64_t> epochs;
    Dispatch(xgpu::Kernel k,std::size_t count,unsigned group,unsigned shared_words,std::vector<std::size_t> dependencies)
        : kernel(std::move(k)),n(count),local(group),words(shared_words),deps(std::move(dependencies)),
          pc(n),waiting(n,false),valid(n,false),shared(std::max<std::size_t>(1,((n+local-1)/local)*words)),
          output(n),epochs((n+local-1)/local) {
        for(auto& r:regs) r.resize(n);
    }
    bool done() const {
        return std::all_of(pc.begin(),pc.end(),[&](std::size_t p){return p==kernel.code.size();});
    }
    std::set<std::size_t> runnable() const {
        std::set<std::size_t> result;
        for(std::size_t lane=0;lane<n;++lane)
            if(!waiting[lane] && pc[lane]!=kernel.code.size()) result.insert(pc[lane]);
        return result;
    }
};
class Dense {
public:
    std::vector<std::uint32_t> memory;
    std::vector<Dispatch> jobs;
    std::size_t cursor=0;
    std::uint64_t revision=0,steps=0,global_reads=0,global_writes=0,shared_reads=0,shared_writes=0;
    std::uint64_t atomics=0,releases=0;
    explicit Dense(std::size_t words):memory(words) {}
    void enqueue(xgpu::Kernel kernel,std::size_t n,unsigned local=64,unsigned shared=0,std::vector<std::size_t> deps={}) {
        jobs.emplace_back(std::move(kernel),n,local,shared,std::move(deps)); ++revision;
    }
    bool finished() const { return std::all_of(jobs.begin(),jobs.end(),[](const Dispatch& d){return d.done();}); }
    bool step() {
        if(finished()) return false;
        std::size_t id=jobs.size();
        for(std::size_t k=0;k<jobs.size();++k) {
            const auto candidate=(cursor+k)%jobs.size(); const auto& d=jobs[candidate];
            const bool dependencies=std::all_of(d.deps.begin(),d.deps.end(),[&](std::size_t dep){return jobs[dep].done();});
            if(dependencies && !d.runnable().empty()) { id=candidate; break; }
        }
        if(id==jobs.size()) throw std::runtime_error("oraculo bloqueado");
        auto& d=jobs[id]; const auto ready=d.runnable();
        auto choose=ready.lower_bound(d.cursor); if(choose==ready.end()) choose=ready.begin();
        const auto pc=*choose; const auto ins=d.kernel.code[pc];
        std::vector<std::size_t> lanes;
        for(std::size_t lane=0;lane<d.n;++lane) if(d.pc[lane]==pc && !d.waiting[lane]) lanes.push_back(lane);
        std::set<std::size_t> written;
        for(auto lane:lanes) {
            using O=xgpu::StateOp;
            const auto a=d.regs[ins.a][lane],b=d.regs[ins.b][lane];
            auto& dst=d.regs[ins.dst][lane];
            std::size_t next=pc+1;
            switch(ins.op) {
                case O::Index:dst=static_cast<std::uint32_t>(lane); break;
                case O::Constant:dst=ins.value; break;
                case O::Move:dst=a; break;
                case O::Add:dst=static_cast<std::uint32_t>(std::uint64_t(a)+b); break;
                case O::Multiply:dst=static_cast<std::uint32_t>(std::uint64_t(a)*b); break;
                case O::Patch:if(lane==ins.k) dst=ins.value; break;
                case O::Choose:dst=lane<ins.k?a:b; break;
                case O::LocalIndex:dst=static_cast<std::uint32_t>(lane%d.local); break;
                case O::GroupIndex:dst=static_cast<std::uint32_t>(lane/d.local); break;
                case O::BitAnd:dst=a&b; break;
                case O::BitOr:dst=a|b; break;
                case O::BitXor:dst=a^b; break;
                case O::ShiftLeft:if(b>=32) throw std::runtime_error("shift escalar invalido"); dst=a<<b; break;
                case O::ShiftRight:if(b>=32) throw std::runtime_error("shift escalar invalido"); dst=a>>b; break;
                case O::Less:dst=a<b; break;
                case O::Equal:dst=a==b; break;
                case O::Branch:if(a) next=static_cast<std::size_t>(ins.k); break;
                case O::BranchIndexLess:if(lane<ins.k) next=ins.value; break;
                case O::Jump:next=static_cast<std::size_t>(ins.k); break;
                case O::Output:d.output[lane]=dst; d.valid[lane]=true; break;
                case O::Halt:next=d.kernel.code.size(); break;
                case O::Barrier:d.waiting[lane]=true; next=pc; break;
                case O::LoadGlobal:case O::StoreGlobal:case O::AtomicAddGlobal:
                case O::LoadShared:case O::StoreShared:case O::AtomicAddShared: {
                    const bool shared=ins.op==O::LoadShared || ins.op==O::StoreShared || ins.op==O::AtomicAddShared;
                    const bool load=ins.op==O::LoadShared || ins.op==O::LoadGlobal;
                    const bool atomic=ins.op==O::AtomicAddGlobal || ins.op==O::AtomicAddShared;
                    if(shared && a>=d.words) throw std::runtime_error("memoria escalar fuera del grupo");
                    const auto at=shared?(lane/d.local)*d.words+a:a;
                    auto& buffer=shared?d.shared:memory;
                    if(at>=buffer.size()) throw std::runtime_error("memoria escalar fuera del dominio");
                    if(load) dst=buffer[at];
                    else if(atomic) { const auto old=buffer[at]; buffer[at]=static_cast<std::uint32_t>(std::uint64_t(old)+b); dst=old; }
                    else {
                        if(!written.insert(at).second) throw std::runtime_error("colision escalar");
                        buffer[at]=b;
                    }
                    if(load || atomic) (shared?shared_reads:global_reads)++;
                    if(!load) (shared?shared_writes:global_writes)++;
                    if(atomic) ++atomics;
                    break;
                }
            }
            d.pc[lane]=next;
        }
        if(ins.op==xgpu::StateOp::Barrier) {
            for(std::size_t begin=0;begin<d.n;begin+=d.local) {
                const auto end=std::min(d.n,begin+d.local);
                std::size_t arrived=0,site=d.kernel.code.size();
                for(auto lane=begin;lane<end;++lane) if(d.waiting[lane]) {
                    if(site!=d.kernel.code.size() && site!=d.pc[lane]) throw std::runtime_error("barreras escalares incompatibles");
                    site=d.pc[lane]; ++arrived;
                }
                if(arrived==end-begin) {
                    for(auto lane=begin;lane<end;++lane) { d.waiting[lane]=false; d.pc[lane]=site+1; }
                    ++d.epochs[begin/d.local]; ++releases;
                }
            }
        }
        d.cursor=(pc+1)%d.kernel.code.size(); cursor=(id+1)%jobs.size(); ++revision; ++steps;
        return true;
    }
    void run(std::size_t budget=10000) {
        while(!finished() && budget--) step();
        if(!finished()) throw std::runtime_error("oraculo excedio pasos");
    }
};
} // namespace execution_reference
