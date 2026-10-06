#pragma once
// Independent wire codec for the future Android C++17 backend. Transport owns
// USB access; this module neither opens devices nor assumes permission models.
#include <array>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace p2pro {
using Header = std::array<std::uint8_t, 8>;
enum class Property : std::uint16_t {
    Distance = 0, ReflectedKelvin = 1, AtmosphericKelvin = 2,
    Emissivity = 3, Transmission = 4, HighGain = 5
};
Header standard_header(std::uint16_t code, std::uint32_t parameter, std::uint16_t length);
Header long_header(std::uint16_t code, std::uint16_t parameter, std::uint32_t value);
Header long_parameters(std::uint32_t first, std::uint32_t second);
std::vector<std::uint16_t> decode_plane(const std::uint8_t* data, std::size_t length,
                                      std::size_t stride = 512);
double celsius(std::uint16_t raw);
enum class MailboxState { Ready, Busy, Error };
MailboxState mailbox_state(std::uint8_t status);
} // namespace p2pro
