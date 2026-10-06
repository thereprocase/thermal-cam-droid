#include "p2pro_protocol.hpp"
#include <limits>

namespace p2pro {
namespace {
void be32(Header& out, std::size_t offset, std::uint32_t value) {
    for (std::size_t i = 0; i < 4; ++i) {
        out[offset + i] = static_cast<std::uint8_t>(value >> (24 - 8 * i));
    }
}
} // namespace

Header standard_header(std::uint16_t code, std::uint32_t parameter, std::uint16_t length) {
    Header out{};
    out[0] = static_cast<std::uint8_t>(code);
    out[1] = static_cast<std::uint8_t>(code >> 8);
    for (std::size_t i = 0; i < 4; ++i) {
        out[2 + i] = static_cast<std::uint8_t>(parameter >> (8 * i));
    }
    out[6] = static_cast<std::uint8_t>(length >> 8);
    out[7] = static_cast<std::uint8_t>(length);
    return out;
}

Header long_header(std::uint16_t code, std::uint16_t parameter, std::uint32_t value) {
    Header out{};
    out[0] = static_cast<std::uint8_t>(code);
    out[1] = static_cast<std::uint8_t>(code >> 8);
    out[2] = static_cast<std::uint8_t>(parameter >> 8);
    out[3] = static_cast<std::uint8_t>(parameter);
    be32(out, 4, value);
    return out;
}

Header long_parameters(std::uint32_t first, std::uint32_t second) {
    Header out{};
    be32(out, 0, first);
    be32(out, 4, second);
    return out;
}

std::vector<std::uint16_t> decode_plane(const std::uint8_t* data, std::size_t length,
                                      std::size_t stride) {
    if (!data || stride < 512 || stride % 2 ||
        stride > std::numeric_limits<std::size_t>::max() / 384 || length < stride * 384) {
        throw std::invalid_argument("Invalid composite frame or stride");
    }
    std::vector<std::uint16_t> out(256 * 192);
    for (std::size_t y = 0; y < 192; ++y) {
        for (std::size_t x = 0; x < 256; ++x) {
            const auto offset = (y + 192) * stride + x * 2;
            out[y * 256 + x] = static_cast<std::uint16_t>(data[offset]) |
                              static_cast<std::uint16_t>(data[offset + 1] << 8);
        }
    }
    return out;
}

double celsius(std::uint16_t raw) { return static_cast<double>(raw) / 64.0 - 273.15; }

MailboxState mailbox_state(std::uint8_t status) {
    if (status & 0xfc) return MailboxState::Error;
    return status & 3 ? MailboxState::Busy : MailboxState::Ready;
}
} // namespace p2pro
