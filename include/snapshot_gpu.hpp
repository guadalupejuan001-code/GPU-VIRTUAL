#pragma once
#include "ejecucion_gpu.hpp"

namespace xgpu {
struct ExecutionCodec {
    static constexpr std::size_t maximum_bytes=64*1024*1024;
    static std::uint64_t checksum(const std::string& bytes) {
        std::uint64_t value=14695981039346656037ULL;
        for(unsigned char b:bytes) { value^=b; value*=1099511628211ULL; }
        return value;
    }
    class Reader {
        std::istringstream in_;
    public:
        explicit Reader(const std::string& text):in_(text) {}
        Index integer(Index maximum=UINT64_MAX) {
            std::string text;
            require(static_cast<bool>(in_>>text) && !text.empty() && text.size()<=20,"numero de archivo C11 invalido");
            Index n=0;
            for(char c:text) {
                require(c>='0' && c<='9',"numero de archivo C11 invalido");
                const auto digit=static_cast<Index>(c-'0');
                require(digit<=maximum && n<=(maximum-digit)/10,"numero C11 fuera de rango");
                n=n*10+digit;
            }
            return n;
        }
        void end() {
            std::string word;
            require(static_cast<bool>(in_>>word) && word=="END","archivo C11 incompleto");
            in_>>std::ws; require(in_.eof(),"cola de archivo C11 invalida");
        }
    };
    static void write_mask(std::ostream& out,const LaneMask& mask) {
        out<<mask.ranges.size();
        for(const auto& r:mask.ranges) out<<' '<<r.begin<<' '<<r.end;
        out<<'\n';
    }
    static LaneMask read_mask(Reader& in,Index n) {
        LaneMask mask(n); const auto size=in.integer(65536);
        for(Index i=0;i<size;++i) {
            const auto begin=in.integer(n),end=in.integer(n);
            mask.ranges.push_back({begin,end});
        }
        mask.validate(); return mask;
    }
    static void write_field(std::ostream& out,const Field& field) {
        out<<field.count<<' '<<field.bands.size()<<' '<<field.details.size()<<'\n';
        for(const auto& b:field.bands) {
            out<<b.begin<<' '<<b.end<<' '<<b.mode.coefficients.size();
            for(auto word:b.mode.coefficients) out<<' '<<word;
            out<<'\n';
        }
        for(const auto& detail:field.details) out<<detail.first<<' '<<detail.second<<'\n';
    }
    static FieldRoot read_field(Reader& in) {
        const auto n=in.integer(domain_limit); Field field(n); field.bands.clear();
        const auto nb=in.integer(max_bands),nd=in.integer(max_details);
        for(Index j=0;j<nb;++j) {
            const auto begin=in.integer(n),end=in.integer(n),nc=in.integer(max_degree+1);
            require(nc>0,"modo C11 vacio"); std::vector<Word> c;
            for(Index i=0;i<nc;++i) c.push_back(static_cast<Word>(in.integer(UINT32_MAX)));
            require(c.size()==1 || c.back()!=0,"modo C11 no normalizado");
            field.bands.push_back({begin,end,Mode(std::move(c))});
        }
        field.validate();
        for(Index j=0;j<nd;++j) {
            const auto at=in.integer(n-1); const auto value=static_cast<Word>(in.integer(UINT32_MAX));
            require(field.details.emplace(at,value).second,"detalle C11 repetido");
        }
        field.validate(); return field_root(std::move(field));
    }
    static std::string encode(const Execution& execution) {
        execution.validate();
        std::vector<FieldRoot> fields;
        const auto identify=[&](const FieldRoot& field)->std::size_t {
            auto p=std::find(fields.begin(),fields.end(),field);
            if(p==fields.end()) { fields.push_back(field); return fields.size()-1; }
            return static_cast<std::size_t>(p-fields.begin());
        };
        identify(execution.global_);
        for(const auto& d:execution.jobs_) {
            for(const auto& r:d.registers) identify(r);
            identify(d.shared); identify(d.output);
        }
        std::ostringstream out;
        const auto& limits=execution.limits_; const auto& c=execution.counters_;
        out<<limits.scalar_lanes_per_step<<' '<<limits.barrier_groups<<' '<<limits.dispatches<<'\n';
        out<<execution.revision_<<' '<<execution.dispatch_cursor_<<'\n';
        out<<c.steps<<' '<<c.scalar_lane_evaluations<<' '<<c.global_reads<<' '<<c.global_writes<<' '
           <<c.shared_reads<<' '<<c.shared_writes<<' '<<c.atomics<<' '<<c.barrier_releases<<'\n';
        out<<fields.size()<<'\n';
        for(const auto& field:fields) write_field(out,*field);
        out<<execution.jobs_.size()<<'\n';
        for(const auto& d:execution.jobs_) {
            out<<d.count<<' '<<d.local_size<<' '<<d.shared_words<<' '<<d.pc_cursor<<'\n';
            out<<d.dependencies.size(); for(auto dependency:d.dependencies) out<<' '<<dependency;
            out<<'\n'<<d.kernel->code.size()<<'\n';
            for(const auto& i:d.kernel->code)
                out<<static_cast<unsigned>(i.op)<<' '<<i.dst<<' '<<i.a<<' '<<i.b<<' '<<i.k<<' '<<i.value<<'\n';
            for(const auto& r:d.registers) out<<identify(r)<<' ';
            out<<identify(d.shared)<<' '<<identify(d.output)<<'\n';
            write_mask(out,d.output_valid); write_mask(out,d.halted);
            out<<d.ready.size()<<'\n';
            for(const auto& item:d.ready) { out<<item.first<<' '; write_mask(out,item.second); }
            out<<d.waiting.size()<<'\n';
            for(const auto& item:d.waiting) {
                out<<item.first<<' '<<item.second.pc<<' '<<item.second.epoch<<' ';
                write_mask(out,item.second.lanes);
            }
            out<<d.barrier_epochs.size()<<'\n';
            for(const auto& item:d.barrier_epochs) out<<item.first<<' '<<item.second<<'\n';
        }
        out<<"END\n";
        const auto payload=out.str();
        require(payload.size()<maximum_bytes-128,"archivo C11 supera 64 MiB");
        return "XGPU-C11 1\n"+std::to_string(payload.size())+" "+std::to_string(checksum(payload))+"\n"+payload;
    }
    static Execution decode(const std::string& bytes) {
        require(bytes.size()<=maximum_bytes,"archivo C11 demasiado grande");
        const std::string magic="XGPU-C11 1\n";
        require(bytes.compare(0,magic.size(),magic)==0,"cabecera C11 invalida");
        const auto line=bytes.find('\n',magic.size());
        require(line!=std::string::npos && line-magic.size()<=64,"longitud de cabecera C11 invalida");
        Reader header(bytes.substr(magic.size(),line-magic.size())+" END");
        const auto length=header.integer(maximum_bytes),hash=header.integer(); header.end();
        require(length==bytes.size()-line-1,"longitud de archivo C11 incompatible");
        const auto payload=bytes.substr(line+1);
        require(checksum(payload)==hash,"checksum C11 incompatible");
        Reader in(payload);
        ExecutionLimits limits;
        limits.scalar_lanes_per_step=in.integer(65536);
        limits.barrier_groups=static_cast<std::size_t>(in.integer(65536));
        limits.dispatches=static_cast<std::size_t>(in.integer(256)); limits.validate();
        const auto revision=in.integer(),cursor=in.integer(255);
        ExecutionCounters counters;
        counters.steps=in.integer(); counters.scalar_lane_evaluations=in.integer();
        counters.global_reads=in.integer(); counters.global_writes=in.integer();
        counters.shared_reads=in.integer(); counters.shared_writes=in.integer();
        counters.atomics=in.integer(); counters.barrier_releases=in.integer();
        const auto nf=in.integer(1+limits.dispatches*(register_count+2));
        require(nf>0,"archivo C11 sin memoria");
        std::vector<FieldRoot> fields;
        for(Index f=0;f<nf;++f) fields.push_back(read_field(in));
        Execution result(fields[0]->count,limits);
        result.global_=fields[0]; result.revision_=revision; result.counters_=counters;
        result.dispatch_cursor_=static_cast<std::size_t>(cursor);
        const auto nj=in.integer(limits.dispatches);
        for(Index j=0;j<nj;++j) {
            const auto n=in.integer(domain_limit);
            const auto local=static_cast<unsigned>(in.integer(1024));
            const auto shared=static_cast<unsigned>(in.integer(65536));
            const auto pc_cursor=in.integer(99999),nd=in.integer(j);
            std::vector<std::size_t> deps;
            for(Index k=0;k<nd;++k) deps.push_back(static_cast<std::size_t>(in.integer(j?j-1:0)));
            Kernel kernel; const auto ni=in.integer(100000);
            for(Index p=0;p<ni;++p) {
                StateInstruction i{};
                i.op=static_cast<StateOp>(in.integer(static_cast<unsigned>(StateOp::Barrier)));
                i.dst=static_cast<unsigned>(in.integer(register_count-1));
                i.a=static_cast<unsigned>(in.integer(register_count-1));
                i.b=static_cast<unsigned>(in.integer(register_count-1));
                i.k=in.integer(domain_limit); i.value=static_cast<Word>(in.integer(UINT32_MAX));
                kernel.code.push_back(i);
            }
            DispatchState d(std::move(kernel),n,local,shared,std::move(deps));
            d.pc_cursor=static_cast<std::size_t>(pc_cursor);
            for(auto& r:d.registers) r=fields[static_cast<std::size_t>(in.integer(nf-1))];
            d.shared=fields[static_cast<std::size_t>(in.integer(nf-1))];
            d.output=fields[static_cast<std::size_t>(in.integer(nf-1))];
            d.output_valid=read_mask(in,n); d.halted=read_mask(in,n); d.ready.clear();
            const auto nr=in.integer(ni);
            for(Index k=0;k<nr;++k) {
                const auto pc=static_cast<std::size_t>(in.integer(ni?ni-1:0));
                require(d.ready.emplace(pc,read_mask(in,n)).second,"PC listo repetido");
            }
            const auto nw=in.integer(limits.barrier_groups);
            for(Index k=0;k<nw;++k) {
                const auto group=in.integer(d.groups()-1);
                const auto pc=static_cast<std::size_t>(in.integer(ni-1)),epoch=in.integer();
                auto mask=read_mask(in,n);
                require(d.waiting.emplace(group,BarrierWait{pc,epoch,std::move(mask)}).second,"grupo en espera repetido");
            }
            const auto ne=in.integer(limits.barrier_groups);
            for(Index k=0;k<ne;++k) {
                const auto group=in.integer(d.groups()-1),epoch=in.integer();
                require(d.barrier_epochs.emplace(group,epoch).second,"epoca de grupo repetida");
            }
            result.jobs_.push_back(std::move(d));
        }
        in.end(); result.validate(); return result;
    }
};
inline std::string Execution::snapshot() const { return ExecutionCodec::encode(*this); }
inline Execution Execution::restore(const std::string& bytes) { return ExecutionCodec::decode(bytes); }
} // namespace xgpu
