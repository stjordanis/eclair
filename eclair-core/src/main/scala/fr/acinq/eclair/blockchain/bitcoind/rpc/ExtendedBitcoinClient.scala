/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind.rpc

import fr.acinq.bitcoin._
import fr.acinq.eclair.ShortChannelId.coordinates
import fr.acinq.eclair.TxCoordinates
import fr.acinq.eclair.blockchain.ValidateResult
import fr.acinq.eclair.wire.ChannelAnnouncement
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val rpcClient: BitcoinJsonRPCClient) {

  implicit val formats = org.json4s.DefaultFormats

  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    rpcClient.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extractOrElse[Int](0)))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def getTxBlockHash(txId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    rpcClient.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => (json \ "blockhash").extractOpt[String])
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def lookForSpendingTx(blockhash_opt: Option[String], txid: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      blockhash <- blockhash_opt match {
        case Some(b) => Future.successful(b)
        case None => rpcClient.invoke("getbestblockhash") collect { case JString(b) => b }
      }
      // with a verbosity of 0, getblock returns the raw serialized block
      block <- rpcClient.invoke("getblock", blockhash, 0).collect { case JString(b) => Block.read(b) }
      prevblockhash = BinaryData(block.header.hashPreviousBlock.reverse).toString
      res <- block.tx.find(tx => tx.txIn.exists(i => i.outPoint.txid.toString() == txid && i.outPoint.index == outputIndex)) match {
        case None => lookForSpendingTx(Some(prevblockhash), txid, outputIndex)
        case Some(tx) => Future.successful(tx)
      }
    } yield res

  def getMempool()(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      txids <- rpcClient.invoke("getrawmempool").map(json => json.extract[List[String]])
      txs <- Future.sequence(txids.map(getTransaction(_)))
    } yield txs

  /**
    * @param txId
    * @param ec
    * @return
    */
  def getRawTransaction(txId: String)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("getrawtransaction", txId) collect {
      case JString(raw) => raw
    }

  def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txId).map(raw => Transaction.read(raw))

  def isTransactionOutputSpendable(txId: String, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- rpcClient.invoke("gettxout", txId, outputIndex, includeMempool)
    } yield json != JNull

  /**
    *
    * @param txId transaction id
    * @param ec
    * @return a Future[height, index] where height is the height of the block where this transaction was published, and index is
    *         the index of the transaction in that block
    */
  def getTransactionShortId(txId: String)(implicit ec: ExecutionContext): Future[(Int, Int)] = {
    val future = for {
      Some(blockHash) <- getTxBlockHash(txId)
      json <- rpcClient.invoke("getblock", blockHash)
      JInt(height) = json \ "height"
      JString(hash) = json \ "hash"
      JArray(txs) = json \ "tx"
      index = txs.indexOf(JString(txId))
    } yield (height.toInt, index)

    future
  }

  def publishTransaction(hex: String)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("sendrawtransaction", hex) collect {
      case JString(txid) => txid
    }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    publishTransaction(tx.toString())

  /**
    * We need this to compute absolute timeouts expressed in number of blocks (where getBlockCount would be equivalent
    * to time.now())
    *
    * @param ec
    * @return the current number of blocks in the active chain
    */
  def getBlockCount(implicit ec: ExecutionContext): Future[Long] =
    rpcClient.invoke("getblockcount") collect {
      case JInt(count) => count.toLong
    }

  def validate(c: ChannelAnnouncement)(implicit ec: ExecutionContext): Future[ValidateResult] = {
    val TxCoordinates(blockHeight, txIndex, outputIndex) = coordinates(c.shortChannelId)

    for {
      blockHash: String <- rpcClient.invoke("getblockhash", blockHeight).map(_.extractOrElse[String]("00" * 32))
      txid: String <- rpcClient.invoke("getblock", blockHash).map {
        case json => Try {
          val JArray(txs) = json \ "tx"
          txs(txIndex).extract[String]
        } getOrElse ("00" * 32)
      }
      tx <- getRawTransaction(txid)
      unspent <- isTransactionOutputSpendable(txid, outputIndex, includeMempool = true)
    } yield ValidateResult(c, Some(Transaction.read(tx)), unspent, None)

  } recover { case t: Throwable => ValidateResult(c, None, false, Some(t)) }

}
