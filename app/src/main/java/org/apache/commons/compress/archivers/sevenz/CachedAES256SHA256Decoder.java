package org.apache.commons.compress.archivers.sevenz;

import com.watchpicture.app.archive.SevenZKeyCache;
import org.apache.commons.compress.PasswordRequiredException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Custom 7z AES-256 decoder hook that leverages [SevenZKeyCache] to eliminate
 * the 500ms~1500ms PBKDF2 (524,288 rounds of SHA-256) calculation on repeated stream opens.
 */
public class CachedAES256SHA256Decoder extends AbstractCoder {

    private final AbstractCoder delegate;

    public CachedAES256SHA256Decoder(AbstractCoder delegate) {
        super(AES256Options.class);
        this.delegate = delegate;
    }

    @Override
    InputStream decode(
            final String archiveName,
            final InputStream in,
            final long uncompressedLength,
            final Coder coder,
            final byte[] passwordBytes,
            final int maxMemoryLimitInKb) throws IOException {
        return new CachedAES256DecoderInputStream(in, coder, archiveName, passwordBytes);
    }

    @Override
    OutputStream encode(final OutputStream out, final Object options) throws IOException {
        if (delegate != null) {
            return delegate.encode(out, options);
        }
        throw new UnsupportedOperationException("Encode not supported without delegate");
    }

    @Override
    byte[] getOptionsAsProperties(final Object options) throws IOException {
        if (delegate != null) {
            return delegate.getOptionsAsProperties(options);
        }
        return new byte[0];
    }

    private static class CachedAES256DecoderInputStream extends InputStream {
        private final InputStream in;
        private final Coder coder;
        private final String archiveName;
        private final byte[] passwordBytes;
        private boolean isInitialized;
        private CipherInputStream cipherInputStream;

        private CachedAES256DecoderInputStream(
                final InputStream in,
                final Coder coder,
                final String archiveName,
                final byte[] passwordBytes) {
            this.in = in;
            this.coder = coder;
            this.archiveName = archiveName;
            this.passwordBytes = passwordBytes;
        }

        private synchronized CipherInputStream init() throws IOException {
            if (isInitialized) {
                return cipherInputStream;
            }
            if (coder.properties == null) {
                throw new IOException("Missing AES256 properties in " + archiveName);
            }
            if (coder.properties.length < 2) {
                throw new IOException("AES256 properties too short in " + archiveName);
            }
            final int byte0 = 0xff & coder.properties[0];
            final int numCyclesPower = byte0 & 0x3f;
            final int byte1 = 0xff & coder.properties[1];
            final int ivSize = ((byte0 >> 6 & 1) + (byte1 & 0x0f));
            final int saltSize = ((byte0 >> 7 & 1) + (byte1 >> 4));
            if (2 + saltSize + ivSize > coder.properties.length) {
                throw new IOException("Salt size + IV size too long in " + archiveName);
            }
            final byte[] salt = new byte[saltSize];
            System.arraycopy(coder.properties, 2, salt, 0, saltSize);
            final byte[] iv = new byte[16];
            System.arraycopy(coder.properties, 2 + saltSize, iv, 0, ivSize);

            if (passwordBytes == null) {
                throw new PasswordRequiredException(archiveName);
            }

            final byte[] aesKey = SevenZKeyCache.INSTANCE.getOrDerive(passwordBytes, salt, numCyclesPower);

            try {
                final SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
                final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
                cipherInputStream = new CipherInputStream(in, cipher);
                isInitialized = true;
                return cipherInputStream;
            } catch (final GeneralSecurityException generalSecurityException) {
                throw new IllegalStateException("Decryption error", generalSecurityException);
            }
        }

        @Override
        public void close() throws IOException {
            if (cipherInputStream != null) {
                cipherInputStream.close();
            } else {
                in.close();
            }
        }

        @Override
        public int read() throws IOException {
            return init().read();
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return init().read(b, off, len);
        }
    }

    private static volatile boolean installed = false;

    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            Field coderMapField = Coders.class.getDeclaredField("CODER_MAP");
            coderMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<SevenZMethod, AbstractCoder> map = (Map<SevenZMethod, AbstractCoder>) coderMapField.get(null);
            if (map != null) {
                AbstractCoder original = map.get(SevenZMethod.AES256SHA256);
                if (!(original instanceof CachedAES256SHA256Decoder)) {
                    map.put(SevenZMethod.AES256SHA256, new CachedAES256SHA256Decoder(original));
                }
            }
            installed = true;
        } catch (Exception e) {
            android.util.Log.e("CachedAES256Decoder", "Failed to install CachedAES256SHA256Decoder into Coders", e);
        }
    }
}
