#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
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

JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegMediaHelper_nativeGetMediaInfo(
        JNIEnv *env, jobject thiz, jstring media_path) {
    const char *path = (*env)->GetStringUTFChars(env, media_path, NULL);
    if (!path) return NULL;

    AVFormatContext *format_ctx = NULL;
    if (avformat_open_input(&format_ctx, path, NULL, NULL) != 0) {
        (*env)->ReleaseStringUTFChars(env, media_path, path);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, media_path, path);

    if (avformat_find_stream_info(format_ctx, NULL) < 0) {
        avformat_close_input(&format_ctx);
        return NULL;
    }

    char info[4096];
    int offset = 0;
    offset += snprintf(info + offset, sizeof(info) - offset,
        "{\"format\":\"%s\",\"duration_sec\":%lld,\"bitrate\":%lld,\"streams\":[",
        format_ctx->iformat ? format_ctx->iformat->name : "unknown",
        (long long)(format_ctx->duration / AV_TIME_BASE),
        (long long)format_ctx->bit_rate);

    for (unsigned int i = 0; i < format_ctx->nb_streams; i++) {
        AVStream *st = format_ctx->streams[i];
        AVCodecParameters *codecpar = st->codecpar;
        const char *type_str = "other";
        if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO) type_str = "video";
        else if (codecpar->codec_type == AVMEDIA_TYPE_AUDIO) type_str = "audio";
        else if (codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) type_str = "subtitle";

        AVDictionaryEntry *lang_entry = av_dict_get(st->metadata, "language", NULL, 0);
        const char *lang = lang_entry ? lang_entry->value : "";
        AVDictionaryEntry *title_entry = av_dict_get(st->metadata, "title", NULL, 0);
        const char *title = title_entry ? title_entry->value : "";

        const char *codec_name = avcodec_get_name(codecpar->codec_id);

        if (i > 0 && offset < (int)sizeof(info) - 1) {
            offset += snprintf(info + offset, sizeof(info) - offset, ",");
        }

        if (codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            double fps = 0.0;
            if (st->r_frame_rate.den > 0) {
                fps = av_q2d(st->r_frame_rate);
            }
            offset += snprintf(info + offset, sizeof(info) - offset,
                "{\"index\":%u,\"type\":\"%s\",\"codec\":\"%s\",\"width\":%d,\"height\":%d,\"fps\":%.2f,\"bitrate\":%lld,\"lang\":\"%s\",\"title\":\"%s\"}",
                i, type_str, codec_name, codecpar->width, codecpar->height, fps, (long long)codecpar->bit_rate, lang, title);
        } else if (codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            offset += snprintf(info + offset, sizeof(info) - offset,
                "{\"index\":%u,\"type\":\"%s\",\"codec\":\"%s\",\"channels\":%d,\"sample_rate\":%d,\"bitrate\":%lld,\"lang\":\"%s\",\"title\":\"%s\"}",
                i, type_str, codec_name, codecpar->ch_layout.nb_channels, codecpar->sample_rate, (long long)codecpar->bit_rate, lang, title);
        } else {
            offset += snprintf(info + offset, sizeof(info) - offset,
                "{\"index\":%u,\"type\":\"%s\",\"codec\":\"%s\",\"lang\":\"%s\",\"title\":\"%s\"}",
                i, type_str, codec_name, lang, title);
        }
    }

    if (offset < (int)sizeof(info) - 3) {
        offset += snprintf(info + offset, sizeof(info) - offset, "]}");
    }

    avformat_close_input(&format_ctx);
    return (*env)->NewStringUTF(env, info);
}

JNIEXPORT jboolean JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegMediaHelper_nativeExtractSubtitle(
        JNIEnv *env, jobject thiz, jstring media_path, jint target_stream_idx, jstring output_path) {
    const char *in_path = (*env)->GetStringUTFChars(env, media_path, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!in_path || !out_path) {
        if (in_path) (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        if (out_path) (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFormatContext *in_ctx = NULL;
    if (avformat_open_input(&in_ctx, in_path, NULL, NULL) != 0) {
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    if (avformat_find_stream_info(in_ctx, NULL) < 0) {
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    int sub_stream_idx = target_stream_idx;
    if (sub_stream_idx < 0) {
        for (unsigned int i = 0; i < in_ctx->nb_streams; i++) {
            if (in_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) {
                sub_stream_idx = i;
                break;
            }
        }
    }

    if (sub_stream_idx < 0 || sub_stream_idx >= (int)in_ctx->nb_streams) {
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFormatContext *out_ctx = NULL;
    if (avformat_alloc_output_context2(&out_ctx, NULL, "srt", out_path) < 0 || !out_ctx) {
        if (avformat_alloc_output_context2(&out_ctx, NULL, NULL, out_path) < 0 || !out_ctx) {
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }
    }

    AVStream *in_stream = in_ctx->streams[sub_stream_idx];
    AVStream *out_stream = avformat_new_stream(out_ctx, NULL);
    if (!out_stream) {
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    if (avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar) < 0) {
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }
    out_stream->codecpar->codec_tag = 0;

    if (!(out_ctx->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&out_ctx->pb, out_path, AVIO_FLAG_WRITE) < 0) {
            avformat_free_context(out_ctx);
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }
    }

    if (avformat_write_header(out_ctx, NULL) < 0) {
        if (out_ctx->pb) avio_closep(&out_ctx->pb);
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVPacket *pkt = av_packet_alloc();
    while (av_read_frame(in_ctx, pkt) >= 0) {
        if (pkt->stream_index == sub_stream_idx) {
            pkt->stream_index = out_stream->index;
            av_packet_rescale_ts(pkt, in_stream->time_base, out_stream->time_base);
            av_interleaved_write_frame(out_ctx, pkt);
        }
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);

    av_write_trailer(out_ctx);

    if (out_ctx->pb) avio_closep(&out_ctx->pb);
    avformat_free_context(out_ctx);
    avformat_close_input(&in_ctx);

    (*env)->ReleaseStringUTFChars(env, media_path, in_path);
    (*env)->ReleaseStringUTFChars(env, output_path, out_path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegMediaHelper_nativeExtractAudio(
        JNIEnv *env, jobject thiz, jstring media_path, jint target_stream_idx, jstring output_path) {
    const char *in_path = (*env)->GetStringUTFChars(env, media_path, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!in_path || !out_path) {
        if (in_path) (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        if (out_path) (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFormatContext *in_ctx = NULL;
    if (avformat_open_input(&in_ctx, in_path, NULL, NULL) != 0) {
        LOGE("nativeExtractAudio: avformat_open_input failed for %s", in_path);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    if (avformat_find_stream_info(in_ctx, NULL) < 0) {
        LOGE("nativeExtractAudio: avformat_find_stream_info failed for %s", in_path);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    int audio_stream_idx = target_stream_idx;
    if (audio_stream_idx < 0 || audio_stream_idx >= (int)in_ctx->nb_streams ||
        in_ctx->streams[audio_stream_idx]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
        audio_stream_idx = av_find_best_stream(in_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    }

    if (audio_stream_idx < 0) {
        LOGE("nativeExtractAudio: no audio stream found in %s", in_path);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVStream *in_stream = in_ctx->streams[audio_stream_idx];

    AVFormatContext *out_ctx = NULL;
    if (avformat_alloc_output_context2(&out_ctx, NULL, NULL, out_path) < 0 || !out_ctx) {
        // Fallback: try deducing format name from extension
        const char *fmt_name = NULL;
        if (strstr(out_path, ".ac3")) fmt_name = "ac3";
        else if (strstr(out_path, ".eac3")) fmt_name = "eac3";
        else if (strstr(out_path, ".dts")) fmt_name = "dts";
        else if (strstr(out_path, ".thd") || strstr(out_path, ".truehd")) fmt_name = "truehd";
        else if (strstr(out_path, ".wav")) fmt_name = "wav";
        else if (strstr(out_path, ".flac")) fmt_name = "flac";
        else if (strstr(out_path, ".opus")) fmt_name = "opus";
        else if (strstr(out_path, ".ogg")) fmt_name = "ogg";
        else if (strstr(out_path, ".mp3")) fmt_name = "mp3";
        else if (strstr(out_path, ".m4a")) fmt_name = "ipod";
        else if (strstr(out_path, ".mka")) fmt_name = "matroska";

        if (fmt_name) {
            avformat_alloc_output_context2(&out_ctx, NULL, fmt_name, out_path);
        }
    }

    if (!out_ctx) {
        LOGE("nativeExtractAudio: avformat_alloc_output_context2 failed for %s", out_path);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVStream *out_stream = avformat_new_stream(out_ctx, NULL);
    if (!out_stream) {
        LOGE("nativeExtractAudio: avformat_new_stream failed");
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    if (avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar) < 0) {
        LOGE("nativeExtractAudio: avcodec_parameters_copy failed");
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }
    out_stream->codecpar->codec_tag = 0;

    if (out_ctx->oformat->name &&
        (strcmp(out_ctx->oformat->name, "mp4") == 0 || strcmp(out_ctx->oformat->name, "ipod") == 0)) {
        av_dict_set(&out_ctx->metadata, "movflags", "faststart", 0);
    }

    if (!(out_ctx->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&out_ctx->pb, out_path, AVIO_FLAG_WRITE) < 0) {
            LOGE("nativeExtractAudio: avio_open failed for %s", out_path);
            avformat_free_context(out_ctx);
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }
    }

    int header_err = avformat_write_header(out_ctx, NULL);
    if (header_err < 0) {
        char errbuf[256];
        av_strerror(header_err, errbuf, sizeof(errbuf));
        LOGE("nativeExtractAudio: avformat_write_header failed for %s: %d (%s), codec: %s",
             out_path, header_err, errbuf, avcodec_get_name(in_stream->codecpar->codec_id));
        if (out_ctx->pb) avio_closep(&out_ctx->pb);
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    int64_t cur_pts = 0;
    int packets_written = 0;
    AVPacket *pkt = av_packet_alloc();
    while (av_read_frame(in_ctx, pkt) >= 0) {
        if (pkt->stream_index == audio_stream_idx) {
            pkt->stream_index = out_stream->index;
            pkt->pos = -1;

            // Rescale timestamps to output time base
            av_packet_rescale_ts(pkt, in_stream->time_base, out_stream->time_base);

            // Audio packets have no B-frames; enforce non-negative, monotonically non-decreasing DTS
            if (pkt->pts == AV_NOPTS_VALUE || pkt->pts < cur_pts) {
                pkt->pts = cur_pts;
            }
            pkt->dts = pkt->pts;

            int64_t duration = pkt->duration;
            if (duration <= 0) {
                if (out_stream->codecpar->frame_size > 0) {
                    duration = out_stream->codecpar->frame_size;
                } else if (out_stream->codecpar->sample_rate > 0) {
                    duration = av_rescale_q(1024, (AVRational){1, out_stream->codecpar->sample_rate}, out_stream->time_base);
                } else {
                    duration = 1;
                }
            }
            if (duration <= 0) duration = 1;
            pkt->duration = duration;
            cur_pts = pkt->pts + duration;

            if (av_interleaved_write_frame(out_ctx, pkt) >= 0) {
                packets_written++;
            }
        }
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);

    // Flush any buffered frames in the interleaver
    av_interleaved_write_frame(out_ctx, NULL);

    av_write_trailer(out_ctx);

    if (out_ctx->pb) avio_closep(&out_ctx->pb);
    avformat_free_context(out_ctx);
    avformat_close_input(&in_ctx);

    (*env)->ReleaseStringUTFChars(env, media_path, in_path);
    (*env)->ReleaseStringUTFChars(env, output_path, out_path);

    if (packets_written == 0) {
        LOGE("nativeExtractAudio: zero audio packets written to %s", out_path);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegMediaHelper_nativeTranscodeAudio(
        JNIEnv *env, jobject thiz, jstring media_path, jint target_stream_idx, jstring output_path) {
    const char *in_path = (*env)->GetStringUTFChars(env, media_path, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!in_path || !out_path) {
        if (in_path) (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        if (out_path) (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFormatContext *in_ctx = NULL;
    if (avformat_open_input(&in_ctx, in_path, NULL, NULL) != 0) {
        LOGE("nativeTranscodeAudio: avformat_open_input failed for %s", in_path);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    if (avformat_find_stream_info(in_ctx, NULL) < 0) {
        LOGE("nativeTranscodeAudio: avformat_find_stream_info failed for %s", in_path);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    int audio_stream_idx = target_stream_idx;
    if (audio_stream_idx < 0 || audio_stream_idx >= (int)in_ctx->nb_streams ||
        in_ctx->streams[audio_stream_idx]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
        audio_stream_idx = av_find_best_stream(in_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    }

    if (audio_stream_idx < 0) {
        LOGE("nativeTranscodeAudio: no audio stream found in %s", in_path);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVStream *in_stream = in_ctx->streams[audio_stream_idx];

    // Find and open decoder
    const AVCodec *dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
    if (!dec) {
        LOGE("nativeTranscodeAudio: decoder not found for %s", avcodec_get_name(in_stream->codecpar->codec_id));
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVCodecContext *dec_ctx = avcodec_alloc_context3(dec);
    if (!dec_ctx || avcodec_parameters_to_context(dec_ctx, in_stream->codecpar) < 0 || avcodec_open2(dec_ctx, dec, NULL) < 0) {
        LOGE("nativeTranscodeAudio: failed to open decoder");
        if (dec_ctx) avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    // Allocate output context for .m4a (ipod format)
    AVFormatContext *out_ctx = NULL;
    if (avformat_alloc_output_context2(&out_ctx, NULL, "ipod", out_path) < 0 || !out_ctx) {
        LOGE("nativeTranscodeAudio: avformat_alloc_output_context2 failed for %s", out_path);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    // Find and open AAC encoder
    const AVCodec *enc = avcodec_find_encoder(AV_CODEC_ID_AAC);
    if (!enc) {
        LOGE("nativeTranscodeAudio: AAC encoder not found");
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVCodecContext *enc_ctx = avcodec_alloc_context3(enc);
    if (!enc_ctx) {
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    enc_ctx->sample_rate = dec_ctx->sample_rate > 0 ? dec_ctx->sample_rate : 48000;
    enc_ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    enc_ctx->bit_rate = 192000;
    enc_ctx->ch_layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;

    if (out_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if (avcodec_open2(enc_ctx, enc, NULL) < 0) {
        LOGE("nativeTranscodeAudio: failed to open AAC encoder");
        avcodec_free_context(&enc_ctx);
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVStream *out_stream = avformat_new_stream(out_ctx, NULL);
    if (!out_stream || avcodec_parameters_from_context(out_stream->codecpar, enc_ctx) < 0) {
        LOGE("nativeTranscodeAudio: avformat_new_stream failed");
        avcodec_free_context(&enc_ctx);
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }
    out_stream->time_base = (AVRational){1, enc_ctx->sample_rate};

    // Open output IO
    if (!(out_ctx->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&out_ctx->pb, out_path, AVIO_FLAG_WRITE) < 0) {
            LOGE("nativeTranscodeAudio: avio_open failed for %s", out_path);
            avcodec_free_context(&enc_ctx);
            avformat_free_context(out_ctx);
            avcodec_free_context(&dec_ctx);
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }
    }

    av_dict_set(&out_ctx->metadata, "movflags", "faststart", 0);

    if (avformat_write_header(out_ctx, NULL) < 0) {
        LOGE("nativeTranscodeAudio: avformat_write_header failed");
        if (out_ctx->pb) avio_closep(&out_ctx->pb);
        avcodec_free_context(&enc_ctx);
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    // Initialize resampler
    SwrContext *swr_ctx = NULL;
    int swr_err = swr_alloc_set_opts2(
        &swr_ctx,
        &enc_ctx->ch_layout, enc_ctx->sample_fmt, enc_ctx->sample_rate,
        &dec_ctx->ch_layout, dec_ctx->sample_fmt, dec_ctx->sample_rate,
        0, NULL);
    if (swr_err < 0 || !swr_ctx || swr_init(swr_ctx) < 0) {
        LOGE("nativeTranscodeAudio: swr_init failed");
        if (swr_ctx) swr_free(&swr_ctx);
        if (out_ctx->pb) avio_closep(&out_ctx->pb);
        avcodec_free_context(&enc_ctx);
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    // Audio FIFO buffer for 1024-sample AAC frames
    AVAudioFifo *fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, 1024 * 4);
    if (!fifo) {
        LOGE("nativeTranscodeAudio: av_audio_fifo_alloc failed");
        swr_free(&swr_ctx);
        if (out_ctx->pb) avio_closep(&out_ctx->pb);
        avcodec_free_context(&enc_ctx);
        avformat_free_context(out_ctx);
        avcodec_free_context(&dec_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFrame *in_frame = av_frame_alloc();
    AVFrame *enc_frame = av_frame_alloc();
    enc_frame->nb_samples = enc_ctx->frame_size > 0 ? enc_ctx->frame_size : 1024;
    enc_frame->format = enc_ctx->sample_fmt;
    av_channel_layout_copy(&enc_frame->ch_layout, &enc_ctx->ch_layout);
    enc_frame->sample_rate = enc_ctx->sample_rate;
    av_frame_get_buffer(enc_frame, 0);

    AVPacket *in_pkt = av_packet_alloc();
    AVPacket *out_pkt = av_packet_alloc();
    int64_t pts = 0;
    int frames_encoded = 0;

    while (av_read_frame(in_ctx, in_pkt) >= 0) {
        if (in_pkt->stream_index == audio_stream_idx) {
            if (avcodec_send_packet(dec_ctx, in_pkt) >= 0) {
                while (avcodec_receive_frame(dec_ctx, in_frame) == 0) {
                    // Resample to encoder format
                    int max_dst_nb_samples = av_rescale_rnd(
                        swr_get_delay(swr_ctx, dec_ctx->sample_rate) + in_frame->nb_samples,
                        enc_ctx->sample_rate, dec_ctx->sample_rate, AV_ROUND_UP);

                    uint8_t **resampled_data = NULL;
                    int resampled_linesize = 0;
                    av_samples_alloc_array_and_samples(&resampled_data, &resampled_linesize,
                                                       enc_ctx->ch_layout.nb_channels, max_dst_nb_samples,
                                                       enc_ctx->sample_fmt, 0);

                    int nb_samples_out = swr_convert(swr_ctx, resampled_data, max_dst_nb_samples,
                                                     (const uint8_t **)in_frame->data, in_frame->nb_samples);
                    if (nb_samples_out > 0) {
                        if (av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + nb_samples_out) >= 0) {
                            av_audio_fifo_write(fifo, (void **)resampled_data, nb_samples_out);
                        }
                    }
                    if (resampled_data) {
                        av_freep(&resampled_data[0]);
                        av_freep(&resampled_data);
                    }

                    // Encode full 1024-sample frames from FIFO
                    while (av_audio_fifo_size(fifo) >= enc_frame->nb_samples) {
                        av_frame_make_writable(enc_frame);
                        av_audio_fifo_read(fifo, (void **)enc_frame->data, enc_frame->nb_samples);
                        enc_frame->pts = pts;
                        pts += enc_frame->nb_samples;

                        if (avcodec_send_frame(enc_ctx, enc_frame) >= 0) {
                            while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
                                av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
                                out_pkt->stream_index = out_stream->index;
                                av_interleaved_write_frame(out_ctx, out_pkt);
                                av_packet_unref(out_pkt);
                                frames_encoded++;
                            }
                        }
                    }
                }
            }
        }
        av_packet_unref(in_pkt);
    }

    // Flush decoder
    avcodec_send_packet(dec_ctx, NULL);
    while (avcodec_receive_frame(dec_ctx, in_frame) == 0) {
        int max_dst_nb_samples = av_rescale_rnd(
            swr_get_delay(swr_ctx, dec_ctx->sample_rate) + in_frame->nb_samples,
            enc_ctx->sample_rate, dec_ctx->sample_rate, AV_ROUND_UP);
        uint8_t **resampled_data = NULL;
        int resampled_linesize = 0;
        av_samples_alloc_array_and_samples(&resampled_data, &resampled_linesize,
                                           enc_ctx->ch_layout.nb_channels, max_dst_nb_samples,
                                           enc_ctx->sample_fmt, 0);
        int nb_samples_out = swr_convert(swr_ctx, resampled_data, max_dst_nb_samples,
                                         (const uint8_t **)in_frame->data, in_frame->nb_samples);
        if (nb_samples_out > 0) {
            if (av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + nb_samples_out) >= 0) {
                av_audio_fifo_write(fifo, (void **)resampled_data, nb_samples_out);
            }
        }
        if (resampled_data) {
            av_freep(&resampled_data[0]);
            av_freep(&resampled_data);
        }
    }

    // Flush any remaining samples in FIFO (pad with silence if needed for last frame)
    int remaining = av_audio_fifo_size(fifo);
    if (remaining > 0) {
        av_frame_make_writable(enc_frame);
        // Zero all samples first
        for (int ch = 0; ch < enc_ctx->ch_layout.nb_channels; ch++) {
            memset(enc_frame->data[ch], 0, enc_frame->nb_samples * sizeof(float));
        }
        av_audio_fifo_read(fifo, (void **)enc_frame->data, remaining);
        enc_frame->pts = pts;
        pts += enc_frame->nb_samples;
        if (avcodec_send_frame(enc_ctx, enc_frame) >= 0) {
            while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
                av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
                out_pkt->stream_index = out_stream->index;
                av_interleaved_write_frame(out_ctx, out_pkt);
                av_packet_unref(out_pkt);
                frames_encoded++;
            }
        }
    }

    // Flush encoder
    avcodec_send_frame(enc_ctx, NULL);
    while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
        av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
        out_pkt->stream_index = out_stream->index;
        av_interleaved_write_frame(out_ctx, out_pkt);
        av_packet_unref(out_pkt);
        frames_encoded++;
    }

    av_interleaved_write_frame(out_ctx, NULL);
    av_write_trailer(out_ctx);

    // Cleanup
    av_packet_free(&in_pkt);
    av_packet_free(&out_pkt);
    av_frame_free(&in_frame);
    av_frame_free(&enc_frame);
    av_audio_fifo_free(fifo);
    swr_free(&swr_ctx);
    if (out_ctx->pb) avio_closep(&out_ctx->pb);
    avcodec_free_context(&enc_ctx);
    avformat_free_context(out_ctx);
    avcodec_free_context(&dec_ctx);
    avformat_close_input(&in_ctx);

    (*env)->ReleaseStringUTFChars(env, media_path, in_path);
    (*env)->ReleaseStringUTFChars(env, output_path, out_path);

    if (frames_encoded == 0) {
        LOGE("nativeTranscodeAudio: zero frames encoded for %s", out_path);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegMediaHelper_nativeConvertToMp4(
        JNIEnv *env, jobject thiz, jstring media_path, jstring output_path) {
    const char *in_path = (*env)->GetStringUTFChars(env, media_path, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!in_path || !out_path) {
        if (in_path) (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        if (out_path) (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFormatContext *in_ctx = NULL;
    if (avformat_open_input(&in_ctx, in_path, NULL, NULL) != 0) {
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    if (avformat_find_stream_info(in_ctx, NULL) < 0) {
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVFormatContext *out_ctx = NULL;
    if (avformat_alloc_output_context2(&out_ctx, NULL, "mp4", out_path) < 0 || !out_ctx) {
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    int *stream_mapping = (int *)calloc(in_ctx->nb_streams, sizeof(int));
    if (!stream_mapping) {
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    int stream_idx = 0;
    for (unsigned int i = 0; i < in_ctx->nb_streams; i++) {
        AVStream *in_stream = in_ctx->streams[i];
        AVCodecParameters *in_codecpar = in_stream->codecpar;

        // Map video and audio streams to output
        if (in_codecpar->codec_type != AVMEDIA_TYPE_VIDEO &&
            in_codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
            stream_mapping[i] = -1;
            continue;
        }

        stream_mapping[i] = stream_idx++;
        AVStream *out_stream = avformat_new_stream(out_ctx, NULL);
        if (!out_stream) {
            free(stream_mapping);
            avformat_free_context(out_ctx);
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }

        if (avcodec_parameters_copy(out_stream->codecpar, in_codecpar) < 0) {
            free(stream_mapping);
            avformat_free_context(out_ctx);
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }
        out_stream->codecpar->codec_tag = 0;
    }

    if (!(out_ctx->oformat->flags & AVFMT_NOFILE)) {
        if (avio_open(&out_ctx->pb, out_path, AVIO_FLAG_WRITE) < 0) {
            free(stream_mapping);
            avformat_free_context(out_ctx);
            avformat_close_input(&in_ctx);
            (*env)->ReleaseStringUTFChars(env, media_path, in_path);
            (*env)->ReleaseStringUTFChars(env, output_path, out_path);
            return JNI_FALSE;
        }
    }

    // Faststart flag for modern MP4 web/streaming playback
    av_dict_set(&out_ctx->metadata, "movflags", "faststart", 0);

    if (avformat_write_header(out_ctx, NULL) < 0) {
        if (out_ctx->pb) avio_closep(&out_ctx->pb);
        free(stream_mapping);
        avformat_free_context(out_ctx);
        avformat_close_input(&in_ctx);
        (*env)->ReleaseStringUTFChars(env, media_path, in_path);
        (*env)->ReleaseStringUTFChars(env, output_path, out_path);
        return JNI_FALSE;
    }

    AVPacket *pkt = av_packet_alloc();
    while (av_read_frame(in_ctx, pkt) >= 0) {
        int out_idx = stream_mapping[pkt->stream_index];
        if (out_idx >= 0 && out_idx < (int)out_ctx->nb_streams) {
            AVStream *in_stream = in_ctx->streams[pkt->stream_index];
            AVStream *out_stream = out_ctx->streams[out_idx];

            pkt->stream_index = out_idx;
            av_packet_rescale_ts(pkt, in_stream->time_base, out_stream->time_base);
            av_interleaved_write_frame(out_ctx, pkt);
        }
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);

    av_write_trailer(out_ctx);

    if (out_ctx->pb) avio_closep(&out_ctx->pb);
    free(stream_mapping);
    avformat_free_context(out_ctx);
    avformat_close_input(&in_ctx);

    (*env)->ReleaseStringUTFChars(env, media_path, in_path);
    (*env)->ReleaseStringUTFChars(env, output_path, out_path);
    return JNI_TRUE;
}


