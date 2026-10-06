#include "p2pro_camera.hpp"
#include <algorithm>
#include <thread>

namespace p2pro {
namespace {
std::uint16_t be16(const std::vector<std::uint8_t>& bytes) {
    return static_cast<std::uint16_t>(bytes.at(0) << 8) | bytes.at(1);
}
} // namespace

Camera::Camera(Transport& transport, std::chrono::milliseconds ready_timeout)
    : transport_(transport), ready_timeout_(ready_timeout) {
    if (ready_timeout.count() < 0) throw std::invalid_argument("Negative ready timeout");
}

void Camera::send(std::uint16_t mailbox, const Header& header) {
    transport_.write(mailbox, {header.begin(), header.end()});
}

std::vector<std::uint8_t> Camera::receive(std::uint16_t mailbox, std::size_t length) {
    auto result = transport_.read(mailbox, length);
    if (result.size() != length) throw std::runtime_error("Short control response");
    return result;
}

void Camera::ready() {
    auto deadline = std::chrono::steady_clock::now() + ready_timeout_;
    while (true) {
        const auto state = mailbox_state(receive(0x200, 1)[0]);
        if (state == MailboxState::Error) throw std::runtime_error("Camera error status");
        if (state == MailboxState::Ready) return;
        if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Camera ready timeout");
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

std::vector<std::uint8_t> Camera::standard_read(std::uint16_t code, std::uint32_t parameter, std::uint16_t length) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    ready(); send(0x1d00, standard_header(code, parameter, length)); ready();
    return receive(0x1d08, length);
}

void Camera::standard_write(std::uint16_t code, std::uint32_t parameter) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    ready(); send(0x1d00, standard_header(code, parameter, 0)); ready();
}

std::uint16_t Camera::property(Property property) {
    const auto index = static_cast<std::uint16_t>(property);
    if (index > 5) throw std::invalid_argument("Unknown property");
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    ready(); send(0x9d00, long_header(0x8514, index, 0));
    send(0x1d08, long_parameters(0, 2)); ready();
    return be16(receive(0x1d10, 2));
}

void Camera::set_property(Property property, std::uint16_t value) {
    const auto index = static_cast<std::uint16_t>(property);
    // These bounds are client limits from prior art, not universal firmware
    // guarantees. Readback records observed clamps on the tested unit.
    constexpr std::uint16_t maxima[] = {32767, 1024, 1024, 128, 128, 1};
    if (index > 5 || value > maxima[index]) throw std::invalid_argument("Property value outside client domain");
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    ready(); send(0x9d00, long_header(0xc514, index, value));
    send(0x1d08, long_parameters(0, 0)); ready();
}

std::vector<std::uint8_t> Camera::device_info(unsigned item) {
    constexpr std::uint16_t lengths[] = {8,8,8,26,4,50,48,16,4};
    if (item > 8) throw std::invalid_argument("Unknown device-info item");
    return standard_read(0x8405, item, lengths[item]);
}

std::uint8_t Camera::palette() { return standard_read(0x8409, 0, 1)[0]; }

void Camera::set_palette(std::uint8_t palette) {
    if (palette < 1 || palette > 11 || palette == 2) throw std::invalid_argument("Unknown or reserved palette");
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    ready(); send(0x9d00, standard_header(0xc409, 0, 1)); ready();
    transport_.write(0x1d08, {palette}); ready();
}

std::array<std::uint8_t, 2> Camera::shutter_state() {
    const auto result = standard_read(0x830c, 0, 2);
    return {result[0], result[1]};
}
void Camera::set_shutter_control_enabled(bool enabled) { standard_write(0x410c, enabled); }
void Camera::set_shutter_closed(bool closed) { standard_write(0x420c, closed); }
void Camera::nuc() {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    set_shutter_control_enabled(true); standard_write(0xc10d, 0);
}
std::uint16_t Camera::shutter_reference() { return be16(standard_read(0x840c, 0, 2)); }
std::uint16_t Camera::current_reference() { return be16(standard_read(0x8b0d, 0, 2)); }
void Camera::preview(bool enabled, bool y16) {
    standard_write(y16 ? (enabled ? 0x010a : 0x020a) : (enabled ? 0xc10f : 0x020f), 0);
}

std::vector<std::uint8_t> Camera::spi_read(std::uint32_t address, std::size_t length) {
    if (!length || length > 512 || static_cast<std::uint64_t>(address) + length > 0x100000000ULL) {
        throw std::invalid_argument("SPI read exceeds bounded research domain");
    }
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    std::vector<std::uint8_t> output;
    for (std::size_t offset=0; offset<length; offset+=256) {
        const auto count = static_cast<std::uint16_t>(std::min<std::size_t>(256,length-offset));
        const auto pointer = static_cast<std::uint32_t>(address+offset);
        Header packet{1,0x82,static_cast<std::uint8_t>(pointer >> 24),static_cast<std::uint8_t>(pointer >> 16),
                      static_cast<std::uint8_t>(pointer >> 8),static_cast<std::uint8_t>(pointer),
                      static_cast<std::uint8_t>(count >> 8),static_cast<std::uint8_t>(count)};
        ready(); send(0x1d00, packet); ready();
        const auto data = receive(0x1d08,count); output.insert(output.end(),data.begin(),data.end());
    }
    return output;
}
} // namespace p2pro
