#pragma once

// Implementacion escrita para este experimento. C++17, biblioteca estandar.
// C01 modela un subconjunto de ejecucion paralela uint32, sobre CPU anfitriona.
#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace xgpu {
using Word = std::uint32_t;
using Index = std::uint64_t;
constexpr Index domain_limit = Index{1} << 32;
constexpr std::size_t max_degree = 32;
constexpr std::size_t max_bands = 256;
constexpr std::size_t max_details = 65536;
constexpr std::size_t max_coefficients = 4096;
constexpr std::size_t register_count = 8;

inline void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}
inline Word add_word(Word a, Word b) {
    return static_cast<Word>(std::uint64_t{a} + b);
}
inline Word mul_word(Word a, Word b) {
    return static_cast<Word>(std::uint64_t{a} * b);
}

struct Mode {
    std::vector<Word> coefficients{0};
    explicit Mode(std::vector<Word> values = {0}) : coefficients(std::move(values)) {
        if (coefficients.empty()) coefficients.push_back(0);
        while (coefficients.size() > 1 && coefficients.back() == 0) coefficients.pop_back();
    }
    Word at(Index index) const {
        Word result = 0;
        for (auto it = coefficients.rbegin(); it != coefficients.rend(); ++it)
            result = add_word(mul_word(result, static_cast<Word>(index)), *it);
        return result;
    }
    bool operator==(const Mode& other) const { return coefficients == other.coefficients; }
    static Mode add(const Mode& a, const Mode& b) {
        std::vector<Word> c(std::max(a.coefficients.size(), b.coefficients.size()), 0);
        for (std::size_t i = 0; i < a.coefficients.size(); ++i) c[i] = a.coefficients[i];
        for (std::size_t i = 0; i < b.coefficients.size(); ++i) c[i] = add_word(c[i], b.coefficients[i]);
        return Mode(std::move(c));
    }
    static Mode multiply(const Mode& a, const Mode& b) {
        std::vector<Word> c(a.coefficients.size() + b.coefficients.size() - 1, 0);
        for (std::size_t i = 0; i < a.coefficients.size(); ++i)
            for (std::size_t j = 0; j < b.coefficients.size(); ++j)
                c[i+j] = add_word(c[i+j], mul_word(a.coefficients[i], b.coefficients[j]));
        return Mode(std::move(c));
    }
};

struct Band { Index begin, end; Mode mode; };

class Field {
public:
    Index count;
    std::vector<Band> bands;
    std::map<Index, Word> details;

    explicit Field(Index n, Mode mode = Mode{}) : count(n), bands{{0, n, std::move(mode)}} {
        validate();
    }
    const Mode& mode_at(Index index) const {
        require(index < count, "indice fuera del dominio");
        auto it = std::upper_bound(bands.begin(), bands.end(), index,
            [](Index value, const Band& band) { return value < band.begin; });
        require(it != bands.begin(), "dominio de bandas invalido");
        return (--it)->mode;
    }
    Word base_at(Index index) const { return mode_at(index).at(index); }
    Word at(Index index) const {
        require(index < count, "indice fuera del dominio");
        auto found = details.find(index);
        return found == details.end() ? base_at(index) : found->second;
    }
    void validate() const {
        require(count > 0 && count <= domain_limit, "dominio no admitido");
        require(!bands.empty() && bands.size() <= max_bands, "presupuesto de bandas");
        Index next = 0;
        std::size_t total = 0;
        for (std::size_t i = 0; i < bands.size(); ++i) {
            const auto& band = bands[i];
            require(band.begin == next && band.end > band.begin && band.end <= count,
                    "bandas no cubren el dominio exactamente");
            const auto& c = band.mode.coefficients;
            require(!c.empty() && c.size() <= max_degree+1, "presupuesto de grado");
            require(c.size() == 1 || c.back() != 0, "modo sin normalizar");
            require(i == 0 || !(band.mode == bands[i-1].mode), "bandas redundantes");
            next = band.end;
            total += c.size();
        }
        require(next == count && total <= max_coefficients, "presupuesto de coeficientes");
        require(details.size() <= max_details, "presupuesto de detalles");
        for (const auto& item : details) {
            require(item.first < count, "detalle fuera del dominio");
            require(item.second != base_at(item.first), "detalle redundante");
        }
    }
    Field patched(Index index, Word value) const {
        require(index < count, "detalle fuera del dominio");
        Field result = *this;
        if (value == base_at(index)) result.details.erase(index);
        else result.details[index] = value;
        result.validate();
        return result;
    }
    static void append(std::vector<Band>& to, Index begin, Index end, Mode mode) {
        if (begin == end) return;
        if (!to.empty() && to.back().end == begin && to.back().mode == mode)
            to.back().end = end;
        else to.push_back({begin, end, std::move(mode)});
    }
    static Field combine(const Field& a, const Field& b, bool product) {
        require(a.count == b.count, "dominios incompatibles");
        Field out(a.count);
        out.bands.clear();
        std::size_t ai = 0, bi = 0;
        Index begin = 0;
        while (begin < a.count) {
            const Index end = std::min(a.bands[ai].end, b.bands[bi].end);
            Mode mode = product ? Mode::multiply(a.bands[ai].mode, b.bands[bi].mode)
                                : Mode::add(a.bands[ai].mode, b.bands[bi].mode);
            require(mode.coefficients.size() <= max_degree+1, "presupuesto de grado");
            append(out.bands, begin, end, std::move(mode));
            require(out.bands.size() <= max_bands, "presupuesto de bandas");
            begin = end;
            if (a.bands[ai].end == end) ++ai;
            if (b.bands[bi].end == end) ++bi;
        }
        std::set<Index> changed;
        for (const auto& item : a.details) changed.insert(item.first);
        for (const auto& item : b.details) changed.insert(item.first);
        for (Index index : changed) {
            const Word value = product ? mul_word(a.at(index), b.at(index))
                                       : add_word(a.at(index), b.at(index));
            if (value != out.base_at(index)) out.details[index] = value;
        }
        out.validate();
        return out;
    }
    static Field choose(Index cut, const Field& left, const Field& right) {
        require(left.count == right.count && cut <= left.count, "seleccion fuera del dominio");
        Field out(left.count);
        out.bands.clear();
        for (const auto& band : left.bands)
            if (band.begin < cut) append(out.bands, band.begin, std::min(cut, band.end), band.mode);
        for (const auto& band : right.bands)
            if (band.end > cut) append(out.bands, std::max(cut, band.begin), band.end, band.mode);
        for (const auto& item : left.details)
            if (item.first < cut) out.details.insert(item);
        for (const auto& item : right.details)
            if (item.first >= cut) out.details.insert(item);
        out.validate();
        return out;
    }
};

enum class Op : unsigned { Index=0, Constant=1, Add=2, Multiply=3, Patch=4, Choose=5, Store=6, Halt=7 };
struct Instruction {
    Op op;
    unsigned dst=0, a=0, b=0;
    Index k=0;
    Word value=0;
};
struct Program {
    Index count;
    std::vector<Instruction> code;
    void validate() const {
        require(count > 0 && count <= domain_limit, "dominio no admitido");
        require(!code.empty() && code.size() <= 100000, "programa vacio o demasiado grande");
        require(code.back().op == Op::Halt, "falta fin");
        for (std::size_t p=0; p<code.size(); ++p) {
            const auto& i = code[p];
            require(static_cast<unsigned>(i.op) <= static_cast<unsigned>(Op::Halt), "operacion desconocida");
            require(i.dst < register_count && i.a < register_count && i.b < register_count,
                    "registro fuera de rango");
            require(i.op != Op::Halt || p+1 == code.size(), "fin antes del final");
            if (i.op == Op::Patch) require(i.k < count, "detalle fuera del dominio");
            if (i.op == Op::Choose) require(i.k <= count, "corte fuera del dominio");
        }
    }
};

class Machine {
    Program program_;
    std::array<std::shared_ptr<const Field>, register_count> registers_;
    std::shared_ptr<const Field> output_;
    std::size_t pc_ = 0;
    std::uint64_t revision_ = 0;
public:
    explicit Machine(Program program) : program_(std::move(program)) {
        program_.validate();
        auto zero = std::make_shared<const Field>(program_.count);
        registers_.fill(zero);
    }
    Index count() const { return program_.count; }
    std::size_t pc() const { return pc_; }
    std::uint64_t revision() const { return revision_; }
    bool finished() const { return pc_ == program_.code.size(); }
    const Field& reg(unsigned r) const { require(r < register_count, "registro fuera de rango"); return *registers_[r]; }
    const Field& output() const { require(static_cast<bool>(output_), "sin salida"); return *output_; }
    bool has_output() const { return static_cast<bool>(output_); }
    bool step() {
        if (finished()) return false;
        const auto i = program_.code[pc_];
        // Preparar el nuevo valor antes de modificar la autoridad de estado.
        std::shared_ptr<const Field> candidate;
        switch (i.op) {
            case Op::Index: candidate = std::make_shared<const Field>(count(), Mode({0,1})); break;
            case Op::Constant: candidate = std::make_shared<const Field>(count(), Mode({i.value})); break;
            case Op::Add: candidate = std::make_shared<const Field>(Field::combine(reg(i.a), reg(i.b), false)); break;
            case Op::Multiply: candidate = std::make_shared<const Field>(Field::combine(reg(i.a), reg(i.b), true)); break;
            case Op::Patch: candidate = std::make_shared<const Field>(reg(i.dst).patched(i.k, i.value)); break;
            case Op::Choose: candidate = std::make_shared<const Field>(Field::choose(i.k, reg(i.a), reg(i.b))); break;
            case Op::Store: output_ = registers_[i.dst]; break;
            case Op::Halt: break;
        }
        if (candidate) registers_[i.dst] = std::move(candidate);
        ++pc_;
        ++revision_;
        return true;
    }
    void run() { while (step()) {} }
    std::vector<Word> materialize(Index begin, Index length) const {
        require(begin <= count() && length <= count()-begin, "lectura fuera del dominio");
        require(length <= 16777216, "presupuesto de materializacion");
        std::vector<Word> result(static_cast<std::size_t>(length));
        for (Index i=0; i<length; ++i) result[static_cast<std::size_t>(i)] = output().at(begin+i);
        return result;
    }
    std::string snapshot() const {
        std::vector<std::shared_ptr<const Field>> fields;
        const auto identify = [&fields](const std::shared_ptr<const Field>& ptr) {
            if (!ptr) return std::size_t{99};
            auto it = std::find(fields.begin(), fields.end(), ptr);
            if (it == fields.end()) { fields.push_back(ptr); return fields.size()-1; }
            return static_cast<std::size_t>(it-fields.begin());
        };
        std::array<std::size_t, register_count> ids{};
        for (unsigned r=0; r<register_count; ++r) ids[r] = identify(registers_[r]);
        const auto output_id = identify(output_);
        std::ostringstream s;
        s << "XGPU-C01 1\n" << count() << ' ' << pc_ << ' ' << revision_ << '\n' << fields.size() << '\n';
        for (const auto& field : fields) {
            s << field->bands.size() << ' ' << field->details.size() << '\n';
            for (const auto& band : field->bands) {
                s << band.begin << ' ' << band.end << ' ' << band.mode.coefficients.size();
                for (Word word : band.mode.coefficients) s << ' ' << word;
                s << '\n';
            }
            for (const auto& item : field->details) s << item.first << ' ' << item.second << '\n';
        }
        for (auto id : ids) s << id << ' ';
        s << output_id << '\n' << program_.code.size() << '\n';
        for (const auto& i : program_.code)
            s << static_cast<unsigned>(i.op) << ' ' << i.dst << ' ' << i.a << ' ' << i.b << ' ' << i.k << ' ' << i.value << '\n';
        s << "END\n";
        return s.str();
    }
    static Machine restore(const std::string& text) {
        require(text.size() <= 64*1024*1024, "snapshot demasiado grande");
        std::istringstream s(text);
        std::string magic, token;
        unsigned version=0;
        require(static_cast<bool>(s >> magic >> version) && magic=="XGPU-C01" && version==1,
                "cabecera de snapshot invalida");
        const auto integer = [&s](Index maximum) {
            std::string value;
            require(static_cast<bool>(s >> value) && !value.empty() && value.size() <= 20,
                    "numero de snapshot invalido");
            Index result=0;
            for (char c : value) {
                require(c >= '0' && c <= '9', "numero de snapshot invalido");
                const Index digit=static_cast<Index>(c-'0');
                require(digit <= maximum && result <= (maximum-digit)/10, "numero fuera de rango");
                result=result*10+digit;
            }
            return result;
        };
        const Index n=integer(domain_limit), pc=integer(100000), revision=integer(100000);
        require(n > 0 && pc == revision, "estado de control invalido");
        const auto field_count=integer(register_count+1);
        require(field_count > 0, "snapshot sin campos");
        std::vector<std::shared_ptr<const Field>> fields;
        for (Index f=0; f<field_count; ++f) {
            Field field(n);
            field.bands.clear();
            const auto nb=integer(max_bands), nd=integer(max_details);
            for (Index b=0; b<nb; ++b) {
                const Index begin=integer(n), end=integer(n), nc=integer(max_degree+1);
                require(nc > 0, "modo vacio");
                std::vector<Word> c;
                for (Index j=0; j<nc; ++j) c.push_back(static_cast<Word>(integer(UINT32_MAX)));
                require(c.size() == 1 || c.back() != 0, "modo sin normalizar");
                field.bands.push_back({begin,end,Mode(std::move(c))});
            }
            // Validar la cobertura antes de consultar modos para los detalles.
            field.validate();
            for (Index j=0; j<nd; ++j) {
                const Index at=integer(n-1);
                const Word value=static_cast<Word>(integer(UINT32_MAX));
                require(field.details.emplace(at,value).second, "detalle duplicado");
            }
            field.validate();
            fields.push_back(std::make_shared<const Field>(std::move(field)));
        }
        std::array<std::size_t, register_count> ids{};
        for (auto& id : ids) id=static_cast<std::size_t>(integer(field_count-1));
        const Index output_id=integer(99);
        require(output_id==99 || output_id<field_count, "salida de snapshot invalida");
        Program program{n,{}};
        const auto ni=integer(100000);
        for (Index j=0; j<ni; ++j) {
            Instruction i{};
            i.op=static_cast<Op>(integer(7));
            i.dst=static_cast<unsigned>(integer(register_count-1));
            i.a=static_cast<unsigned>(integer(register_count-1));
            i.b=static_cast<unsigned>(integer(register_count-1));
            i.k=integer(domain_limit);
            i.value=static_cast<Word>(integer(UINT32_MAX));
            program.code.push_back(i);
        }
        require(static_cast<bool>(s >> token) && token=="END", "snapshot incompleto");
        s >> std::ws;
        require(s.eof() && pc <= program.code.size(), "cola o posicion de snapshot invalida");
        Machine result(std::move(program));
        result.pc_=static_cast<std::size_t>(pc);
        result.revision_=revision;
        for (unsigned r=0; r<register_count; ++r) result.registers_[r]=fields[ids[r]];
        if (output_id != 99) result.output_=fields[static_cast<std::size_t>(output_id)];
        return result;
    }
};

inline Program example(Index count) {
    Program p{count,{
        {Op::Index,0}, {Op::Constant,1,0,0,0,3}, {Op::Multiply,2,0,1},
        {Op::Constant,3,0,0,0,7}, {Op::Add,4,2,3},
        {Op::Patch,4,0,0,count-1,UINT32_MAX}, {Op::Constant,5,0,0,0,5},
        {Op::Add,6,4,5}, {Op::Multiply,7,6,6},
        {Op::Choose,7,6,7,count/2}, {Op::Store,7}, {Op::Halt}
    }};
    p.validate();
    return p;
}
} // namespace xgpu
