package org.bitcoins.wallet

import org.bitcoins.core.currency.{Bitcoins, CurrencyUnits}
import org.bitcoins.core.protocol.BlockStamp
import org.bitcoins.server.BitcoinSAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.wallet.BitcoinSWalletTest
import org.bitcoins.testkit.wallet.BitcoinSWalletTest.{
  WalletWithBitcoind,
  WalletWithBitcoindV19
}
import org.scalatest.FutureOutcome

import scala.concurrent.Future

class RescanHandlingTest extends BitcoinSWalletTest {

  /** Wallet config with data directory set to user temp directory */
  implicit override protected def config: BitcoinSAppConfig =
    BitcoinSTestAppConfig.getNeutrinoTestConfig()

  override type FixtureParam = WalletWithBitcoind
  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    withFundedWalletAndBitcoindV19(test)
  }

  behavior of "Wallet rescans"

  val DEFAULT_ADDR_BATCH_SIZE = 10
  it must "be able to discover funds that belong to the wallet using WalletApi.rescanNeutrinoWallet" in {
    fixture: WalletWithBitcoind =>
      val WalletWithBitcoindV19(wallet, _) = fixture

      val initBalanceF = wallet.getBalance()

      val rescanF = for {
        initBalance <- initBalanceF
        _ = assert(
          initBalance > CurrencyUnits.zero,
          s"Cannot run rescan test if our init wallet balance is zero!")
        _ <- wallet.fullRescanNeurinoWallet(DEFAULT_ADDR_BATCH_SIZE)
        balanceAfterRescan <- wallet.getBalance()
      } yield {
        assert(balanceAfterRescan == initBalance)
      }

      rescanF
  }

  it must "be able to discover funds that occurred within a certain range" in {
    fixture: WalletWithBitcoind =>
      val WalletWithBitcoindV19(wallet, bitcoind) = fixture

      val amt = Bitcoins.one
      val numBlocks = 1

      //send funds to a fresh wallet address
      val addrF = wallet.getNewAddress()
      val bitcoindAddrF = bitcoind.getNewAddress
      val initBlockHeightF = wallet.chainQueryApi.getBestHashBlockHeight()
      val newTxWalletF = for {
        addr <- addrF
        txid <- bitcoind.sendToAddress(addr, amt)
        tx <- bitcoind.getRawTransactionRaw(txid)
        bitcoindAddr <- bitcoindAddrF
        blockHashes <- bitcoind.generateToAddress(blocks = numBlocks,
                                                  address = bitcoindAddr)
        newTxWallet <- wallet.processTransaction(transaction = tx,
                                                 blockHashOpt =
                                                   blockHashes.headOption)
        balance <- newTxWallet.getBalance()
        confirmedBalance <- newTxWallet.getConfirmedBalance()
      } yield {
        //balance doesn't have to exactly equal, as there was money in the
        //wallet before hand.
        assert(balance >= amt)
        assert(balance == confirmedBalance)
        newTxWallet
      }

      //let's clear the wallet and then do a rescan for the last numBlocks
      //that means the wallet should only contain the amt we just processed
      for {
        newTxWallet <- newTxWalletF
        initBlockHeight <- initBlockHeightF
        txInBlockHeight = initBlockHeight + numBlocks
        txInBlockHeightOpt = Some(BlockStamp.BlockHeight(txInBlockHeight))
        _ <- newTxWallet.rescanNeutrinoWallet(startOpt = txInBlockHeightOpt,
                                              endOpt = None,
                                              addressBatchSize =
                                                DEFAULT_ADDR_BATCH_SIZE)
        balance <- newTxWallet.getBalance()
      } yield {
        assert(balance == amt)
      }
  }

  it must "NOT discover funds that happened OUTSIDE of a certain range of block hashes" in {
    fixture: WalletWithBitcoind =>
      val WalletWithBitcoindV19(wallet, _) = fixture

      val initBalanceF = wallet.getBalance()

      //find the first block a utxo was created in
      val utxosF = wallet.listUtxos()
      val oldestHeightF = for {
        utxos <- utxosF
        blockhashes = utxos.map(_.blockHash)
        heights <- Future.sequence {
          blockhashes.map(h =>
            wallet.chainQueryApi.getBlockHeight(h.get).map(_.get))
        }
      } yield heights.min

      //ok now that we have the height of the oldest utxo, let's rescan up to then
      val rescanF = for {
        initBalance <- initBalanceF
        _ = assert(
          initBalance > CurrencyUnits.zero,
          s"Cannot run rescan test if our init wallet balance is zero!")
        oldestUtxoHeight <- oldestHeightF
        end = Some(BlockStamp.BlockHeight(oldestUtxoHeight - 1))
        _ <- wallet.rescanNeutrinoWallet(startOpt = BlockStamp.height0Opt,
                                         endOpt = end,
                                         addressBatchSize =
                                           DEFAULT_ADDR_BATCH_SIZE)
        balanceAfterRescan <- wallet.getBalance()
      } yield {
        assert(balanceAfterRescan == CurrencyUnits.zero)
      }

      rescanF
  }

}
