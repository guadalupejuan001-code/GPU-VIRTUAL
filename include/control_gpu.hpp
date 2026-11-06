#pragma once
#include "estado_gpu.hpp"

// C11: estado logico de ejecucion propio. No depende de un driver GPU.
namespace xgpu {
struct LaneRange { Index begin=0, end=0; };

class LaneMask {
public:
    Index count;
    std::vector<LaneRange> ranges;
    explicit LaneMask(Index n, bool full=false) : count(n) {
        require(n && n<=domain_limit, "dominio de mascara invalido");
        if(full) ranges.push_back({0,n});
    }
    static LaneMask interval(Index n, Index begin, Index end) {
        require(begin<=end && end<=n, "intervalo fuera del dominio");
        LaneMask result(n);
        if(begin!=end) result.ranges.push_back({begin,end});
        return result;
    }
    bool empty() const { return ranges.empty(); }
    bool full() const { return ranges.size()==1 && ranges[0].begin==0 && ranges[0].end==count; }
    Index size() const {
        Index total=0;
        for(const auto& r:ranges) total+=r.end-r.begin;
        return total;
    }
    bool contains(Index lane) const {
        require(lane<count, "consulta de mascara fuera del dominio");
        auto p=std::upper_bound(ranges.begin(),ranges.end(),lane,
            [](Index i,const LaneRange& r){return i<r.begin;});
        return p!=ranges.begin() && lane<(--p)->end;
    }
    void validate() const {
        require(count && count<=domain_limit && ranges.size()<=65536, "presupuesto de mascara");
        Index last=0; bool first=true;
        for(const auto& r:ranges) {
            require(r.begin<r.end && r.end<=count && (first || last<r.begin), "mascara no canonica");
            last=r.end; first=false;
        }
    }
    void append(Index begin,Index end) {
        if(begin==end) return;
        if(!ranges.empty() && ranges.back().end==begin) ranges.back().end=end;
        else ranges.push_back({begin,end});
    }
    LaneMask joined(const LaneMask& b) const {
        require(count==b.count,"dominios de mascara distintos");
        LaneMask out(count);
        auto all=ranges;
        all.insert(all.end(),b.ranges.begin(),b.ranges.end());
        std::sort(all.begin(),all.end(),[](const LaneRange& l,const LaneRange& r){return l.begin<r.begin;});
        for(const auto& r:all) {
            if(!out.ranges.empty() && r.begin<=out.ranges.back().end)
                out.ranges.back().end=std::max(out.ranges.back().end,r.end);
            else out.ranges.push_back(r);
        }
        out.validate(); return out;
    }
    LaneMask intersected(const LaneMask& b) const {
        require(count==b.count,"dominios de mascara distintos");
        LaneMask out(count); std::size_t i=0,j=0;
        while(i<ranges.size() && j<b.ranges.size()) {
            const auto lo=std::max(ranges[i].begin,b.ranges[j].begin);
            const auto hi=std::min(ranges[i].end,b.ranges[j].end);
            if(lo<hi) out.append(lo,hi);
            if(ranges[i].end<b.ranges[j].end) ++i; else ++j;
        }
        out.validate(); return out;
    }
    LaneMask subtracted(const LaneMask& b) const {
        require(count==b.count,"dominios de mascara distintos");
        LaneMask out(count); std::size_t j=0;
        for(const auto& r:ranges) {
            Index begin=r.begin;
            while(j<b.ranges.size() && b.ranges[j].end<=begin) ++j;
            auto k=j;
            while(k<b.ranges.size() && b.ranges[k].begin<r.end) {
                if(begin<b.ranges[k].begin) out.append(begin,std::min(r.end,b.ranges[k].begin));
                begin=std::max(begin,b.ranges[k].end);
                if(begin>=r.end) break;
                ++k;
            }
            if(begin<r.end) out.append(begin,r.end);
        }
        out.validate(); return out;
    }
};

// Los ocho primeros codigos conservan la semantica del perfil C01.
enum class StateOp : unsigned {
    Index=0, Constant=1, Add=2, Multiply=3, Patch=4, Choose=5, Output=6, Halt=7,
    Move=8, LocalIndex=9, GroupIndex=10, BitAnd=11, BitOr=12, BitXor=13,
    ShiftLeft=14, ShiftRight=15, Less=16, Equal=17, Branch=18,
    BranchIndexLess=19, Jump=20, LoadGlobal=21, StoreGlobal=22,
    AtomicAddGlobal=23, LoadShared=24, StoreShared=25, AtomicAddShared=26,
    Barrier=27
};
struct StateInstruction {
    StateOp op;
    unsigned dst=0,a=0,b=0;
    Index k=0;
    Word value=0;
};
struct Kernel {
    std::vector<StateInstruction> code;
    void validate(Index n) const {
        require(n && n<=domain_limit && !code.empty() && code.size()<=100000,"kernel fuera de presupuesto");
        require(code.back().op==StateOp::Halt,"kernel sin fin final");
        for(const auto& i:code) {
            require(static_cast<unsigned>(i.op)<=static_cast<unsigned>(StateOp::Barrier),"operacion C11 desconocida");
            require(i.dst<register_count && i.a<register_count && i.b<register_count,"registro C11 invalido");
            require(i.k<=domain_limit,"inmediato C11 fuera del formato");
            if(i.op==StateOp::Patch) require(i.k<n,"detalle C11 fuera del dominio");
            if(i.op==StateOp::Choose || i.op==StateOp::BranchIndexLess)
                require(i.k<=n,"corte C11 fuera del dominio");
            if(i.op==StateOp::Jump || i.op==StateOp::Branch)
                require(i.k<code.size(),"salto C11 fuera del programa");
            if(i.op==StateOp::BranchIndexLess)
                require(i.value<code.size(),"salto por indice fuera del programa");
        }
    }
    static Kernel from_c01(const Program& p) {
        p.validate(); Kernel k;
        for(const auto& i:p.code)
            k.code.push_back({static_cast<StateOp>(i.op),i.dst,i.a,i.b,i.k,i.value});
        k.validate(p.count); return k;
    }
};
struct ExecutionLimits {
    Index scalar_lanes_per_step=65536;
    std::size_t barrier_groups=65536;
    std::size_t dispatches=256;
    void validate() const {
        require(scalar_lanes_per_step && scalar_lanes_per_step<=65536,
                "presupuesto escalar C11 invalido");
        require(barrier_groups && barrier_groups<=65536 && dispatches && dispatches<=256,
                "presupuesto de autoridad C11 invalido");
    }
};
} // namespace xgpu
