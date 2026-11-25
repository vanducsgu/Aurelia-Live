package vn.aurelia.live;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ClientKeyStore {
    private static final String ALIAS = "aurelia_nova_client_key";

    private ClientKeyStore() {}

    static boolean save(SharedPreferences preferences, String value) {
        if (value.isEmpty()) {
            preferences.edit().remove("keyCiphertext").remove("keyIv").apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret(true));
            String body = Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            preferences.edit().putString("keyCiphertext", body).putString("keyIv", iv).apply();
            return true;
        } catch (GeneralSecurityException | java.io.IOException | ProviderException e) {
            // A keystore failure must never cause a plaintext preference to be written.
            preferences.edit().remove("keyCiphertext").remove("keyIv").apply();
            return false;
        }
    }

    static String load(SharedPreferences preferences) {
        String body = preferences.getString("keyCiphertext", "");
        String iv = preferences.getString("keyIv", "");
        if (body.isEmpty() || iv.isEmpty()) return "";
        try {
            SecretKey secret = secret(false);
            if (secret == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException | ProviderException e) {
            return "";
        }
    }

    private static SecretKey secret(boolean create) throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
