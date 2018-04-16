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

package fr.acinq.eclair.interop

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Register.Forward
import fr.acinq.eclair.channel._
import fr.acinq.eclair.integration.IntegrationSpec
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle.{PaymentSucceeded, SendPayment}
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JInt, JString, JValue}
import org.json4s.jackson.JsonMethods._
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.sys.process._
import scala.util.Try

/*
  This test is ignored by default. To run it:
  mvn exec:java -Dexec.mainClass="org.scalatest.tools.Runner" -Dexec.classpathScope="test" \
   -Dexec.args="-o -s fr.acinq.eclair.interop.InteroperabilitySpec" \
   -Dinterop-test.lightning-path=$PATH_TO_LIGHNING

 For example:
  mvn exec:java -Dexec.mainClass="org.scalatest.tools.Runner" -Dexec.classpathScope="test" \
   -Dexec.args="-o -s fr.acinq.eclair.interop.InteroperabilitySpec" \
   -Dinterop-test.bitcoin-path=/home/fabrice/bitcoin-0.13.0/bin \
   -Dinterop-test.lightning-path=/home/fabrice/code/lightning/daemon
*/
class InteroperabilitySpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  import InteroperabilitySpec._

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.16.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null

  val PATH_LIGHTNINGD = new File(system.settings.config.getString("interop-test.lightning-path"))
  val PATH_LIGHTNINGD_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-lightningd")

  var lightningd: Process = null
  var lightningdcli: LightningCli = _
  var lightningdinfo: LightningCli.Info = _

  var nodes: Map[String, Kit] = Map()

  implicit val formats = DefaultFormats

  case class BitcoinReq(method: String, params: Any*)

  def startBitcoind: Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
      }
    }))
  }

  def startLightningd = {
    lightningd = s"$PATH_LIGHTNINGD/lightningd/lightningd --network=regtest --bitcoin-datadir=${PATH_BITCOIND_DATADIR} --lightning-dir=${PATH_LIGHTNINGD_DATADIR}".run()
    sys.addShutdownHook(lightningd.destroy())
    lightningdcli = new LightningCli(s"$PATH_LIGHTNINGD/cli/lightning-cli --lightning-dir=$PATH_LIGHTNINGD_DATADIR")
  }

  override def afterAll(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    logger.info(s"stopping bitcoind")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()

    lightningd.destroy()
  }

  def instantiateEclairNode(name: String, config: Config) = {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-eclair-$name")
    datadir.mkdirs()
    new PrintWriter(new File(datadir, "eclair.conf")) {
      write(config.root().render());
      close
    }
    val setup = new Setup(datadir, actorSystem = ActorSystem(s"system-$name"))
    val kit = Await.result(setup.bootstrap, 10 seconds)
    nodes = nodes + (name -> kit)
  }

  test("start bitcoind") {
    startBitcoind
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](30 seconds)
  }

  test("start lightningd") {
    startLightningd
    awaitCond({
      Try(lightningdcli.getinfo).map(info => lightningdinfo = info).isSuccess
    }, max = 30 seconds, interval = 500 millis)
  }

  test("start eclair") {
    import collection.JavaConversions._
    val commonConfig = ConfigFactory.parseString(
      """
        |eclair {
        |
        |  chain = "regtest"
        |
        |  server {
        |    public-ips = [ " localhost" ]
        |    port = 9735
        |  }
        |
        |  bitcoind {
        |    host = "localhost"
        |    rpcport = 28332
        |    zmq = "tcp://127.0.0.1:28334"
        |  }
        |
        |  default-feerates { // those are in satoshis per byte
        |    delay-blocks {
        |      1 = 10
        |      2 = 4
        |      6 = 3
        |      12 = 2
        |      36 = 2
        |      72 = 2
        |    }
        |  }
        |  min-feerate = 1 // minimum feerate in satoshis per byte (same default value as bitcoin core's minimum relay fee)
        |
        |  node-alias = "eclair"
        |  node-color = "49daaa"
        |  channel-flags = 1 // announce channels
        |
        |  max-htlc-value-in-flight-msat = 100000000000 // 10 mBTC
        |  htlc-minimum-msat = 1
        |  max-accepted-htlcs = 30
        |
        |  mindepth-blocks = 2
        |  expiry-delta-blocks = 144
        |
        |  router-broadcast-interval = 2 seconds
        |
        |  auto-reconnect = false
        |}
      """.stripMargin)
    instantiateEclairNode("A", ConfigFactory.parseMap(Map(
      "eclair.node-alias" -> "A",
      "eclair.delay-blocks" -> 130,
      "eclair.server.port" -> 29730,
      "eclair.api.port" -> 28080)).withFallback(commonConfig))
  }

  test("connect to lightningd") {

    val fundingSatoshis = 100000
    val pushMsat = 0
    val sender = TestProbe()
    sender.send(nodes("A").switchboard, Peer.Connect(NodeURI(
      nodeId = lightningdinfo.id,
      address = HostAndPort.fromParts("localhost", lightningdinfo.port))))
    sender.expectMsgAnyOf(10 seconds, "connected", "already connected")
    sender.send(nodes("A").switchboard, Peer.OpenChannel(
      remoteNodeId = lightningdinfo.id,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = MilliSatoshi(pushMsat),
      fundingTxFeeratePerKw_opt = None,
      channelFlags = None))
    assert(sender.expectMsgType[String](10 seconds).startsWith("created channel"))

    val numberOfChannels = 1
    val channelEndpointsCount = 1 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, 'watches)
          watches ++ sender.expectMsgType[Set[Watch]]
      }
      watches.count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    // confirming the funding tx
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)

    within(30 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == NORMAL) {
          count = count + 1
        }
      }
    }
  }

  test("send payments") {
    val pr = lightningdcli.invoice(MilliSatoshi(1000 * 1000), "label1", "something")
    val sendReq = SendPayment(pr.amount.get.toLong, pr.paymentHash, pr.nodeId)
    val sender = TestProbe()
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[PaymentSucceeded](5 seconds)
  }

  test("close channel (mutual close)") {
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    val sender = TestProbe()
    sender.send(nodes("A").register, 'channels)
    val channels = sender.expectMsgType[Map[BinaryData, ActorRef]]
    val channelId = channels.keys.head
    sender.send(nodes("A").register, Forward(channelId, CMD_CLOSE(None)))
    sender.expectMsg("ok")
    within(30 seconds) {
        eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == CLOSING
    }
    sender.send(bitcoincli, BitcoinReq("generate", 6))
    sender.expectMsgType[JValue](10 seconds)
    within(30 seconds) {
      eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == CLOSED
    }
  }
}

object InteroperabilitySpec {

  object LightningCli {

    case class Info(id: PublicKey, port: Int)
    case class Peers(peers: Seq[Peer])

    case class Peer(name: String, state: String, peerid: String, our_amount: Long, our_fee: Long, their_amount: Long, their_fee: Long)
  }

  class LightningCli(path: String) {

    import LightningCli._

    implicit val formats = org.json4s.DefaultFormats

    def getinfo: Info = {
      val raw = s"$path getinfo" !!
      val json = parse(raw)
      val JString(id) = json \ "id"
      val JInt(port)  = json \ "port"
      Info(PublicKey(BinaryData(id)), port.intValue())
    }

    def invoice(amount: MilliSatoshi, label: String, description: String) : PaymentRequest = {
      val raw = s"$path invoice ${amount.toLong} $label $description" !!
      val json = parse(raw)
      val JString(pr) = json \ "bolt11"
      PaymentRequest.read(pr)
    }

    /**
      *
      * @return a funding address that can be used to connect to another node
      */
    def fund: String = {
      val raw = s"$path newaddr" !!
      val json = parse(raw)
      val JString(address) = json \ "address"
      address
    }

    /**
      * connect to another node
      *
      * @param host node address
      * @param port node port
      * @param tx   transaction that sends money to a funding address generated with the "fund" method
      */
    def connect(host: String, port: Int, tx: String): Unit = {
      assert(s"$path connect $host $port $tx".! == 0)
    }

    def close(peerId: String): Unit = {
      assert(s"$path close $peerId".! == 0)
    }

    def getPeers: Seq[LightningCli.Peer] = {
      val raw = s"$path getpeers" !!

      parse(raw).extract[Peers].peers
    }

    def newhtlc(peerid: String, amount: Long, expiry: Long, rhash: BinaryData): Unit = {
      assert(s"$path dev-newhtlc $peerid $amount $expiry $rhash".! == 0)
    }

    def fulfillhtlc(peerid: String, htlcId: Long, rhash: BinaryData): Unit = {
      assert(s"$path dev-fulfillhtlc $peerid $htlcId $rhash".! == 0)
    }

    def commit(peerid: String): Unit = {
      assert(s"$path dev-commit $peerid".! == 0)
    }

    def devroutefail(enable: Boolean): Unit = {
      assert(s"$path dev-routefail $enable".! == 0)
    }
  }

}
