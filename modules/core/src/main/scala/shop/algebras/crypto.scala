package shop.algebras

import cats.effect.Sync
import cats.implicits._
import io.estatico.newtype.ops._
import javax.crypto.spec.{ PBEKeySpec, SecretKeySpec }
import javax.crypto.{ Cipher, SecretKeyFactory }
import shop.config.data.PasswordSalt
import shop.domain.auth._

trait Crypto {
  def encrypt(value: Password): EncryptedPassword
  def decrypt(value: EncryptedPassword): Password
}

object LiveCrypto {
  def make[F[_]: Sync](secret: PasswordSalt): F[Crypto] =
    Sync[F]
      .delay {
        val salt     = secret.value.value.value.getBytes("UTF-8")
        val keySpec  = new PBEKeySpec("password".toCharArray(), salt, 65536, 256)
        val factory  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val bytes    = factory.generateSecret(keySpec).getEncoded
        val sKeySpec = new SecretKeySpec(bytes, "AES")
        val eCipher  = Cipher.getInstance("AES").coerce[EncryptCipher]
        eCipher.value.init(Cipher.ENCRYPT_MODE, sKeySpec)
        val dCipher = Cipher.getInstance("AES").coerce[DecryptCipher]
        dCipher.value.init(Cipher.DECRYPT_MODE, sKeySpec)
        (eCipher, dCipher)
      }
      .map {
        case (ec, dc) =>
          new LiveCrypto(ec, dc)
      }
}

final class LiveCrypto private (
    eCipher: EncryptCipher,
    dCipher: DecryptCipher
) extends Crypto {

  // Workaround for PostgreSQL ERROR: invalid byte sequence for encoding "UTF8": 0x00
  private val Key = "=DownInAHole="

  def encrypt(password: Password): EncryptedPassword = {
    val bytes      = password.value.getBytes("UTF-8")
    val result     = new String(eCipher.value.doFinal(bytes), "UTF-8")
    val removeNull = result.replaceAll("\\u0000", Key)
    removeNull.coerce[EncryptedPassword]
  }

  def decrypt(password: EncryptedPassword): Password = {
    val bytes      = password.value.getBytes("UTF-8")
    val result     = new String(dCipher.value.doFinal(bytes), "UTF-8")
    val insertNull = result.replaceAll(Key, "\\u0000")
    insertNull.coerce[Password]
  }

}