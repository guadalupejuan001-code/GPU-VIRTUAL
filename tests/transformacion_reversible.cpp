#include "transformacion_reversible.hpp"

#include <iostream>
#include <random>

using Transform = xgpu::ReversibleBitTransform;
using Gate = Transform::Gate;
using Op = Transform::Operation;

namespace {

unsigned checks = 0;
void need(bool value) {
    if (!value) throw std::runtime_error("Transformación reversible distinta de la referencia");
    ++checks;
}

bool bit(const Transform::Bits& input, std::size_t index) {
    return (input[index / Transform::word_bits] >> (index % Transform::word_bits)) & 1U;
}

void flip(Transform::Bits& value, std::size_t index) {
    value[index / Transform::word_bits] ^= std::uint64_t{1} << (index % Transform::word_bits);
}

} // namespace

int main() {
    try {
        std::mt19937_64 rng(0x58475550524556ULL);
        for (const std::size_t width : {1, 2, 63, 64, 65, 129, 1024, 4097}) {
            std::vector<Gate> gates;
            for (std::size_t i = 0; i < 3 * width; ++i) {
                const auto target = rng() % width;
                const auto control = rng() % width;
                if (target == control || rng() % 3 == 0) gates.push_back({Op::X, target});
                else gates.push_back({Op::Cnot, target, control});
            }
            const Transform compiled(width, gates);
            const Transform inverse(width, std::vector<Gate>(gates.rbegin(), gates.rend()));
            for (unsigned sample = 0; sample < 128; ++sample) {
                Transform::Bits input((width + 63) / 64);
                for (auto& word : input) word = rng();
                if (width % 64) input.back() >>= 64 - width % 64;
                auto expected = input;
                for (const auto& gate : gates)
                    if (gate.operation == Op::X || bit(expected, gate.control)) flip(expected, gate.target);
                const auto result = compiled.apply(input);
                need(result == expected);
                need(inverse.apply(result) == input);
                compiled.apply(input, input);
                need(input == result);
            }
        }

        std::vector<Gate> word_xor;
        for (std::size_t i = 0; i < 512; ++i) word_xor.push_back({Op::Cnot, 512 + i, i});
        const Transform packed(1024, word_xor);
        const auto counts = packed.operation_counts();
        need(counts.packed_terms == 24 && counts.parity_rows == 0);
        for (auto gates : {std::vector<Gate>{{Op::Cnot, 0, 0}},
                           std::vector<Gate>{{Op::X, 65}},
                           std::vector<Gate>{{static_cast<Op>(99), 0}}}) {
            bool rejected = false;
            try { (void)Transform(65, gates); } catch (const std::invalid_argument&) { rejected = true; }
            need(rejected);
        }
        bool rejected = false;
        try { (void)Transform(65, {}).apply({0, 2}); } catch (const std::invalid_argument&) { rejected = true; }
        need(rejected);
        std::cout << "ReversibleBitTransform: " << checks << " comprobaciones\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
