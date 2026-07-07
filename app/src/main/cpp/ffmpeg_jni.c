#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>

#define LOG_TAG "FFmpegThumbnail"
#define LOGE(...) ((void)0)
#define LOGD(...) ((void)0)

JNIEXPORT jboolean JNICALL
Java_za_kilowatch_ultimatefilemanager_media_FFmpegThumbnailHelper_extractFrame(
        JNIEnv *env, jobject thiz, jstring video_path, jint time_percent, jobject bitmap) {

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
                // Got a decoded frame! Let's lock the bitmap pixels and scale/convert it.
                if (AndroidBitmap_lockPixels(env, bitmap, &bmp_pixels) >= 0 && bmp_pixels) {
                    enum AVPixelFormat dst_pix_fmt;
                    if (bmp_info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                        dst_pix_fmt = AV_PIX_FMT_RGBA;
                    } else {
                        dst_pix_fmt = AV_PIX_FMT_RGB565;
                    }

                    struct SwsContext *sws_ctx = sws_getContext(
                            frame->width, frame->height, codec_ctx->pix_fmt,
                            bmp_info.width, bmp_info.height, dst_pix_fmt,
                            SWS_BILINEAR, NULL, NULL, NULL);

                    if (sws_ctx) {
                        uint8_t *dst_data[4] = { (uint8_t *)bmp_pixels, NULL, NULL, NULL };
                        int dst_linesize[4] = { bmp_info.stride, 0, 0, 0 };

                        sws_scale(sws_ctx, (const uint8_t *const *)frame->data, frame->linesize,
                                  0, frame->height, dst_data, dst_linesize);

                        sws_freeContext(sws_ctx);
                        success = JNI_TRUE;
                    }
                    AndroidBitmap_unlockPixels(env, bitmap);
                }
                av_packet_unref(packet);
                break; // We only need the first decoded frame around the target seek time.
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
                    if (AndroidBitmap_lockPixels(env, bitmap, &bmp_pixels) >= 0 && bmp_pixels) {
                        enum AVPixelFormat dst_pix_fmt;
                        if (bmp_info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                            dst_pix_fmt = AV_PIX_FMT_RGBA;
                        } else {
                            dst_pix_fmt = AV_PIX_FMT_RGB565;
                        }

                        struct SwsContext *sws_ctx = sws_getContext(
                                frame->width, frame->height, codec_ctx->pix_fmt,
                                bmp_info.width, bmp_info.height, dst_pix_fmt,
                                SWS_BILINEAR, NULL, NULL, NULL);

                        if (sws_ctx) {
                            uint8_t *dst_data[4] = { (uint8_t *)bmp_pixels, NULL, NULL, NULL };
                            int dst_linesize[4] = { bmp_info.stride, 0, 0, 0 };

                            sws_scale(sws_ctx, (const uint8_t *const *)frame->data, frame->linesize,
                                      0, frame->height, dst_data, dst_linesize);

                            sws_freeContext(sws_ctx);
                            success = JNI_TRUE;
                        }
                        AndroidBitmap_unlockPixels(env, bitmap);
                    }
                    av_packet_unref(packet);
                    break;
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
