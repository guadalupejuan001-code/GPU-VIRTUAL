#pragma once
#include "control_gpu.hpp"
#include "float32_gpu.hpp"
#include <cstring>
#include <functional>
#include <optional>
#include <unordered_map>
#include <unordered_set>

namespace xgpu {
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
enum class ValueKind : unsigned {
    Polynomial=0,Local=1,Group=2,Add=3,Multiply=4,And=5,Or=6,Xor=7,Shl=8,Shr=9,
    Less=10,Equal=11,FloatFromUnsigned=12,FloatAdd=13,FloatMultiply=14,FloatLess=15,
    Select=16,Gather=17,ScatterIdentity=18,ScatterXor=19,Patch=20,Rank=21
};
struct WordBounds { Word low=0,high=UINT32_MAX; };
class Value;
class ValueReader;
using FieldRoot=std::shared_ptr<const Value>;
constexpr std::size_t max_value_nodes=65536,max_value_depth=128,max_value_words=1048576;
inline Word value_operation(ValueKind kind,Word a,Word b) {
    switch(kind) {
        case ValueKind::Add:return add_word(a,b);
        case ValueKind::Multiply:return mul_word(a,b);
        case ValueKind::And:return a&b;
        case ValueKind::Or:return a|b;
        case ValueKind::Xor:return a^b;
        case ValueKind::Shl:require(b<32,"desplazamiento fuera de uint32");return a<<b;
        case ValueKind::Shr:require(b<32,"desplazamiento fuera de uint32");return a>>b;
        case ValueKind::Less:return a<b;
        case ValueKind::Equal:return a==b;
        case ValueKind::FloatFromUnsigned:return binary32::from_unsigned(a);
        case ValueKind::FloatAdd:return binary32::add(a,b);
        case ValueKind::FloatMultiply:return binary32::multiply(a,b);
        case ValueKind::FloatLess:return binary32::less(a,b);
        default:throw std::runtime_error("operacion de relacion invalida");
    }
}
class Value {
    ValueKind kind_;
    std::shared_ptr<const Field> field_;
    FieldRoot left_,right_;
    Index immediate_;
    LaneMask mask_;
    std::map<Index,Word> patches_;
    WordBounds bounds_;
    Index period_=0;
    std::size_t depth_=1;
    std::uint64_t hash_=14695981039346656037ULL,checks_=0;
    std::vector<Index> prefix_;
    static std::uint64_t hash_word(std::uint64_t hash,Index value) {
        for(unsigned j=0;j<8;++j) {hash^=(value>>(j*8))&255;hash*=1099511628211ULL;} return hash;
    }
    static Index common_period(Index a,Index b) {
        if(!a || !b) return 0;
        auto x=a,y=b; while(y) {auto r=x%y;x=y;y=r;}
        return a/x<=65536/b?(a/x)*b:0;
    }
    void derive() {
        require(count && count<=domain_limit,"dominio de relacion invalido");
        mask_.validate();
        const auto k=kind_;
        if(k==ValueKind::Polynomial) {
            require(field_ && field_->count==count && !left_ && !right_ && !immediate_ && patches_.empty(),"campo de relacion invalido");
            require(mask_.full() && mask_.count==count,"mascara de campo invalida");
            field_->validate();
            std::uint64_t lo=UINT32_MAX,hi=0;
            for(const auto& band:field_->bands) {
                const auto& c=band.mode.coefficients;
                if(c.size()<=2) {
                    const auto slope=c.size()==2?c[1]:0;
                    const auto first=std::uint64_t{slope}*band.begin+c[0];
                    const auto last=std::uint64_t{slope}*(band.end-1)+c[0];
                    if(last<=UINT32_MAX) {lo=std::min(lo,first);hi=std::max(hi,last);}
                    else {lo=0;hi=UINT32_MAX;}
                } else {lo=0;hi=UINT32_MAX;}
            }
            for(const auto& d:field_->details) {lo=std::min<std::uint64_t>(lo,d.second);hi=std::max<std::uint64_t>(hi,d.second);}
            bounds_={static_cast<Word>(lo),static_cast<Word>(hi)};
            if(constant()) period_=1;
        } else if(k==ValueKind::Rank) {
            require(!field_ && !left_ && !right_ && !immediate_ && mask_.count==count && patches_.empty(),"rango de mascara invalido");
            Index total=0;for(const auto& r:mask_.ranges) {prefix_.push_back(total);total+=r.end-r.begin;}
            bounds_={0,static_cast<Word>(std::min<Index>(total,UINT32_MAX))};
        } else if(k==ValueKind::Local || k==ValueKind::Group) {
            require(!field_ && !left_ && !right_ && immediate_ && immediate_<=1024 && mask_.full() && mask_.count==count && patches_.empty(),"indice relacional invalido");
            bounds_={0,static_cast<Word>(k==ValueKind::Local?std::min(count-1,immediate_-1):(count-1)/immediate_)};
            if(k==ValueKind::Local) period_=immediate_;
        } else {
            require(!field_ && left_,"relacion sin operando");
            depth_=left_->depth()+1;
            if(right_) depth_=std::max(depth_,right_->depth()+1);
            require(depth_<=max_value_depth,"presupuesto de profundidad de relaciones");
            const bool unary=k==ValueKind::FloatFromUnsigned || k==ValueKind::Patch;
            require(unary?!right_:static_cast<bool>(right_),"aridad de relacion invalida");
            require(static_cast<unsigned>(k)<=static_cast<unsigned>(ValueKind::Rank),"relacion desconocida");
            if(k==ValueKind::ScatterIdentity || k==ValueKind::ScatterXor) {
                require(left_->count==count && right_->count<=count && mask_.count==right_->count,"dominio de dispersion invalido");
                if(k==ValueKind::ScatterXor)
                    require((right_->count&(right_->count-1))==0 && immediate_<right_->count,"permutacion XOR invalida");
                else require(immediate_==0,"dispersion identidad invalida");
                bounds_={std::min(left_->bounds().low,right_->bounds().low),std::max(left_->bounds().high,right_->bounds().high)};
            } else if(k==ValueKind::Gather) {
                require(right_->count==count && mask_.count==count && !immediate_,"dominio de lectura relacional invalido");
                if(right_->bounds().high>=left_->count) {
                    require(mask_.size()<=65536,"direccion no demostrada requiere expansion acotada");
                    for(const auto& r:mask_.ranges) for(Index lane=r.begin;lane<r.end;++lane) {
                        require(right_->at(lane)<left_->count,"lectura relacional fuera de memoria"); ++checks_;
                    }
                }
                bounds_=mask_.full()?left_->bounds():WordBounds{0,left_->bounds().high};
                period_=mask_.full()?right_->period():0;
            } else {
                require(left_->count==count && (!right_ || right_->count==count) && mask_.count==count && !immediate_,"dominios de relacion distintos");
                const auto a=left_->bounds(),b=right_?right_->bounds():WordBounds{};
                if(k==ValueKind::Select) bounds_={std::min(a.low,b.low),std::max(a.high,b.high)};
                else if(k==ValueKind::Patch) {
                    require(mask_.full() && !patches_.empty() && patches_.size()<=max_details,"parches de relacion invalidos");
                    bounds_=a;
                    for(const auto& p:patches_) {
                        require(p.first<count && p.second!=left_->at(p.first),"parche redundante o fuera del dominio");
                        bounds_.low=std::min(bounds_.low,p.second); bounds_.high=std::max(bounds_.high,p.second);
                    }
                } else {
                    if(k!=ValueKind::Shl && k!=ValueKind::Shr) require(mask_.full(),"mascara inesperada en relacion");
                    if(k==ValueKind::Shl || k==ValueKind::Shr) {
                        if(b.high>=32) {
                            require(mask_.size()<=65536,"desplazamiento no demostrado requiere expansion acotada");
                            for(const auto& r:mask_.ranges) for(Index lane=r.begin;lane<r.end;++lane) {
                                require(right_->at(lane)<32,"desplazamiento fuera de uint32"); ++checks_;
                            }
                        }
                    }
                    if(k==ValueKind::And) bounds_={0,std::min(a.high,b.high)};
                    else if(k==ValueKind::Or || k==ValueKind::Xor) {
                        Word high=std::max(a.high,b.high);high|=high>>1;high|=high>>2;high|=high>>4;high|=high>>8;high|=high>>16;
                        bounds_={0,high};
                    } else if(k==ValueKind::Less || k==ValueKind::Equal || k==ValueKind::FloatLess) bounds_={0,1};
                    else if(k==ValueKind::Add && std::uint64_t{a.high}+b.high<=UINT32_MAX)
                        bounds_={add_word(a.low,b.low),add_word(a.high,b.high)};
                    else if(k==ValueKind::Multiply && std::uint64_t{a.high}*b.high<=UINT32_MAX)
                        bounds_={mul_word(a.low,b.low),mul_word(a.high,b.high)};
                    else if(k==ValueKind::Shr && b.high<32) bounds_={0,a.high>>b.low};
                    else if(k==ValueKind::Shl && b.high<32 && (std::uint64_t{a.high}<<b.high)<=UINT32_MAX)
                        bounds_={0,static_cast<Word>(a.high<<b.high)};
                    period_=mask_.full()?(unary?left_->period():common_period(left_->period(),right_->period())):0;
                    if(k==ValueKind::And) {
                        const auto patterned=[&](const FieldRoot& index,const FieldRoot& c) {
                            if(!index->identity() || !c->constant()) return Index{0};
                            const Index period=Index{c->at(0)}+1;
                            return period<=65536 && (period&(period-1))==0?period:0;
                        };
                        if(auto p=patterned(left_,right_)) period_=p;
                        else if(auto p=patterned(right_,left_)) period_=p;
                    }
                }
            }
            if(k!=ValueKind::Patch) require(patches_.empty(),"parches inesperados");
        }
        if(bounds_.low==bounds_.high) period_=1;
        hash_=hash_word(hash_,static_cast<unsigned>(kind_)); hash_=hash_word(hash_,count);
        hash_=hash_word(hash_,immediate_);
        if(field_) {
            for(const auto& b:field_->bands) {
                hash_=hash_word(hash_,b.begin);hash_=hash_word(hash_,b.end);hash_=hash_word(hash_,b.mode.coefficients.size());
                for(auto c:b.mode.coefficients) hash_=hash_word(hash_,c);
            }
            for(const auto& p:field_->details) {hash_=hash_word(hash_,p.first);hash_=hash_word(hash_,p.second);}
        } else {
            hash_=hash_word(hash_,reinterpret_cast<std::uintptr_t>(left_.get()));
            hash_=hash_word(hash_,reinterpret_cast<std::uintptr_t>(right_.get()));
        }
        hash_=hash_word(hash_,mask_.count);
        for(const auto& r:mask_.ranges) {hash_=hash_word(hash_,r.begin);hash_=hash_word(hash_,r.end);}
        for(const auto& p:patches_) {hash_=hash_word(hash_,p.first);hash_=hash_word(hash_,p.second);}
    }
    Value(ValueKind kind,Index n,std::shared_ptr<const Field> field,FieldRoot left,FieldRoot right,
          Index immediate,LaneMask mask,std::map<Index,Word> patches)
        :kind_(kind),field_(std::move(field)),left_(std::move(left)),right_(std::move(right)),
         immediate_(immediate),mask_(std::move(mask)),patches_(std::move(patches)),count(n) {derive();}
public:
    const Index count;
    static FieldRoot polynomial(Field f) {
        const auto n=f.count;
        return FieldRoot(new Value(ValueKind::Polynomial,n,std::make_shared<const Field>(std::move(f)),{},{},0,LaneMask(n,true),{}));
    }
    static FieldRoot node(ValueKind kind,Index n,FieldRoot left,FieldRoot right,Index immediate,LaneMask mask,
                         std::map<Index,Word> patches={}) {
        return FieldRoot(new Value(kind,n,{},std::move(left),std::move(right),immediate,std::move(mask),std::move(patches)));
    }
    ValueKind kind() const {return kind_;}
    const FieldRoot& left() const {return left_;}
    const FieldRoot& right() const {return right_;}
    Index immediate() const {return immediate_;}
    const LaneMask& mask() const {return mask_;}
    const std::map<Index,Word>& patches() const {return patches_;}
    const Field& field() const {require(static_cast<bool>(field_),"relacion no es campo C01");return *field_;}
    bool is_field() const {return static_cast<bool>(field_);}
    bool constant() const {return bounds_.low==bounds_.high;}
    bool constant_on(const LaneMask& active,Word& word) const {
        require(active.count==count && !active.empty(),"dominio de constante activa invalido");
        if(constant()) {word=bounds_.low;return true;}
        if(field_) {
            word=field_->at(active.ranges.front().begin);
            std::size_t band_index=0;
            for(const auto& range:active.ranges) {
                while(field_->bands[band_index].end<=range.begin) ++band_index;
                auto current=band_index;
                while(current<field_->bands.size() && field_->bands[current].begin<range.end) {
                    const auto& band=field_->bands[current];
                    const auto begin=std::max(range.begin,band.begin),end=std::min(range.end,band.end);
                    if(end-begin==1) {if(field_->at(begin)!=word) return false;}
                    else if(band.mode.coefficients.size()!=1 || band.mode.coefficients[0]!=word) return false;
                    ++current;
                }
            }
            for(const auto& detail:field_->details) if(active.contains(detail.first) && detail.second!=word) return false;
            return true;
        }
        if(kind_==ValueKind::Select) {
            const auto selected=active.intersected(mask_),other=active.subtracted(mask_);
            if(other.empty()) return right_->constant_on(selected,word);
            if(selected.empty()) return left_->constant_on(other,word);
            Word a=0,b=0;
            if(left_->constant_on(other,a) && right_->constant_on(selected,b) && a==b) {word=a;return true;}
        }
        if(kind_==ValueKind::Gather && active.subtracted(mask_).empty() && left_->constant()) {
            word=left_->bounds().low;return true;
        }
        return false;
    }
    bool identity() const {return field_ && field_->bands.size()==1 && field_->bands[0].mode.coefficients==std::vector<Word>({0,1}) && field_->details.empty();}
    const WordBounds& bounds() const {return bounds_;}
    Index period() const {return period_;}
    std::size_t depth() const {return depth_;}
    std::uint64_t hash() const {return hash_;}
    std::uint64_t scalar_checks() const {return checks_;}
    void validate() const {require(count && depth_<=max_value_depth,"relacion invalida");}
    bool same_node(const Value& b) const {
        if(kind_!=b.kind_ || count!=b.count || immediate_!=b.immediate_ || left_!=b.left_ || right_!=b.right_ ||
           patches_!=b.patches_ || mask_.count!=b.mask_.count || mask_.ranges.size()!=b.mask_.ranges.size()) return false;
        for(std::size_t j=0;j<mask_.ranges.size();++j)
            if(mask_.ranges[j].begin!=b.mask_.ranges[j].begin || mask_.ranges[j].end!=b.mask_.ranges[j].end) return false;
        if(field_) {
            if(field_->bands.size()!=b.field_->bands.size() || field_->details!=b.field_->details) return false;
            for(std::size_t j=0;j<field_->bands.size();++j) {
                const auto& a=field_->bands[j];const auto& bb=b.field_->bands[j];
                if(a.begin!=bb.begin || a.end!=bb.end || !(a.mode==bb.mode)) return false;
            }
        }
        return true;
    }
    template<class Read> Word evaluate(Index index,Read read) const {
        require(index<count,"consulta relacional fuera del dominio");
        if(field_) return field_->at(index);
        if(kind_==ValueKind::Local) return static_cast<Word>(index%immediate_);
        if(kind_==ValueKind::Group) return static_cast<Word>(index/immediate_);
        if(kind_==ValueKind::Rank) {
            auto p=std::upper_bound(mask_.ranges.begin(),mask_.ranges.end(),index,
                [](Index at,const LaneRange& r){return at<r.begin;});
            if(p==mask_.ranges.begin()) return 0;
            const auto j=static_cast<std::size_t>((--p)-mask_.ranges.begin());
            return static_cast<Word>(prefix_[j]+std::min(index,p->end)-p->begin);
        }
        if(kind_==ValueKind::Select) return read(mask_.contains(index)?right_:left_,index);
        if(kind_==ValueKind::Patch) {auto p=patches_.find(index);return p==patches_.end()?read(left_,index):p->second;}
        if(kind_==ValueKind::ScatterIdentity || kind_==ValueKind::ScatterXor) {
            if(index<right_->count) {const auto lane=index^immediate_;if(mask_.contains(lane)) return read(right_,lane);}
            return read(left_,index);
        }
        if(kind_==ValueKind::Gather) return mask_.contains(index)?read(left_,read(right_,index)):0;
        if((kind_==ValueKind::Shl || kind_==ValueKind::Shr) && !mask_.contains(index)) return 0;
        return value_operation(kind_,read(left_,index),right_?read(right_,index):0);
    }
    Word at(Index index) const;
};
inline FieldRoot field_root(Field field) {return Value::polynomial(std::move(field));}
struct ValueInventory {
    std::unordered_set<const Value*> seen;
    std::size_t words=0,ranges=0;
    void include(const FieldRoot& root) {
        if(!root || !seen.insert(root.get()).second) return;
        require(seen.size()<=max_value_nodes,"presupuesto de nodos relacionales"); root->validate();
        ranges+=root->mask().ranges.size();
        words+=root->patches().size()*2;
        if(root->is_field()) {
            words+=root->field().details.size()*2;
            for(const auto& band:root->field().bands) words+=band.mode.coefficients.size()+4;
        }
        require(words<=max_value_words && ranges<=max_value_words,"presupuesto de datos relacionales");
        include(root->left());include(root->right());
    }
};
class ValueArena {
    std::unordered_map<std::uint64_t,std::vector<FieldRoot>> buckets_;
    std::unordered_set<const Value*> seen_;
    std::uint64_t& created_;std::uint64_t& reused_;std::uint64_t& checked_;
    static void increment(std::uint64_t& value,std::uint64_t amount=1) {
        require(amount<=UINT64_MAX-value,"contador relacional agotado");value+=amount;
    }
public:
    ValueArena(std::uint64_t& created,std::uint64_t& reused,std::uint64_t& checked)
        :created_(created),reused_(reused),checked_(checked) {}
    void seed(const FieldRoot& root) {
        if(!root || !seen_.insert(root.get()).second) return;
        seed(root->left());seed(root->right());buckets_[root->hash()].push_back(root);
    }
    FieldRoot keep(FieldRoot root) {increment(reused_);return root;}
    FieldRoot intern(FieldRoot candidate) {
        increment(checked_,candidate->scalar_checks());
        auto& bucket=buckets_[candidate->hash()];
        for(const auto& old:bucket) if(old->same_node(*candidate)) return keep(old);
        require(seen_.size()<max_value_nodes,"presupuesto temporal de nodos relacionales");
        increment(created_);
        seen_.insert(candidate.get());bucket.push_back(candidate);return candidate;
    }
    FieldRoot polynomial(Field field) {return intern(field_root(std::move(field)));}
    FieldRoot node(ValueKind kind,Index n,FieldRoot a,FieldRoot b={},Index immediate=0,
                   const LaneMask* mask=nullptr,std::map<Index,Word> patches={}) {
        if(kind==ValueKind::Gather && a && b && b->identity() && a->count==n && (!mask || mask->full())) return keep(a);
        return intern(Value::node(kind,n,std::move(a),std::move(b),immediate,mask?*mask:LaneMask(n,true),std::move(patches)));
    }
    FieldRoot binary(ValueKind kind,FieldRoot a,FieldRoot b,const LaneMask& active) {
        Word av=0,bv=0;
        if(a->constant_on(active,av) && (!b || b->constant_on(active,bv)))
            return polynomial(Field(a->count,Mode({value_operation(kind,av,bv)})));
        if(kind==ValueKind::Add) {
            const auto identity_parts=[](const FieldRoot& product,const FieldRoot& local) {
                if(product->kind()!=ValueKind::Multiply || local->kind()!=ValueKind::Local) return false;
                const auto group=product->left()->kind()==ValueKind::Group?product->left():product->right();
                const auto factor=product->left()->kind()==ValueKind::Group?product->right():product->left();
                return group->kind()==ValueKind::Group && factor->constant() && group->immediate()==local->immediate() && factor->at(0)==group->immediate();
            };
            if(identity_parts(a,b) || identity_parts(b,a)) return polynomial(Field(a->count,Mode({0,1})));
        }
        if(kind==ValueKind::Add || kind==ValueKind::Multiply) {
            if(a->is_field() && b->is_field()) {
                require(a->count==b->count,"dominios de relacion distintos");
                try {return polynomial(Field::combine(a->field(),b->field(),kind==ValueKind::Multiply));}
                catch(const std::runtime_error&) { /* Keep the exact expression when C01 budgets cannot encode it. */ }
            }
        }
        if(a==b && (kind==ValueKind::And || kind==ValueKind::Or)) return keep(a);
        if(a==b && (kind==ValueKind::Xor || kind==ValueKind::Less || kind==ValueKind::Equal))
            return polynomial(Field(a->count,Mode({kind==ValueKind::Equal?1u:0u})));
        const auto n=a->count;
        return node(kind,n,std::move(a),std::move(b),0,(kind==ValueKind::Shl || kind==ValueKind::Shr)?&active:nullptr);
    }
    FieldRoot select(FieldRoot old,FieldRoot next,const LaneMask& mask) {
        if(mask.empty()) return old;
        if(mask.full()) return next;
        if(old==next) return keep(next);
        if(old->constant() && next->constant() && old->bounds().low==next->bounds().low) return keep(old);
        if(old->is_field() && next->is_field()) {
            try {return polynomial(overlay_field(old->field(),next->field(),mask));}
            catch(const std::runtime_error&) { /* Preserve an exact selection when polynomial regions do not fit. */ }
        }
        const auto n=old->count;return node(ValueKind::Select,n,std::move(old),std::move(next),0,&mask);
    }
    FieldRoot patch(FieldRoot old,std::map<Index,Word> values) {
        for(auto p=values.begin();p!=values.end();) {
            if(old->at(p->first)==p->second) p=values.erase(p);else ++p;
        }
        if(values.empty()) return keep(old);
        if(old->is_field()) {
            Field field=old->field();
            for(const auto& p:values) {
                if(p.second==field.base_at(p.first)) field.details.erase(p.first);
                else field.details[p.first]=p.second;
            }
            if(field.details.size()<=max_details) {field.validate();return polynomial(std::move(field));}
        }
        const auto n=old->count;return node(ValueKind::Patch,n,std::move(old),{},0,nullptr,std::move(values));
    }
};
class ValueReader {
    struct Entry {const Value* node;Index index;Word value;};
    struct PlanStep {const Value* node;std::size_t left=0,right=0;};
    FieldRoot owner_;const Value* root_;const Field* direct_=nullptr;std::vector<Entry> memo_;
    std::vector<PlanStep> plan_;std::vector<Word> values_;
    Word read(const Value* root,Index index) {
        for(const auto& e:memo_) if(e.node==root && e.index==index) return e.value;
        const auto word=root->evaluate(index,[&](const FieldRoot& p,Index at){return read(p.get(),at);});
        require(memo_.size()<max_value_nodes,"presupuesto de consulta relacional");
        memo_.push_back({root,index,word});return word;
    }
    void prepare(bool compile) {
        if(root_->is_field()) {direct_=&root_->field();return;}
        if(compile) {
            std::unordered_map<const Value*,std::size_t> ids;
            std::function<bool(const Value*)> visit=[&](const Value* node) {
                if(ids.count(node)) return true;
                if(node->kind()==ValueKind::Gather || node->kind()==ValueKind::ScatterIdentity || node->kind()==ValueKind::ScatterXor) return false;
                if(node->left() && !visit(node->left().get())) return false;
                if(node->right() && !visit(node->right().get())) return false;
                require(plan_.size()<max_value_nodes,"presupuesto de plan de consulta");
                const auto id=plan_.size();
                plan_.push_back({node,node->left()?ids.at(node->left().get()):0,node->right()?ids.at(node->right().get()):0});
                ids.emplace(node,id);return true;
            };
            if(visit(root_)) {values_.resize(plan_.size());return;}
            plan_.clear();
        }
        memo_.reserve(64);
    }
    Index pattern_words(Index begin, Index count) const {
        require(begin <= root_->count && count <= root_->count - begin,
                "salida fuera del dominio de la relacion");
        return root_->period() ? std::min(root_->period(), count) : count;
    }
    static std::uint64_t triangular(Index n) {
        return n < 2 ? 0 : (n % 2 ? n * ((n - 1) / 2) : (n / 2) * (n - 1));
    }
    // Sum floor((slope * i + start) / modulus) using the Euclidean recursion.
    // In the uint32 domain n, slope, start and modulus keep slope * n + start
    // below 2^64 after the remainders. Every accumulated term is nonnegative.
    static std::uint64_t floor_sum(Index n, Index modulus, Index slope, Index start) {
        std::uint64_t total = 0;
        for (;;) {
            total += triangular(n) * (slope / modulus) + n * (start / modulus);
            slope %= modulus;
            start %= modulus;
            const auto next = slope * n + start;
            if (next < modulus) return total;
            n = next / modulus;
            start = next % modulus;
            std::swap(modulus, slope);
        }
    }
    static std::optional<std::uint64_t> affine_sum(const Field& field, Index begin, Index count) {
        constexpr Index modulus = Index{1} << 32;
        const auto end = begin + count;
        std::uint64_t total = 0;
        for (const auto& band : field.bands) {
            const auto first = std::max(begin, band.begin);
            const auto last = std::min(end, band.end);
            if (first >= last) continue;
            const auto& coefficients = band.mode.coefficients;
            if (coefficients.size() > 2) return std::nullopt;
            const auto slope = coefficients.size() == 2 ? coefficients[1] : 0;
            const auto n = last - first;
            const auto start = band.mode.at(first);
            // Unsigned operations are modulo 2^64. Their combined residue is
            // the exact sum, whose bound is 2^32 * (2^32 - 1) < 2^64.
            total += n * start + triangular(n) * slope - modulus * floor_sum(n, modulus, slope, start);
        }
        for (auto it = field.details.lower_bound(begin); it != field.details.end() && it->first < end; ++it) {
            total -= field.base_at(it->first);
            total += it->second;
        }
        return total;
    }
public:
    struct UnsignedSum { std::uint64_t value; Index evaluated_words; };

    explicit ValueReader(FieldRoot root):owner_(std::move(root)),root_(owner_.get()) {prepare(true);}
    explicit ValueReader(const Value& root):root_(&root) {prepare(false);}
    Word at(Index index) {
        if(direct_) return direct_->at(index);
        if(!plan_.empty()) {
            for(std::size_t j=0;j<plan_.size();++j) {
                const auto& step=plan_[j];
                values_[j]=step.node->evaluate(index,[&](const FieldRoot& p,Index at) {
                    require(at==index,"plan puntual recibio otro indice");
                    return values_[p==step.node->left()?step.left:step.right];
                });
            }
            return values_.back();
        }
        memo_.clear();return read(root_,index);
    }

    // Serialize a contiguous range. The returned count is the number of
    // words evaluated before copying the remainder of a proven period.
    std::size_t copy_little_endian(Index begin, std::size_t count, char* destination) {
        constexpr std::size_t word_bytes = 4;
        require(count <= std::numeric_limits<std::size_t>::max() / word_bytes &&
                    (count == 0 || destination),
                "buffer de salida relacional invalido");

        const auto evaluated = static_cast<std::size_t>(pattern_words(begin, count));
        for (std::size_t i = 0; i < evaluated; ++i) {
            const auto value = at(begin + i);
            for (unsigned byte = 0; byte < word_bytes; ++byte)
                destination[i * word_bytes + byte] = static_cast<char>((value >> (8U * byte)) & 0xffU);
        }

        const auto bytes = count * word_bytes;
        for (std::size_t written = evaluated * word_bytes; written < bytes;) {
            const auto copy = std::min(written, bytes - written);
            std::memcpy(destination + written, destination, copy);
            written += copy;
        }
        return evaluated;
    }

    UnsignedSum sum_unsigned(Index begin, Index count) {
        const auto evaluated = pattern_words(begin, count);
        if (!evaluated) return {0, 0};
        if (direct_ && !root_->period()) {
            if (const auto sum = affine_sum(*direct_, begin, count)) return {*sum, 0};
        }
        const auto tail = count % evaluated;
        std::uint64_t cycle_sum = 0;
        std::uint64_t tail_sum = 0;
        for (Index i = 0; i < evaluated; ++i) {
            const auto value = at(begin + i);
            cycle_sum += value;
            if (i < tail) tail_sum += value;
        }
        // At most 2^32 unsigned 32-bit words fit their exact sum in 64 bits.
        return {cycle_sum * (count / evaluated) + tail_sum, evaluated};
    }
};
inline Word Value::at(Index index) const {
    if(field_) return field_->at(index);
    ValueReader reader(*this);return reader.at(index);
}
} // namespace xgpu
