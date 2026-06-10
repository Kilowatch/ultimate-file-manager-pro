/*
 * Thread-safe patch for org.apache.ftpserver.listener.nio.FtpResponseEncoder.
 *
 * ROOT CAUSE (original — thread-safety):
 *   The original class in ftpserver-core:1.2.0 uses a shared
 *       private static final CharsetEncoder ENCODER = ...
 *   CharsetEncoder is NOT thread-safe (it maintains internal encode/flush state).
 *   When two FTP clients connect concurrently and both issue commands (e.g. TYPE A),
 *   two MINA I/O threads race to call buf.putString(value, ENCODER) simultaneously,
 *   permanently corrupting the encoder's state and throwing:
 *       java.nio.charset.CoderMalfunctionError
 *
 * ROOT CAUSE (secondary — Android ICU malformed input):
 *   Android's ICU-backed UTF-8 CharsetEncoder throws:
 *       java.lang.IllegalArgumentException: uSource failed: U_ILLEGAL_ARGUMENT_ERROR
 *   when the string being encoded contains lone/unpaired UTF-16 surrogates
 *   (e.g. \uD800–\uDFFF without a matching pair). This happens when an FTP client
 *   sends a USER command with a malformed/binary username that the server echoes
 *   back in a "331 Password required for <username>" response.
 *   On standard JDK, such input returns CoderResult.MALFORMED and is handled
 *   gracefully. On Android, the ICU native layer throws IllegalArgumentException
 *   directly, bypassing the normal error-handling path entirely, so
 *   CharsetEncoder.encode() re-throws it as CoderMalfunctionError.
 *
 * FIXES:
 *   1. Replace the shared static CharsetEncoder with a ThreadLocal<CharsetEncoder>.
 *      Each I/O thread gets its own encoder instance, eliminating the race entirely.
 *   2. Call enc.reset() before every encode call to guarantee a clean state.
 *   3. Configure the encoder with CodingErrorAction.REPLACE for both malformed
 *      input and unmappable characters. This instructs Android's ICU encoder to
 *      substitute bad characters with '?' instead of throwing. Safe for FTP control
 *      responses which are ASCII-based; a '?' placeholder is far better than a crash.
 *
 * HOW THIS FILE WORKS:
 *   This class has the exact same fully-qualified name as the broken library class.
 *   Android's (Gradle / D8) build system resolves app source classes before library
 *   classes, so this file silently shadows (replaces) the broken version at build time.
 *   No library changes, no bytecode manipulation required.
 */
package org.apache.ftpserver.listener.nio;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolEncoderAdapter;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

/**
 * Encodes an {@link org.apache.ftpserver.ftplet.FtpReply} into bytes for
 * transmission over the wire. This is a thread-safe replacement for the
 * Apache FtpServer 1.2.0 version which used a shared static CharsetEncoder.
 */
public class FtpResponseEncoder extends ProtocolEncoderAdapter {

    /**
     * One CharsetEncoder per thread. CharsetEncoder is stateful and explicitly
     * NOT thread-safe, so it must never be shared across threads.
     */
    private static final ThreadLocal<CharsetEncoder> ENCODER =
            ThreadLocal.withInitial(() -> Charset.forName("UTF-8").newEncoder()
                    // REPLACE bad input instead of throwing on Android's ICU encoder.
                    // Lone/unpaired UTF-16 surrogates in FTP usernames cause
                    // IllegalArgumentException (U_ILLEGAL_ARGUMENT_ERROR) on Android,
                    // which MINA re-wraps as CoderMalfunctionError. REPLACE substitutes
                    // invalid chars with '?' and keeps the FTP server running.
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE));

    @Override
    public void encode(IoSession session, Object message,
            ProtocolEncoderOutput out) throws Exception {
        String value = message.toString();
        IoBuffer buf = IoBuffer.allocate(value.length()).setAutoExpand(true);
        // Reset the encoder before use. CharsetEncoder is stateful — if a previous
        // call on this thread left it in a non-RESET state, skipping reset() would
        // cause CoderMalfunctionError on the next invocation.
        CharsetEncoder enc = ENCODER.get();
        enc.reset();
        buf.putString(value, enc);
        buf.flip();
        out.write(buf);
    }
}
