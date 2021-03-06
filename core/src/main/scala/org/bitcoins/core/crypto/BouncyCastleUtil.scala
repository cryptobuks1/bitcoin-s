package org.bitcoins.core.crypto

import java.math.BigInteger

import org.bitcoins.core.util.BitcoinSUtil
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.{
  ECPrivateKeyParameters,
  ECPublicKeyParameters
}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECCurve, ECPoint}
import scodec.bits.ByteVector

import scala.util.Try

object BouncyCastleUtil {

  private val curve: ECCurve = CryptoParams.curve.getCurve
  private val G: ECPoint = CryptoParams.curve.getG
  private val N: BigInteger = CryptoParams.curve.getN

  private def getBigInteger(bytes: ByteVector): BigInteger = {
    new BigInteger(1, bytes.toArray)
  }

  def addNumbers(num1: ByteVector, num2: ByteVector): BigInteger = {
    val bigInteger1 = getBigInteger(num1)
    val bigInteger2 = getBigInteger(num2)

    bigInteger1.add(bigInteger2).mod(N)
  }

  def decodePoint(bytes: ByteVector): ECPoint = {
    curve.decodePoint(bytes.toArray)
  }

  def validatePublicKey(bytes: ByteVector): Boolean = {
    Try(decodePoint(bytes))
      .map(_.getCurve == curve)
      .getOrElse(false)
  }

  def decompressPublicKey(publicKey: ECPublicKey): ECPublicKey = {
    if (publicKey.isCompressed) {
      val point = decodePoint(publicKey.bytes)
      val decompressedBytes =
        ByteVector.fromHex("04").get ++
          ByteVector(point.getXCoord.getEncoded) ++
          ByteVector(point.getYCoord.getEncoded)
      ECPublicKey(decompressedBytes)
    } else publicKey
  }

  def computePublicKey(privateKey: ECPrivateKey): ECPublicKey = {
    val priv = getBigInteger(privateKey.bytes)
    val point = G.multiply(priv)
    val pubBytes = ByteVector(point.getEncoded(privateKey.isCompressed))
    require(
      ECPublicKey.isFullyValid(pubBytes),
      s"Bouncy Castle failed to generate a valid public key, got: ${BitcoinSUtil
        .encodeHex(pubBytes)}")
    ECPublicKey(pubBytes)
  }

  def sign(
      dataToSign: ByteVector,
      privateKey: ECPrivateKey): ECDigitalSignature = {
    val signer: ECDSASigner = new ECDSASigner(
      new HMacDSAKCalculator(new SHA256Digest()))
    val privKey: ECPrivateKeyParameters =
      new ECPrivateKeyParameters(getBigInteger(privateKey.bytes),
                                 CryptoParams.curve)
    signer.init(true, privKey)
    val components: Array[BigInteger] =
      signer.generateSignature(dataToSign.toArray)
    val (r, s) = (components(0), components(1))
    val signature = ECDigitalSignature(r, s)
    //make sure the signature follows BIP62's low-s value
    //https://github.com/bitcoin/bips/blob/master/bip-0062.mediawiki#Low_S_values_in_signatures
    //bitcoinj implementation
    //https://github.com/bitcoinj/bitcoinj/blob/1e66b9a8e38d9ad425507bf5f34d64c5d3d23bb8/core/src/main/java/org/bitcoinj/core/ECKey.java#L551
    val signatureLowS = DERSignatureUtil.lowS(signature)
    require(
      signatureLowS.isDEREncoded,
      "We must create DER encoded signatures when signing a piece of data, got: " + signatureLowS)
    signatureLowS
  }

  def verifyDigitalSignature(
      data: ByteVector,
      publicKey: ECPublicKey,
      signature: ECDigitalSignature): Boolean = {
    val resultTry = Try {
      val publicKeyParams =
        new ECPublicKeyParameters(decodePoint(publicKey.bytes),
                                  CryptoParams.curve)

      val signer = new ECDSASigner
      signer.init(false, publicKeyParams)
      signature match {
        case EmptyDigitalSignature =>
          signer.verifySignature(data.toArray,
                                 java.math.BigInteger.valueOf(0),
                                 java.math.BigInteger.valueOf(0))
        case _: ECDigitalSignature =>
          val rBigInteger: BigInteger = new BigInteger(signature.r.toString())
          val sBigInteger: BigInteger = new BigInteger(signature.s.toString())
          signer.verifySignature(data.toArray, rBigInteger, sBigInteger)
      }
    }
    resultTry.getOrElse(false)
  }
}
