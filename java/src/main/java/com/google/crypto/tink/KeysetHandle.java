// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink;

import com.google.crypto.tink.proto.EncryptedKeyset;
import com.google.crypto.tink.proto.KeyData;
import com.google.crypto.tink.proto.KeyTemplate;
import com.google.crypto.tink.proto.Keyset;
import com.google.crypto.tink.proto.KeysetInfo;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * KeysetHandle provides abstracted access to Keysets, to limit the exposure
 * of actual protocol buffers that hold sensitive key material.
 */
public final class KeysetHandle {
  /**
   * The {@code Keyset}.
   */
  private Keyset keyset;

  private KeysetHandle(Keyset keyset) {
    this.keyset = keyset;
  }

  /**
   * @return a new {@code KeysetHandle} from a {@code keyset}.
   * @throws GeneralSecurityException
   */
  static final KeysetHandle fromKeyset(Keyset keyset)
      throws GeneralSecurityException {
    assertEnoughKeyMaterial(keyset);
    return new KeysetHandle(keyset);
  }

  /**
   * @return the actual keyset data.
   */
  Keyset getKeyset() {
    return keyset;
  }

  /**
   * Creates keyset handles from an encrypted keyset obtained via {@code reader}.
   * Users that need to load cleartext keysets can use {@code CleartextKeysetHandle}.
   * @return a new {@code KeysetHandle} from {@code encryptedKeysetProto} that was encrypted
   * with {@code masterKey}.
   * @throws GeneralSecurityException
   */
  public static final KeysetHandle fromKeysetReader(KeysetReader reader,
      Aead masterKey) throws GeneralSecurityException, IOException {
    EncryptedKeyset encryptedKeyset = reader.readEncrypted();
    assertEnoughEncryptedKeyMaterial(encryptedKeyset);
    return new KeysetHandle(decrypt(encryptedKeyset, masterKey));
  }

  /**
   * @return a new keyset handle that contains a single fresh key generated
   * according to the {@code keyTemplate}.
   * @throws GeneralSecurityException
   */
  public static final KeysetHandle generateNew(KeyTemplate keyTemplate)
      throws GeneralSecurityException {
    return KeysetManager.withEmptyKeyset()
        .rotate(keyTemplate)
        .getKeysetHandle();
  }

  /**
   * @return the {@code KeysetInfo} that doesn't contain actual key material.
   */
  public KeysetInfo getKeysetInfo() {
    return Util.getKeysetInfo(keyset);
  }

  /**
   * Serializes and writes the keyset to {@code keysetWriter}.
   */
  public void write(KeysetWriter keysetWriter) throws IOException {
    keysetWriter.write(keyset);
    return;
  }

  /**
   * Serializes, encrypts with {@code masterKey} and writes the keyset to {@code outputStream}.
   */
  public void writeEncrypted(KeysetWriter keysetWriter, Aead masterKey)
      throws GeneralSecurityException, IOException {
    EncryptedKeyset encryptedKeyset = encrypt(keyset, masterKey);
    keysetWriter.write(encryptedKeyset);
    return;
  }

  /**
   * Encrypts the keyset with the {@code Aead} master key.
   */
  private static EncryptedKeyset encrypt(Keyset keyset, Aead masterKey)
      throws GeneralSecurityException {
    byte[] encryptedKeyset = masterKey.encrypt(keyset.toByteArray(),
        /* additionalData= */new byte[0]);
    // Check if we can decrypt, to detect errors
    try {
      final Keyset keyset2 = Keyset.parseFrom(masterKey.decrypt(
          encryptedKeyset, /* additionalData= */new byte[0]));
      if (!keyset2.equals(keyset)) {
        throw new GeneralSecurityException("cannot encrypt keyset");
      }
    } catch (InvalidProtocolBufferException e) {
      throw new GeneralSecurityException("invalid keyset, corrupted key material");
    }
    return EncryptedKeyset.newBuilder()
        .setEncryptedKeyset(ByteString.copyFrom(encryptedKeyset))
        .setKeysetInfo(Util.getKeysetInfo(keyset))
        .build();
  }

  /**
   * Decrypts the encrypted keyset with the {@code Aead} master key.
   */
  private static Keyset decrypt(EncryptedKeyset encryptedKeyset, Aead masterKey)
      throws GeneralSecurityException {
    try {
      Keyset keyset = Keyset.parseFrom(masterKey.decrypt(
          encryptedKeyset.getEncryptedKeyset().toByteArray(), /* additionalData= */new byte[0]));
      // check emptiness here too, in case the encrypted keys unwrapped to nothing?
      assertEnoughKeyMaterial(keyset);
      return keyset;
    } catch (InvalidProtocolBufferException e) {
      throw new GeneralSecurityException("invalid keyset, corrupted key material");
    }
  }

  /**
   * If the managed keyset contains private keys, returns a {@code KeysetHandle}
   * of the public keys.
   */
  public KeysetHandle getPublicKeysetHandle() throws GeneralSecurityException {
    if (keyset == null) {
      throw new GeneralSecurityException("cleartext keyset is not available");
    }
    Keyset.Builder keysetBuilder = Keyset.newBuilder();
    for (Keyset.Key key : keyset.getKeyList()) {
      KeyData keyData = createPublicKeyData(key.getKeyData());
      keysetBuilder.addKey(Keyset.Key.newBuilder()
          .mergeFrom(key)
          .setKeyData(keyData)
          .build());
    }
    keysetBuilder.setPrimaryKeyId(keyset.getPrimaryKeyId());
    return new KeysetHandle(keysetBuilder.build());
  }

  private static KeyData createPublicKeyData(KeyData privateKeyData)
      throws GeneralSecurityException {
    if (privateKeyData.getKeyMaterialType() != KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE) {
      throw new GeneralSecurityException("The keyset contains a non-private key");
    }
    KeyData publicKeyData = Registry.INSTANCE.getPublicKeyData(
        privateKeyData.getTypeUrl(), privateKeyData.getValue());
    validate(publicKeyData);
    return publicKeyData;
  }

  private static void validate(KeyData keyData) throws GeneralSecurityException {
    // This will throw GeneralSecurityException if the keyData is invalid.
    Registry.INSTANCE.getPrimitive(keyData);
  }

  /**
   * Prints out the {@code KeysetInfo}.
   */
  @Override
  public String toString() {
    return getKeysetInfo().toString();
  }

  /**
   * Validate that an keyset handle contains enough key material to build a keyset on, and throws
   * otherwise.
   * @throws GeneralSecurityException
   */
  public static void assertEnoughKeyMaterial(Keyset keyset) throws GeneralSecurityException {
    if (keyset == null || keyset.getKeyCount() <= 0) {
      throw new GeneralSecurityException("empty keyset");
    }
  }

  /**
   * Validates that an encrypted keyset contains enough key material to build a keyset on,
   * and throws otherwise.
   * @throws GeneralSecurityException
   */
  public static void assertEnoughEncryptedKeyMaterial(EncryptedKeyset keyset)
      throws GeneralSecurityException {
    if (keyset == null || keyset.getEncryptedKeyset().size() == 0) {
      throw new GeneralSecurityException("empty keyset");
    }
  }
}
