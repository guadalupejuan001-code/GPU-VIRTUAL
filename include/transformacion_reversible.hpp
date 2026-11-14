#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <utility>
#include <vector>

namespace xgpu {

// Compile XI's X and controlled-X gates on computational-basis inputs.
// Every output bit is a GF(2) dot product with an optional constant flip.
// The host evaluates the composed circuit using packed integer operations.
class ReversibleBitTransform {
 public:
    using Word = std::uint64_t;
    using Bits = std::vector<Word>;
    static constexpr std::size_t word_bits = 64;
    enum class Operation { X, Cnot };
    struct Gate { Operation operation; std::size_t target; std::size_t control = 0; };
    struct OperationCounts { std::size_t packed_terms = 0, parity_rows = 0; };

    ReversibleBitTransform(std::size_t bits, const std::vector<Gate>& gates)
        : bits_(bits), packed_rows_(bits / word_bits + (bits % word_bits != 0)),
          flips_(packed_rows_.size()) {
        if (bits == 0) throw std::invalid_argument("El circuito requiere al menos un bit");
        std::vector<std::vector<Term>> rows(bits);
        for (const auto& gate : gates) {
            if (gate.operation != Operation::X && gate.operation != Operation::Cnot)
                throw std::invalid_argument("Puerta reversible no admitida");
            if (gate.target >= bits || (gate.operation == Operation::Cnot &&
                                         (gate.control >= bits || gate.control == gate.target)))
                throw std::invalid_argument("Operandos fuera del circuito o iguales en CNOT");
        }
        for (std::size_t i = 0; i < bits; ++i)
            rows[i].push_back({i / word_bits, bit_mask(i)});
        for (const auto& gate : gates) {
            if (gate.operation == Operation::X)
                flips_[gate.target / word_bits] ^= bit_mask(gate.target);
            else
                compose_cnot(rows, gate.control, gate.target);
        }
        pack(rows);
    }

    Bits apply(const Bits& input) const {
        Bits output;
        apply(input, output);
        return output;
    }

    void apply(const Bits& input, Bits& output) const {
        const auto remainder = bits_ % word_bits;
        if (input.size() != flips_.size() || (remainder && (input.back() >> remainder)))
            throw std::invalid_argument("La entrada no coincide con el ancho del circuito");

        if (&input == &output) {
            output = apply(input);
            return;
        }
        output = flips_;
        for (std::size_t i = 0; i < packed_rows_.size(); ++i) {
            for (const auto& term : packed_rows_[i]) output[i] ^= input[term.word] & term.mask;
        }
        for (const auto& row : bit_rows_) {
            Word combined = 0;
            for (const auto& term : row.terms) combined ^= input[term.word] & term.mask;
            output[row.bit / word_bits] ^= parity(combined) << (row.bit % word_bits);
        }
    }

    OperationCounts operation_counts() const noexcept {
        OperationCounts counts{0, bit_rows_.size()};
        for (const auto& row : packed_rows_) counts.packed_terms += row.size();
        return counts;
    }

 private:
    struct Term {
        std::size_t word;
        Word mask;
    };
    struct BitRow { std::size_t bit; std::vector<Term> terms; };

    void compose_cnot(std::vector<std::vector<Term>>& rows, std::size_t control, std::size_t target) {
        const auto& source = rows[control];
        const auto& destination = rows[target];
        std::vector<Term> result;
        result.reserve(std::min(flips_.size(), source.size() + destination.size()));
        auto left = source.begin();
        auto right = destination.begin();
        while (left != source.end() && right != destination.end()) {
            if (left->word < right->word) {
                result.push_back(*left++);
            } else if (right->word < left->word) {
                result.push_back(*right++);
            } else {
                const auto mask = left->mask ^ right->mask;
                if (mask) result.push_back({left->word, mask});
                ++left;
                ++right;
            }
        }
        result.insert(result.end(), left, source.end());
        result.insert(result.end(), right, destination.end());
        rows[target] = std::move(result);

        const auto control_bit = (flips_[control / word_bits] >> (control % word_bits)) & 1U;
        flips_[target / word_bits] ^= control_bit << (target % word_bits);
    }

    void pack(std::vector<std::vector<Term>>& rows) {
        // When each address contributes its bit at the same offset, apply the
        // map one whole output word at a time. Other rows still use exact parity.
        Bits masks(flips_.size());
        std::vector<std::size_t> touched;
        for (std::size_t word = 0; word < packed_rows_.size(); ++word) {
            const auto first = word * word_bits;
            const auto count = std::min(word_bits, bits_ - first);
            for (auto bit = first; bit < first + count; ++bit) {
                auto& terms = rows[bit];
                const auto mask = bit_mask(bit);
                if (std::all_of(terms.begin(), terms.end(),
                                [mask](const Term& term) { return term.mask == mask; })) {
                    for (const auto& term : terms) {
                        auto& source_mask = masks[term.word];
                        if (!source_mask) touched.push_back(term.word);
                        source_mask |= mask;
                    }
                } else {
                    bit_rows_.push_back({bit, std::move(terms)});
                }
            }
            packed_rows_[word].reserve(touched.size());
            for (const auto source : touched) {
                packed_rows_[word].push_back({source, masks[source]});
                masks[source] = 0;
            }
            touched.clear();
        }
    }

    static Word bit_mask(std::size_t bit) noexcept { return Word{1} << (bit % word_bits); }

    static Word parity(Word value) noexcept {
#if defined(__GNUC__) || defined(__clang__)
        return static_cast<Word>(__builtin_parityll(value));
#else
        value ^= value >> 32;
        value ^= value >> 16;
        value ^= value >> 8;
        value ^= value >> 4;
        return (Word{0x6996} >> (value & 15)) & 1;
#endif
    }

    std::size_t bits_;
    std::vector<std::vector<Term>> packed_rows_;
    std::vector<BitRow> bit_rows_;
    Bits flips_;
};

} // namespace xgpu
