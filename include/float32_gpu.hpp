#pragma once
#include "estado_gpu.hpp"

// Aritmetica binary32 propia sobre enteros, sin depender del FPU anfitrion.
// Redondeo nearest/ties-to-even; subnormales graduales; NaN aritmetico canonico.
namespace xgpu::binary32 {
constexpr Word nan=0x7fc00000u;
inline bool is_nan(Word a) { return (a&0x7fffffffu)>0x7f800000u; }
inline std::uint64_t jam(std::uint64_t value,unsigned shift) {
    if(!shift) return value;
    if(shift>=64) return value?1:0;
    return (value>>shift)|((value&((std::uint64_t{1}<<shift)-1))?1:0);
}
inline Word rounded(Word sign,int exponent,std::uint64_t significand) {
    if(exponent<=0) { significand=jam(significand,static_cast<unsigned>(1-exponent)); exponent=1; }
    auto mantissa=significand>>3;
    const auto tail=significand&7;
    if(tail>4 || (tail==4 && (mantissa&1))) ++mantissa;
    if(mantissa>=0x1000000u) { mantissa>>=1; ++exponent; }
    if(exponent>=255) return sign|0x7f800000u;
    if(mantissa<0x800000u) exponent=0;
    return sign|(static_cast<Word>(exponent)<<23)|(static_cast<Word>(mantissa)&0x7fffffu);
}
inline Word add(Word a,Word b) {
    if(is_nan(a) || is_nan(b)) return nan;
    auto aa=a&0x7fffffffu,bb=b&0x7fffffffu;
    if(aa==0x7f800000u || bb==0x7f800000u) {
        if(aa==bb && ((a^b)&0x80000000u)) return nan;
        return aa==0x7f800000u?a:b;
    }
    if(!aa && !bb) return (a&b)&0x80000000u;
    if(aa<bb) { std::swap(a,b); std::swap(aa,bb); }
    int ea=static_cast<int>(aa>>23),eb=static_cast<int>(bb>>23);
    const auto ma=(aa&0x7fffffu)|(ea?0x800000u:0);
    const auto mb=(bb&0x7fffffu)|(eb?0x800000u:0);
    ea=std::max(1,ea); eb=std::max(1,eb);
    auto sa=std::uint64_t{ma}<<3;
    const auto sb=jam(std::uint64_t{mb}<<3,static_cast<unsigned>(ea-eb));
    if((a^b)&0x80000000u) sa-=sb; else sa+=sb;
    if(!sa) return 0;
    if(sa>=0x8000000u) { sa=jam(sa,1); ++ea; }
    while(sa<0x4000000u && ea>1) { sa<<=1; --ea; }
    return rounded(a&0x80000000u,ea,sa);
}
inline Word multiply(Word a,Word b) {
    if(is_nan(a) || is_nan(b)) return nan;
    const Word sign=(a^b)&0x80000000u;
    const auto aa=a&0x7fffffffu,bb=b&0x7fffffffu;
    if(aa==0x7f800000u || bb==0x7f800000u)
        return (!aa || !bb)?nan:sign|0x7f800000u;
    if(!aa || !bb) return sign;
    int ea=static_cast<int>(aa>>23),eb=static_cast<int>(bb>>23);
    Word ma=(aa&0x7fffffu)|(ea?0x800000u:0),mb=(bb&0x7fffffu)|(eb?0x800000u:0);
    ea=std::max(1,ea); eb=std::max(1,eb);
    while(ma<0x800000u) { ma<<=1; --ea; }
    while(mb<0x800000u) { mb<<=1; --eb; }
    const auto product=std::uint64_t{ma}*mb;
    const bool high=product>=(std::uint64_t{1}<<47);
    return rounded(sign,ea+eb-127+(high?1:0),jam(product,high?21:20));
}
inline Word from_unsigned(Word value) {
    if(!value) return 0;
    unsigned bit=0; for(Word rest=value;rest>>=1;) ++bit;
    const auto extended=bit<=26?std::uint64_t{value}<<(26-bit):jam(value,bit-26);
    return rounded(0,static_cast<int>(bit)+127,extended);
}
inline Word less(Word a,Word b) {
    if(is_nan(a) || is_nan(b) || (!(a&0x7fffffffu) && !(b&0x7fffffffu))) return 0;
    if((a^b)&0x80000000u) return a>>31;
    return (a>>31)?a>b:a<b;
}
} // namespace xgpu::binary32
