#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/log.h>

#define LOG_TAG "FFmpegThumbnail"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static void ffmpeg_log_callback(void *ptr, int level, const char *fmt, va_list vl) {
    if (level > AV_LOG_WARNING) return; // Only log warnings and errors to prevent log flooding
    int android_level = ANDROID_LOG_DEFAULT;
    if (level <= AV_LOG_ERROR) android_level = ANDROID_LOG_ERROR;
    else if (level <= AV_LOG_WARNING) android_level = ANDROID_LOG_WARN;
    else if (level <= AV_LOG_INFO) android_level = ANDROID_LOG_INFO;
    else android_level = ANDROID_LOG_DEBUG;
    __android_log_vprint(android_level, "FFmpegNative", fmt, vl);
}

static void process_bitmap_pixels(AndroidBitmapInfo *bmp_info, void *bmp_pixels, jboolean *is_black) {
    uint32_t *pixels = (uint32_t *)bmp_pixels;
    int w = bmp_info->width;
    int h = bmp_info->height;
    int sample_indices[] = {
        (h / 2) * w + (w / 2),
        (h / 4) * w + (w / 4),
        (3 * h / 4) * w + (3 * w / 4),
        (h / 4) * w + (3 * w / 4),
        (3 * h / 4) * w + (w / 4)
    };
    int sum_val = 0;
    *is_black = JNI_TRUE;

    for (int i = 0; i < 5; i++) {
        uint32_t pixel = pixels[sample_indices[i]];
        if (bmp_info->format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            uint8_t r = pixel & 0xFF;
            uint8_t g = (pixel >> 8) & 0xFF;
            uint8_t b = (pixel >> 16) & 0xFF;
            sum_val += r + g + b;
            if (r > 90 || g > 90 || b > 90) {
                *is_black = JNI_FALSE;
            }
        } else {
            uint16_t pix16 = ((uint16_t *)bmp_pixels)[sample_indices[i]];
            uint8_t r = (pix16 >> 11) & 0x1F;
            uint8_t g = (pix16 >> 5) & 0x3F;
            uint8_t b = pix16 & 0x1F;
            // Normalize to 255
            sum_val += (r * 255 / 31) + (g * 255 / 63) + (b * 255 / 31);
            if (r > 11 || g > 22 || b > 11) {
                *is_black = JNI_FALSE;
            }
        }
    }

    // Calculate average channel value (out of 255)
    float avg_brightness = sum_val / 15.0f;
    if (avg_brightness < 100.0f) {
        float gain = 1.0f + (100.0f - avg_brightness) / 100.0f * 1.0f; // Max 2.0x gain
        __android_log_print(ANDROID_LOG_INFO, "FFmpegThumbnail", "Low average brightness (%.1f). Applying exposure gain: %.2fx", avg_brightness, gain);
        if (bmp_info->format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            for (int i = 0; i < w * h; i++) {
                uint32_t pixel = pixels[i];
                int r = (int)((pixel & 0xFF) * gain);
                int g = (int)(((pixel >> 8) & 0xFF) * gain);
                int b = (int)(((pixel >> 16) & 0xFF) * gain);
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (b > 255) b = 255;
                pixels[i] = (pixel & 0xFF000000) | r | (g << 8) | (b << 16);
            }
        } else {
            uint16_t *pixels16 = (uint16_t *)bmp_pixels;
            for (int i = 0; i < w * h; i++) {
                uint16_t pix16 = pixels16[i];
                int r = (int)(((pix16 >> 11) & 0x1F) * gain);
                int g = (int)(((pix16 >> 5) & 0x3F) * gain);
                int b = (int)((pix16 & 0x1F) * gain);
                if (r > 31) r = 31;
                if (g > 63) g = 63;
                if (b > 31) b = 31;
                pixels16[i] = (r << 11) | (g << 5) | b;
            }
        }
    }

    // Force alpha channel to 255 for RGBA if it's 0 (transparent)
    if (bmp_info->format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        for (int i = 0; i < w * h; i++) {
            pixels[i] |= 0xFF000000;
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegThumbnailHelper_extractFrame(
        JNIEnv *env, jobject thiz, jstring video_path, jint time_percent, jobject bitmap) {

    av_log_set_callback(ffmpeg_log_callback);
    av_log_set_level(AV_LOG_WARNING);

    const char *path = (*env)->GetStringUTFChars(env, video_path, NULL);
    if (!path) {
        return JNI_FALSE;
    }

    // Get bitmap info
    AndroidBitmapInfo bmp_info;
    void *bmp_pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &bmp_info) < 0) {
        LOGE("AndroidBitmap_getInfo failed");
        (*env)->ReleaseStringUTFChars(env, video_path, path);
        return JNI_FALSE;
    }

    if (bmp_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && bmp_info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        LOGE("Unsupported bitmap format (must be RGBA_8888 or RGB_565)");
        (*env)->ReleaseStringUTFChars(env, video_path, path);
        return JNI_FALSE;
    }

    // Open video
    AVFormatContext *format_ctx = NULL;
    if (avformat_open_input(&format_ctx, path, NULL, NULL) != 0) {
        LOGE("avformat_open_input failed for %s", path);
        (*env)->ReleaseStringUTFChars(env, video_path, path);
        return JNI_FALSE;
    }

    (*env)->ReleaseStringUTFChars(env, video_path, path);

    if (avformat_find_stream_info(format_ctx, NULL) < 0) {
        LOGE("avformat_find_stream_info failed");
        avformat_close_input(&format_ctx);
        return JNI_FALSE;
    }

    // Find video stream
    int video_stream_idx = -1;
    const AVCodec *codec = NULL;
    video_stream_idx = av_find_best_stream(format_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (video_stream_idx < 0 || !codec) {
        LOGE("No video stream found");
        avformat_close_input(&format_ctx);
        return JNI_FALSE;
    }

    AVCodecContext *codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx) {
        LOGE("avcodec_alloc_context3 failed");
        avformat_close_input(&format_ctx);
        return JNI_FALSE;
    }

    if (avcodec_parameters_to_context(codec_ctx, format_ctx->streams[video_stream_idx]->codecpar) < 0) {
        LOGE("avcodec_parameters_to_context failed");
        avcodec_free_context(&codec_ctx);
        avformat_close_input(&format_ctx);
        return JNI_FALSE;
    }

    // Single-thread decoding is fine and uses less memory
    codec_ctx->thread_count = 1;

    if (avcodec_open2(codec_ctx, codec, NULL) < 0) {
        LOGE("avcodec_open2 failed");
        avcodec_free_context(&codec_ctx);
        avformat_close_input(&format_ctx);
        return JNI_FALSE;
    }

    // Seek to the requested percentage of the video duration
    AVStream *stream = format_ctx->streams[video_stream_idx];
    if (format_ctx->duration > 0 && time_percent > 0) {
        int64_t target_ts = format_ctx->duration * time_percent / 100;
        // Rescale target timestamp from AV_TIME_BASE to the stream's timebase
        int64_t seek_target = av_rescale_q(target_ts, AV_TIME_BASE_Q, stream->time_base);
        if (av_seek_frame(format_ctx, video_stream_idx, seek_target, AVSEEK_FLAG_BACKWARD) < 0) {
            LOGD("Seek failed, decoding from the beginning");
        } else {
            avcodec_flush_buffers(codec_ctx);
        }
    } else if (time_percent > 0 && format_ctx->pb) {
        // Skip byte seeking for Matroska/WebM because it causes EBML parser alignment and decoding errors
        if (strstr(format_ctx->iformat->name, "matroska") == NULL && 
            strstr(format_ctx->iformat->name, "webm") == NULL) {
            int64_t file_size = avio_size(format_ctx->pb);
            if (file_size > 0) {
                int64_t target_byte = file_size * time_percent / 100;
                LOGD("Duration unknown. Attempting byte-based seek to %lld (file size: %lld)", (long long)target_byte, (long long)file_size);
                if (av_seek_frame(format_ctx, -1, target_byte, AVSEEK_FLAG_BYTE) < 0) {
                    LOGD("Byte-based seek failed, decoding from the beginning");
                } else {
                    avcodec_flush_buffers(codec_ctx);
                }
            }
        } else {
            LOGD("Matroska/WebM format with unknown duration. Decoding sequentially from the beginning to avoid EBML parser errors.");
        }
    }

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    jboolean success = JNI_FALSE;

    int max_frames_to_read = 300; // safety limit to prevent infinite loop or scanning long videos without keyframes
    int frame_count = 0;

    // First attempt: decode starting from seek target
    while (av_read_frame(format_ctx, packet) >= 0 && frame_count < max_frames_to_read) {
        if (packet->stream_index == video_stream_idx) {
            frame_count++;
            int response = avcodec_send_packet(codec_ctx, packet);
            if (response < 0) {
                av_packet_unref(packet);
                break;
            }

            response = avcodec_receive_frame(codec_ctx, frame);
            if (response == 0) {
                LOGD("avcodec_receive_frame success: frame width=%d, height=%d, format=%d", frame->width, frame->height, frame->format);
                jboolean is_black = JNI_TRUE;
                // Got a decoded frame! Let's lock the bitmap pixels and scale/convert it.
                if (AndroidBitmap_lockPixels(env, bitmap, &bmp_pixels) >= 0 && bmp_pixels) {
                    enum AVPixelFormat dst_pix_fmt;
                    if (bmp_info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                        dst_pix_fmt = AV_PIX_FMT_RGBA;
                    } else {
                        dst_pix_fmt = AV_PIX_FMT_RGB565;
                    }

                    struct SwsContext *sws_ctx = sws_getContext(
                            frame->width, frame->height, frame->format,
                            bmp_info.width, bmp_info.height, dst_pix_fmt,
                            SWS_BILINEAR, NULL, NULL, NULL);

                    LOGD("sws_getContext result: %p (dst_pix_fmt=%d)", sws_ctx, dst_pix_fmt);

                    if (sws_ctx) {
                        uint8_t *dst_data[4] = { (uint8_t *)bmp_pixels, NULL, NULL, NULL };
                        int dst_linesize[4] = { bmp_info.stride, 0, 0, 0 };

                        int scale_res = sws_scale(sws_ctx, (const uint8_t *const *)frame->data, frame->linesize,
                                  0, frame->height, dst_data, dst_linesize);
                        LOGD("sws_scale slice height: %d", scale_res);

                        process_bitmap_pixels(&bmp_info, bmp_pixels, &is_black);
                        sws_freeContext(sws_ctx);
                        success = JNI_TRUE;
                    } else {
                        LOGE("sws_getContext failed");
                    }
                    AndroidBitmap_unlockPixels(env, bitmap);
                } else {
                    LOGE("AndroidBitmap_lockPixels failed");
                }
                av_packet_unref(packet);

                if (!is_black) {
                    LOGD("Found non-black frame at frame_count=%d. Exiting loop.", frame_count);
                    break;
                } else {
                    LOGD("Frame %d was black, continuing search...", frame_count);
                }
            } else if (response == AVERROR(EAGAIN)) {
                // Try reading more packets
            } else {
                av_packet_unref(packet);
                break;
            }
        }
        av_packet_unref(packet);
    }

    // Second attempt: if seek target yielded no frames (possibly due to truncated/partial files), retry from the beginning
    if (!success && time_percent > 0) {
        LOGD("Seek target yielded no frames (possibly truncated file), retrying from the beginning");
        av_seek_frame(format_ctx, video_stream_idx, 0, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(codec_ctx);
        frame_count = 0;

        while (av_read_frame(format_ctx, packet) >= 0 && frame_count < max_frames_to_read) {
            if (packet->stream_index == video_stream_idx) {
                frame_count++;
                int response = avcodec_send_packet(codec_ctx, packet);
                if (response < 0) {
                    av_packet_unref(packet);
                    break;
                }

                response = avcodec_receive_frame(codec_ctx, frame);
                if (response == 0) {
                    LOGD("avcodec_receive_frame fallback success: frame width=%d, height=%d, format=%d", frame->width, frame->height, frame->format);
                    jboolean is_black = JNI_TRUE;
                    if (AndroidBitmap_lockPixels(env, bitmap, &bmp_pixels) >= 0 && bmp_pixels) {
                        enum AVPixelFormat dst_pix_fmt;
                        if (bmp_info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                            dst_pix_fmt = AV_PIX_FMT_RGBA;
                        } else {
                            dst_pix_fmt = AV_PIX_FMT_RGB565;
                        }

                        struct SwsContext *sws_ctx = sws_getContext(
                                frame->width, frame->height, frame->format,
                                bmp_info.width, bmp_info.height, dst_pix_fmt,
                                SWS_BILINEAR, NULL, NULL, NULL);

                        LOGD("sws_getContext fallback result: %p (dst_pix_fmt=%d)", sws_ctx, dst_pix_fmt);

                        if (sws_ctx) {
                            uint8_t *dst_data[4] = { (uint8_t *)bmp_pixels, NULL, NULL, NULL };
                            int dst_linesize[4] = { bmp_info.stride, 0, 0, 0 };

                            int scale_res = sws_scale(sws_ctx, (const uint8_t *const *)frame->data, frame->linesize,
                                      0, frame->height, dst_data, dst_linesize);
                            LOGD("sws_scale fallback slice height: %d", scale_res);

                            process_bitmap_pixels(&bmp_info, bmp_pixels, &is_black);
                            sws_freeContext(sws_ctx);
                            success = JNI_TRUE;
                        } else {
                            LOGE("sws_getContext fallback failed");
                        }
                        AndroidBitmap_unlockPixels(env, bitmap);
                    } else {
                        LOGE("AndroidBitmap_lockPixels fallback failed");
                    }
                    av_packet_unref(packet);

                    if (!is_black) {
                        LOGD("Found fallback non-black frame at frame_count=%d. Exiting loop.", frame_count);
                        break;
                    } else {
                        LOGD("Fallback frame %d was black, continuing search...", frame_count);
                    }
                } else if (response == AVERROR(EAGAIN)) {
                    // Try reading more packets
                } else {
                    av_packet_unref(packet);
                    break;
                }
            }
            av_packet_unref(packet);
        }
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codec_ctx);
    avformat_close_input(&format_ctx);

    return success;
}
