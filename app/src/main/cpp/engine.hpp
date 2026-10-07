#pragma once
#include <android/native_window.h>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <libusb.h>
#include <libuvc/libuvc.h>
#include "p2pro_camera.hpp"
#include "radiometry.hpp"
#include "measurements.hpp"

namespace thermal {
constexpr std::size_t PixelCount = 256 * 192;
constexpr std::size_t CompositeBytes = 256 * 384 * 2;

struct DisplaySettings {
    int palette = 0;
    bool flip = false;
    int rotation = 0;
    bool mirror = false;
    bool automatic = true;
    float lower = 20, upper = 30;
    std::shared_ptr<const p2pro::CorrectionTable> correction;
    p2pro::MeasurementSettings measurements;
    std::uint64_t measurement_version = 0;
};

struct Frame {
    std::array<std::uint16_t, PixelCount> plane{};
    std::array<std::uint8_t, CompositeBytes> composite{};
    std::uint64_t sequence = 0;
    std::uint64_t generation = 0;
    std::int64_t callback_ns = 0;
    std::int64_t utc_ns = 0;
    bool fixture = false;
    bool network = false;
    bool archive = false;
    int gain = 1;
    bool command_active = false;
    double minimum = 0, maximum = 0, center = 0;
    unsigned min_index = 0, max_index = 0;
    unsigned invalid_pixels = 0;
    DisplaySettings display;
    p2pro::Measurements measurements;
};
struct CaptureRequest {
    Frame frame;
    std::string identity;
    std::vector<std::uint8_t> rgba;
    std::mutex mutex;
    std::condition_variable condition;
    bool done = false;
    std::string error;
};

class Engine {
public:
    Engine();
    ~Engine();
    void set_surface(ANativeWindow* owned_window);
    void configure(int palette, bool flip, int rotation, bool mirror, bool automatic, float lower, float upper);
    void correction(double emissivity, double reflected_celsius, bool corrected);
    unsigned geometry(unsigned id, int kind, double x0, double y0, double x1, double y1);
    void erase_geometry(unsigned id);
    void measurement_options(unsigned first, unsigned second, int isotherm, float lower, float upper);
    std::uint64_t measurement_version();
    std::string open(int borrowed_fd);
    void replay(const std::vector<std::uint8_t>& composite);
    void archive(const std::vector<std::uint8_t>& composite, std::int64_t timestamp, const std::string& original_source, int gain,
                 const std::string& firmware, const std::vector<int>& original_properties, const std::vector<int>& configured_properties);
    void restore_measurements(const std::vector<int>& geometry, unsigned first, unsigned second, int isotherm, float lower, float upper);
    void begin_network();
    void network_frame(const std::vector<std::uint8_t>& bytes, std::uint32_t sequence);
    void cancel();
    void stop();
    void nuc();
    void gain(bool high);
    std::string summary(bool include_measurements = true);
    std::vector<std::uint8_t> dump_frame();
    std::vector<std::uint8_t> snapshot();
    std::vector<std::uint8_t> capture();
private:
    static void callback(uvc_frame_t* frame, void* user);
    void ingest(const std::uint8_t* bytes, std::size_t length, std::size_t stride);
    void render_loop();
    void close_session();
    void reset_frames(bool fixture);
    std::mutex operation_mutex_;
    std::mutex mutex_;
    std::condition_variable condition_;
    std::vector<std::shared_ptr<CaptureRequest>> capture_requests_;
    std::array<Frame, 4> frames_;
    std::array<bool, 4> occupied_{};
    std::array<int, 4> queue_{};
    unsigned queue_head_ = 0, queue_tail_ = 0, queue_size_ = 0;
    Frame last_presented_;
    DisplaySettings settings_;
    p2pro::BandPlanckTable planck_;
    unsigned next_geometry_id_ = 1;
    ANativeWindow* desired_window_ = nullptr;
    std::uint64_t window_generation_ = 0;
    std::uint64_t capture_generation_ = 0;
    std::atomic<bool> cancelled_{true}, quitting_{false};
    std::thread render_thread_, replay_thread_;
    uvc_context_t* context_ = nullptr;
    uvc_device_handle_t* device_ = nullptr;
    std::unique_ptr<p2pro::Transport> transport_;
    std::unique_ptr<p2pro::Camera> camera_;
    bool streaming_ = false, fixture_ = false, network_ = false;
    bool archive_ = false;
    std::int64_t archive_timestamp_ = 0;
    int gain_mode_ = 1;
    bool command_active_ = false;
    std::string error_, identity_ = "{}";
    std::uint64_t received_ = 0, rendered_ = 0, malformed_ = 0, overflow_ = 0;
    std::uint64_t source_sequence_gaps_ = 0;
    std::uint32_t last_source_sequence_ = 0;
    bool source_sequence_seen_ = false;
    std::int64_t first_callback_ns_ = 0, last_callback_ns_ = 0, last_change_ns_ = 0;
    std::uint64_t last_hash_ = 0;
    double swap_latency_ms_ = 0, max_swap_latency_ms_ = 0;
    double presentation_latency_ms_ = 0;
    std::uint64_t presentation_samples_ = 0;
};
std::int64_t monotonic_ns();
std::string quote(const std::string& value);
} // namespace thermal
