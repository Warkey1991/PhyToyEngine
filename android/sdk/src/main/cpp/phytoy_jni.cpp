#include "phytoy/phytoy.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <unistd.h>

namespace {

constexpr const char* kLogTag = "PhyToyCamera2";
constexpr uint32_t kDefaultProcessingFrameRate = 15U;
constexpr uint32_t kMaximumProcessingFrameRate = 60U;
constexpr uint64_t kNanosecondsPerSecond = 1'000'000'000ULL;

void log_error(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
}

void log_info(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

void require_media(media_status_t status, const char* operation) {
    if (status != AMEDIA_OK) {
        throw std::runtime_error(std::string(operation) + " failed with media status " +
                                 std::to_string(status));
    }
}

void require_engine(pte_status_t status, pte_engine_t* engine, const char* operation) {
    if (status == PTE_STATUS_OK) return;
    const char* detail = engine == nullptr ? pte_last_error() : pte_engine_last_error(engine);
    throw std::runtime_error(std::string(operation) + " failed: " +
                             (detail == nullptr ? "unknown engine error" : detail));
}

class PendingImage {
public:
    PendingImage() = default;
    PendingImage(AImage* image, int fence_fd, uint64_t sequence)
        : image_(image), fence_fd_(fence_fd), sequence_(sequence) {}
    ~PendingImage() { reset(); }
    PendingImage(const PendingImage&) = delete;
    PendingImage& operator=(const PendingImage&) = delete;
    PendingImage(PendingImage&& other) noexcept { *this = std::move(other); }
    PendingImage& operator=(PendingImage&& other) noexcept {
        if (this != &other) {
            reset();
            image_ = std::exchange(other.image_, nullptr);
            fence_fd_ = std::exchange(other.fence_fd_, -1);
            sequence_ = other.sequence_;
        }
        return *this;
    }

    [[nodiscard]] explicit operator bool() const noexcept { return image_ != nullptr; }
    [[nodiscard]] AImage* image() const noexcept { return image_; }
    [[nodiscard]] int fence_fd() const noexcept { return fence_fd_; }
    [[nodiscard]] uint64_t sequence() const noexcept { return sequence_; }

    void reset() noexcept {
        if (fence_fd_ >= 0) ::close(fence_fd_);
        if (image_ != nullptr) AImage_delete(image_);
        image_ = nullptr;
        fence_fd_ = -1;
        sequence_ = 0U;
    }

private:
    AImage* image_{};
    int fence_fd_{-1};
    uint64_t sequence_{};
};

class CameraSession {
public:
    CameraSession() = default;
    ~CameraSession() { close(); }
    CameraSession(const CameraSession&) = delete;
    CameraSession& operator=(const CameraSession&) = delete;

    void initialize(const std::string& host_profile, const std::string& toy_profile,
                    int32_t width, int32_t height, int32_t max_images,
                    ANativeWindow* output_window, uint32_t output_rotation_degrees) {
        output_window_ = output_window;
        if (width <= 0 || height <= 0 || max_images < 3) {
            throw std::invalid_argument(
                "invalid Camera2 ImageReader dimensions or maxImages; async preview requires at least 3");
        }
        width_ = static_cast<uint32_t>(width);
        height_ = static_cast<uint32_t>(height);
        if (output_window == nullptr) {
            throw std::invalid_argument("processed preview output Surface is required");
        }

        require_engine(
            pte_engine_create(host_profile.c_str(), toy_profile.c_str(), &engine_),
            engine_, "pte_engine_create");
        require_engine(
            pte_engine_set_backend(engine_, PTE_BACKEND_VULKAN),
            engine_, "pte_engine_set_backend(Vulkan)");
        require_engine(
            pte_engine_set_output_surface(
                engine_, output_window_, output_rotation_degrees),
            engine_, "pte_engine_set_output_surface(Vulkan swapchain)");
        if (pte_engine_supports_ahardware_buffer_input(engine_) == 0U) {
            throw std::runtime_error(
                "selected Vulkan device cannot import Camera2 AHardwareBuffer input");
        }

        require_media(
            AImageReader_newWithUsage(
                width, height, AIMAGE_FORMAT_PRIVATE,
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, max_images, &reader_),
            "AImageReader_newWithUsage(PRIVATE/GPU_SAMPLED_IMAGE)");
        require_media(AImageReader_getWindow(reader_, &window_), "AImageReader_getWindow");

        worker_ = std::thread(&CameraSession::worker_loop, this);

        AImageReader_ImageListener image_listener{};
        image_listener.context = this;
        image_listener.onImageAvailable = &CameraSession::on_image_available;
        require_media(AImageReader_setImageListener(reader_, &image_listener),
                      "AImageReader_setImageListener");

        AImageReader_BufferRemovedListener removed_listener{};
        removed_listener.context = this;
        removed_listener.onBufferRemoved = &CameraSession::on_buffer_removed;
        require_media(AImageReader_setBufferRemovedListener(reader_, &removed_listener),
                      "AImageReader_setBufferRemovedListener");

        log_info("Camera2 PRIVATE input session ready: " + std::to_string(width_) + "x" +
                 std::to_string(height_) + " async_latest preview_no_cpu_readback " +
                 "capture_readback_on_demand " +
                 "processed_gpu_surface rotation=" +
                 std::to_string(output_rotation_degrees) + " thermal_cadence=" +
                 std::to_string(target_processing_fps_.load()) + "fps " +
                 pte_version_string());
    }

    jobject input_surface(JNIEnv* environment) const {
        if (window_ == nullptr) return nullptr;
        return ANativeWindow_toSurface(environment, window_);
    }

    void set_processing_frame_rate(int32_t frames_per_second) {
        if (frames_per_second < 0 ||
            frames_per_second > static_cast<int32_t>(kMaximumProcessingFrameRate)) {
            throw std::invalid_argument("processing frame-rate limit must be in 0..60");
        }
        target_processing_fps_.store(static_cast<uint32_t>(frames_per_second));
        {
            std::lock_guard lock(queue_mutex_);
            last_admitted_time_ns_ = 0U;
            if (frames_per_second == 0 && pending_image_) {
                ++throttled_frames_;
                pending_image_.reset();
            }
        }
        log_info("Camera2 Vulkan processing target changed to " +
                 std::to_string(frames_per_second) + " fps");
    }

    std::vector<jlong> snapshot() const {
        const auto [p50_latency_us, p95_latency_us] = latency_percentiles();
        return {
            static_cast<jlong>(received_frames_.load()),
            static_cast<jlong>(rendered_frames_.load()),
            static_cast<jlong>(dropped_frames_.load()),
            static_cast<jlong>(error_frames_.load()),
            static_cast<jlong>(last_latency_us_.load()),
            static_cast<jlong>(maximum_latency_us_.load()),
            static_cast<jlong>(queue_submissions_.load()),
            static_cast<jlong>(hardware_buffer_imports_.load()),
            static_cast<jlong>(zero_copy_frames_.load()),
            static_cast<jlong>(resource_allocations_.load()),
            static_cast<jlong>(allocated_bytes_.load()),
            static_cast<jlong>(p50_latency_us),
            static_cast<jlong>(p95_latency_us),
            static_cast<jlong>(input_image_format_.load()),
            static_cast<jlong>(input_buffer_format_.load()),
            static_cast<jlong>(input_buffer_usage_.load()),
            static_cast<jlong>(throttled_frames_.load()),
            static_cast<jlong>(target_processing_fps_.load()),
            static_cast<jlong>(presented_frames_.load()),
            static_cast<jlong>(swapchain_recreates_.load()),
            static_cast<jlong>(output_width_.load()),
            static_cast<jlong>(output_height_.load()),
        };
    }

    std::string last_error() const {
        std::lock_guard lock(error_mutex_);
        return last_error_;
    }

    std::vector<uint32_t> capture_next_frame(int32_t timeout_millis) {
        if (timeout_millis < 250 || timeout_millis > 10'000) {
            throw std::invalid_argument("capture timeout must be in 250..10000 ms");
        }
        if (target_processing_fps_.load() == 0U) {
            throw std::runtime_error("capture is unavailable while thermal processing is paused");
        }

        std::unique_lock lock(capture_mutex_);
        if (capture_pending_) {
            throw std::runtime_error("another still capture is already pending");
        }
        const uint64_t request_id = ++capture_request_sequence_;
        capture_active_id_ = request_id;
        capture_pending_ = true;
        capture_claimed_ = false;
        captured_pixels_.clear();
        capture_failure_.clear();

        const bool completed = capture_finished_.wait_for(
            lock, std::chrono::milliseconds(timeout_millis), [this, request_id] {
                return closing_.load() ||
                    (!capture_pending_ && capture_active_id_ == request_id);
            });
        if (!completed) {
            if (capture_active_id_ == request_id) capture_pending_ = false;
            throw std::runtime_error("timed out waiting for a processed camera frame");
        }
        if (closing_.load()) {
            throw std::runtime_error("camera session closed during capture");
        }
        if (!capture_failure_.empty()) {
            throw std::runtime_error(capture_failure_);
        }
        if (captured_pixels_.size() !=
            static_cast<size_t>(width_) * height_) {
            throw std::runtime_error("processed capture returned an unexpected pixel count");
        }
        return std::move(captured_pixels_);
    }

    void cancel_capture() noexcept {
        {
            std::lock_guard lock(capture_mutex_);
            if (!capture_pending_) return;
            capture_pending_ = false;
            capture_failure_ = "still capture was cancelled";
        }
        capture_finished_.notify_all();
    }

    void close() noexcept {
        bool expected = false;
        if (!closed_.compare_exchange_strong(expected, true)) return;
        closing_.store(true);
        {
            std::lock_guard lock(capture_mutex_);
            capture_pending_ = false;
            capture_failure_ = "camera session closed during capture";
        }
        capture_finished_.notify_all();

        if (reader_ != nullptr) {
            AImageReader_setImageListener(reader_, nullptr);
            AImageReader_setBufferRemovedListener(reader_, nullptr);
        }
        {
            std::unique_lock lock(callback_mutex_);
            callback_finished_.wait(lock, [this] { return callbacks_in_flight_ == 0U; });
        }
        {
            std::lock_guard lock(queue_mutex_);
            worker_stopping_ = true;
            if (pending_image_) {
                ++dropped_frames_;
                pending_image_.reset();
            }
        }
        queue_ready_.notify_all();
        if (worker_.joinable()) worker_.join();

        if (reader_ != nullptr) {
            AImageReader_delete(reader_);
            reader_ = nullptr;
            window_ = nullptr;
        }
        if (engine_ != nullptr) {
            pte_engine_forget_ahardware_buffer(engine_, nullptr);
            pte_engine_destroy(engine_);
            engine_ = nullptr;
        }
        if (output_window_ != nullptr) {
            ANativeWindow_release(output_window_);
            output_window_ = nullptr;
        }
    }

private:
    class CallbackGuard {
    public:
        explicit CallbackGuard(CameraSession& owner) : owner_(owner) {
            std::lock_guard lock(owner_.callback_mutex_);
            ++owner_.callbacks_in_flight_;
        }
        ~CallbackGuard() {
            std::lock_guard lock(owner_.callback_mutex_);
            --owner_.callbacks_in_flight_;
            owner_.callback_finished_.notify_all();
        }

    private:
        CameraSession& owner_;
    };

    static void on_image_available(void* context, AImageReader* reader) {
        auto& session = *static_cast<CameraSession*>(context);
        CallbackGuard callback(session);
        if (!session.closing_.load()) session.enqueue_latest_image(reader);
    }

    static void on_buffer_removed(void* context, AImageReader*, AHardwareBuffer* buffer) {
        auto& session = *static_cast<CameraSession*>(context);
        CallbackGuard callback(session);
        if (session.closing_.load() || session.engine_ == nullptr) return;
        std::lock_guard render_lock(session.render_mutex_);
        const pte_status_t status =
            pte_engine_forget_ahardware_buffer(session.engine_, buffer);
        if (status != PTE_STATUS_OK) {
            session.record_error(pte_engine_last_error(session.engine_));
        }
        session.refresh_engine_stats();
    }

    void enqueue_latest_image(AImageReader* reader) noexcept {
        AImage* image = nullptr;
        int acquire_fence_fd = -1;
        const media_status_t acquired =
            AImageReader_acquireLatestImageAsync(reader, &image, &acquire_fence_fd);
        if (acquired == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) return;
        if (acquired != AMEDIA_OK || image == nullptr) {
            ++error_frames_;
            record_error("AImageReader_acquireLatestImageAsync failed: " +
                         std::to_string(acquired));
            if (acquire_fence_fd >= 0) ::close(acquire_fence_fd);
            return;
        }

        const uint64_t sequence = ++received_frames_;
        PendingImage incoming(image, acquire_fence_fd, sequence);
        {
            std::lock_guard lock(queue_mutex_);
            if (worker_stopping_) {
                ++dropped_frames_;
                return;
            }
            const uint32_t target_fps = target_processing_fps_.load();
            if (target_fps == 0U) {
                ++throttled_frames_;
                return;
            }
            const uint64_t now_ns = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count());
            const uint64_t interval_ns = kNanosecondsPerSecond / target_fps;
            const uint64_t jitter_tolerance_ns = std::min<uint64_t>(
                interval_ns / 20U, 4'000'000ULL);
            if (last_admitted_time_ns_ != 0U &&
                now_ns + jitter_tolerance_ns < last_admitted_time_ns_ + interval_ns) {
                ++throttled_frames_;
                return;
            }
            last_admitted_time_ns_ = now_ns;
            if (pending_image_) ++dropped_frames_;
            pending_image_ = std::move(incoming);
        }
        queue_ready_.notify_one();
    }

    void worker_loop() noexcept {
        for (;;) {
            PendingImage image;
            {
                std::unique_lock lock(queue_mutex_);
                queue_ready_.wait(lock, [this] {
                    return worker_stopping_ || static_cast<bool>(pending_image_);
                });
                if (worker_stopping_) return;
                image = std::move(pending_image_);
            }
            process_image(image);
        }
    }

    uint64_t claim_capture_request() {
        std::lock_guard lock(capture_mutex_);
        if (!capture_pending_ || capture_claimed_) return 0U;
        capture_claimed_ = true;
        return capture_active_id_;
    }

    void complete_capture(uint64_t request_id, std::vector<uint32_t> pixels) {
        {
            std::lock_guard lock(capture_mutex_);
            if (!capture_pending_ || capture_active_id_ != request_id) return;
            captured_pixels_ = std::move(pixels);
            capture_failure_.clear();
            capture_pending_ = false;
        }
        capture_finished_.notify_all();
    }

    void fail_capture(uint64_t request_id, const std::string& message) {
        {
            std::lock_guard lock(capture_mutex_);
            if (!capture_pending_ || capture_active_id_ != request_id) return;
            captured_pixels_.clear();
            capture_failure_ = message;
            capture_pending_ = false;
        }
        capture_finished_.notify_all();
    }

    void process_image(PendingImage& pending) noexcept {
        const uint64_t capture_request_id = claim_capture_request();
        try {
            AImage* image = pending.image();
            int32_t image_width = 0;
            int32_t image_height = 0;
            int32_t image_format = 0;
            require_media(AImage_getWidth(image, &image_width), "AImage_getWidth");
            require_media(AImage_getHeight(image, &image_height), "AImage_getHeight");
            require_media(AImage_getFormat(image, &image_format), "AImage_getFormat");
            if (image_width != static_cast<int32_t>(width_) ||
                image_height != static_cast<int32_t>(height_)) {
                throw std::runtime_error(
                    "Camera2 image dimensions changed from configured reader size");
            }
            input_image_format_.store(static_cast<uint32_t>(image_format));

            AHardwareBuffer* hardware_buffer = nullptr;
            require_media(AImage_getHardwareBuffer(image, &hardware_buffer),
                          "AImage_getHardwareBuffer");
            if (hardware_buffer == nullptr) {
                throw std::runtime_error("Camera2 PRIVATE image has no AHardwareBuffer");
            }
            AHardwareBuffer_Desc description{};
            AHardwareBuffer_describe(hardware_buffer, &description);
            input_buffer_format_.store(description.format);
            input_buffer_usage_.store(description.usage);

            pte_ahardware_buffer_frame_t frame{};
            frame.abi_version = PTE_ABI_VERSION;
            frame.buffer = hardware_buffer;
            frame.width = width_;
            frame.height = height_;
            frame.acquire_fence_fd = pending.fence_fd();

            pte_render_options_t options{};
            options.abi_version = PTE_ABI_VERSION;
            options.seed = pending.sequence();

            const auto started = std::chrono::steady_clock::now();
            {
                std::lock_guard render_lock(render_mutex_);
                if (capture_request_id == 0U) {
                    require_engine(
                        pte_engine_process_ahardware_buffer(engine_, &frame, &options),
                        engine_, "pte_engine_process_ahardware_buffer(Camera2 PRIVATE)");
                } else {
                    std::vector<float> captured(
                        static_cast<size_t>(width_) * height_ * 3U);
                    pte_output_f32_t output{};
                    output.data = captured.data();
                    output.capacity_floats = captured.size();
                    output.row_stride_floats = width_ * 3U;
                    require_engine(
                        pte_engine_render_ahardware_buffer(
                            engine_, &frame, &options, &output),
                        engine_, "pte_engine_render_ahardware_buffer(still capture)");
                    std::vector<uint32_t> pixels(
                        static_cast<size_t>(width_) * height_);
                    for (size_t pixel = 0U; pixel < pixels.size(); ++pixel) {
                        const auto encoded = [&captured, pixel](size_t channel) {
                            return static_cast<uint32_t>(std::lround(
                                std::clamp(captured[pixel * 3U + channel], 0.0F, 1.0F) *
                                255.0F));
                        };
                        pixels[pixel] = 0xFF000000U |
                            (encoded(0U) << 16U) |
                            (encoded(1U) << 8U) |
                            encoded(2U);
                    }
                    complete_capture(capture_request_id, std::move(pixels));
                    log_info(
                        "Digital 01 still capture ready: " + std::to_string(width_) +
                        "x" + std::to_string(height_) + " one_submission host_readback");
                }
                refresh_engine_stats();
            }
            const auto ended = std::chrono::steady_clock::now();
            const uint64_t latency = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(ended - started).count());
            last_latency_us_.store(latency);
            record_latency(latency);
            uint64_t previous_maximum = maximum_latency_us_.load();
            while (previous_maximum < latency &&
                   !maximum_latency_us_.compare_exchange_weak(previous_maximum, latency)) {}
            const uint64_t rendered = ++rendered_frames_;

            if (rendered == 1U || rendered % 30U == 0U) log_progress(rendered);
        } catch (const std::exception& exception) {
            if (capture_request_id != 0U) {
                fail_capture(capture_request_id, exception.what());
            }
            ++error_frames_;
            record_error(exception.what());
        } catch (...) {
            if (capture_request_id != 0U) {
                fail_capture(capture_request_id, "unknown still-capture failure");
            }
            ++error_frames_;
            record_error("unknown Camera2 render failure");
        }
    }

    void refresh_engine_stats() noexcept {
        pte_runtime_stats_t stats{};
        stats.abi_version = PTE_ABI_VERSION;
        if (engine_ == nullptr ||
            pte_engine_get_runtime_stats(engine_, &stats) != PTE_STATUS_OK) return;
        queue_submissions_.store(stats.vulkan_queue_submissions);
        hardware_buffer_imports_.store(stats.ahardware_buffer_imports);
        zero_copy_frames_.store(stats.zero_copy_input_frames);
        resource_allocations_.store(stats.vulkan_resource_allocations);
        allocated_bytes_.store(stats.vulkan_allocated_bytes);
        presented_frames_.store(stats.vulkan_presented_frames);
        swapchain_recreates_.store(stats.vulkan_swapchain_recreates);
        output_width_.store(stats.vulkan_output_width);
        output_height_.store(stats.vulkan_output_height);
    }

    void log_progress(uint64_t rendered) const {
        const std::vector<jlong> values = snapshot();
        log_info(
            "Camera2 PRIVATE rendered=" + std::to_string(rendered) +
            " received=" + std::to_string(values[0]) +
            " dropped=" + std::to_string(values[2]) +
            " p50_ms=" + std::to_string(values[11] / 1000.0) +
            " p95_ms=" + std::to_string(values[12] / 1000.0) +
            " errors=" + std::to_string(values[3]) +
            " submits=" + std::to_string(values[6]) +
            " imports=" + std::to_string(values[7]) +
            " zero_copy=" + std::to_string(values[8]) +
            " aimage_format=" + std::to_string(values[13]) +
            " buffer_format=" + std::to_string(values[14]) +
            " usage=" + std::to_string(values[15]) +
            " throttled=" + std::to_string(values[16]) +
            " target_fps=" + std::to_string(values[17]) +
            " presented=" + std::to_string(values[18]) +
            " swapchain_recreates=" + std::to_string(values[19]) +
            " output=" + std::to_string(values[20]) + "x" +
            std::to_string(values[21]));
    }

    void record_error(std::string message) noexcept {
        {
            std::lock_guard lock(error_mutex_);
            last_error_ = std::move(message);
        }
        log_error(last_error());
    }

    void record_latency(uint64_t latency_us) {
        std::lock_guard lock(latency_mutex_);
        latency_samples_[latency_next_] = latency_us;
        latency_next_ = (latency_next_ + 1U) % latency_samples_.size();
        latency_count_ = std::min(latency_count_ + 1U, latency_samples_.size());
    }

    std::pair<uint64_t, uint64_t> latency_percentiles() const {
        std::vector<uint64_t> samples;
        {
            std::lock_guard lock(latency_mutex_);
            samples.assign(
                latency_samples_.begin(),
                latency_samples_.begin() + static_cast<std::ptrdiff_t>(latency_count_));
        }
        if (samples.empty()) return {0U, 0U};
        std::sort(samples.begin(), samples.end());
        const auto percentile = [&samples](double fraction) {
            const size_t index = static_cast<size_t>(
                std::round(fraction * static_cast<double>(samples.size() - 1U)));
            return samples[index];
        };
        return {percentile(0.50), percentile(0.95)};
    }

    pte_engine_t* engine_{};
    AImageReader* reader_{};
    ANativeWindow* window_{};
    ANativeWindow* output_window_{};
    uint32_t width_{};
    uint32_t height_{};

    std::atomic<bool> closing_{};
    std::atomic<bool> closed_{};
    mutable std::mutex callback_mutex_;
    std::condition_variable callback_finished_;
    uint32_t callbacks_in_flight_{};

    std::thread worker_;
    mutable std::mutex queue_mutex_;
    std::condition_variable queue_ready_;
    PendingImage pending_image_;
    bool worker_stopping_{};
    uint64_t last_admitted_time_ns_{};
    mutable std::mutex render_mutex_;

    mutable std::mutex capture_mutex_;
    std::condition_variable capture_finished_;
    uint64_t capture_request_sequence_{};
    uint64_t capture_active_id_{};
    bool capture_pending_{};
    bool capture_claimed_{};
    std::vector<uint32_t> captured_pixels_;
    std::string capture_failure_;

    std::atomic<uint64_t> received_frames_{};
    std::atomic<uint64_t> rendered_frames_{};
    std::atomic<uint64_t> dropped_frames_{};
    std::atomic<uint64_t> error_frames_{};
    std::atomic<uint64_t> last_latency_us_{};
    std::atomic<uint64_t> maximum_latency_us_{};
    std::atomic<uint64_t> queue_submissions_{};
    std::atomic<uint64_t> hardware_buffer_imports_{};
    std::atomic<uint64_t> zero_copy_frames_{};
    std::atomic<uint64_t> resource_allocations_{};
    std::atomic<uint64_t> allocated_bytes_{};
    std::atomic<uint32_t> input_image_format_{};
    std::atomic<uint32_t> input_buffer_format_{};
    std::atomic<uint64_t> input_buffer_usage_{};
    std::atomic<uint64_t> throttled_frames_{};
    std::atomic<uint32_t> target_processing_fps_{kDefaultProcessingFrameRate};
    std::atomic<uint64_t> presented_frames_{};
    std::atomic<uint64_t> swapchain_recreates_{};
    std::atomic<uint32_t> output_width_{};
    std::atomic<uint32_t> output_height_{};

    mutable std::mutex latency_mutex_;
    std::array<uint64_t, 300U> latency_samples_{};
    size_t latency_next_{};
    size_t latency_count_{};
    mutable std::mutex error_mutex_;
    std::string last_error_;
};

CameraSession* session_from(jlong handle) {
    if (handle == 0) throw std::invalid_argument("native session is closed");
    return reinterpret_cast<CameraSession*>(static_cast<uintptr_t>(handle));
}

std::string utf8(JNIEnv* environment, jstring value) {
    if (value == nullptr) throw std::invalid_argument("profile path is null");
    const char* raw = environment->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) throw std::runtime_error("unable to read profile path");
    std::string result(raw);
    environment->ReleaseStringUTFChars(value, raw);
    return result;
}

void throw_java(JNIEnv* environment, const std::exception& exception) {
    jclass type = environment->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) environment->ThrowNew(type, exception.what());
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeCreate(
    JNIEnv* environment, jclass, jstring host_profile, jstring toy_profile,
    jint width, jint height, jint max_images, jobject output_surface,
    jint output_rotation_degrees) {
    try {
        auto session = std::make_unique<CameraSession>();
        if (output_surface == nullptr) {
            throw std::invalid_argument("processed preview output Surface is null");
        }
        ANativeWindow* output_window = ANativeWindow_fromSurface(
            environment, output_surface);
        session->initialize(
            utf8(environment, host_profile), utf8(environment, toy_profile),
            width, height, max_images, output_window,
            static_cast<uint32_t>(output_rotation_degrees));
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(session.release()));
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
        return 0;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeInputSurface(
    JNIEnv* environment, jclass, jlong handle) {
    try {
        return session_from(handle)->input_surface(environment);
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeSnapshot(
    JNIEnv* environment, jclass, jlong handle) {
    try {
        const std::vector<jlong> values = session_from(handle)->snapshot();
        jlongArray result = environment->NewLongArray(static_cast<jsize>(values.size()));
        if (result == nullptr) {
            throw std::runtime_error("unable to allocate native snapshot array");
        }
        environment->SetLongArrayRegion(
            result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
        return nullptr;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeCaptureNextFrame(
    JNIEnv* environment, jclass, jlong handle, jint timeout_millis) {
    try {
        const std::vector<uint32_t> pixels =
            session_from(handle)->capture_next_frame(timeout_millis);
        if (pixels.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            throw std::runtime_error("processed capture is too large for a Java array");
        }
        jintArray result = environment->NewIntArray(static_cast<jsize>(pixels.size()));
        if (result == nullptr) {
            throw std::runtime_error("unable to allocate processed capture array");
        }
        environment->SetIntArrayRegion(
            result, 0, static_cast<jsize>(pixels.size()),
            reinterpret_cast<const jint*>(pixels.data()));
        return result;
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeCancelCapture(
    JNIEnv* environment, jclass, jlong handle) {
    try {
        session_from(handle)->cancel_capture();
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeSetProcessingFrameRate(
    JNIEnv* environment, jclass, jlong handle, jint frames_per_second) {
    try {
        session_from(handle)->set_processing_frame_rate(frames_per_second);
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeLastError(
    JNIEnv* environment, jclass, jlong handle) {
    try {
        const std::string message = session_from(handle)->last_error();
        return environment->NewStringUTF(message.c_str());
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_phytoy_engine_PhyToyCameraSession_nativeClose(
    JNIEnv* environment, jclass, jlong handle) {
    try {
        delete session_from(handle);
    } catch (const std::exception& exception) {
        throw_java(environment, exception);
    }
}
