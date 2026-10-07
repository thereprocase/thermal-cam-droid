#pragma once
#include "p2pro_protocol.hpp"
#include <chrono>
#include <mutex>

namespace p2pro {
// Android's wrapped libusb handle and desktop transports share this contract.
// The adapter must throw on USB errors and preserve actual transfer lengths.
class Transport {
public:
    virtual ~Transport() = default;
    virtual void write(std::uint16_t mailbox, const std::vector<std::uint8_t>& bytes) = 0;
    virtual std::vector<std::uint8_t> read(std::uint16_t mailbox, std::size_t length) = 0;
};

struct BaselineReadback {
    std::array<std::uint16_t, 6> original;
    std::array<std::uint16_t, 6> configured;
};

class Camera {
public:
    explicit Camera(Transport& transport,
                    std::chrono::milliseconds ready_timeout = std::chrono::seconds(10));
    BaselineReadback establish_baseline(bool high_gain);
    std::uint16_t property(Property property);
    void set_property(Property property, std::uint16_t value);
    std::vector<std::uint8_t> device_info(unsigned item);
    std::uint8_t palette();
    void set_palette(std::uint8_t palette);
    std::array<std::uint8_t, 2> shutter_state();
    void set_shutter_control_enabled(bool enabled);
    void set_shutter_closed(bool closed);
    void nuc();
    std::uint16_t shutter_reference();
    std::uint16_t current_reference();
    void preview(bool enabled, bool y16 = false);
    std::vector<std::uint8_t> spi_read(std::uint32_t address, std::size_t length);
private:
    void ready();
    void send(std::uint16_t mailbox, const Header& header);
    std::vector<std::uint8_t> receive(std::uint16_t mailbox, std::size_t length);
    std::vector<std::uint8_t> standard_read(std::uint16_t code, std::uint32_t parameter, std::uint16_t length);
    void standard_write(std::uint16_t code, std::uint32_t parameter);
    Transport& transport_;
    std::chrono::milliseconds ready_timeout_;
    std::recursive_mutex mutex_;
};
} // namespace p2pro
