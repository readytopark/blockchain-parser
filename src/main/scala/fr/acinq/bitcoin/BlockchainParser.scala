package fr.acinq.bitcoin

import java.io.{File, FileInputStream, ByteArrayInputStream, InputStream}
import akka.actor.{Props, ActorSystem, ActorLogging, Actor}
import fr.acinq.bitcoin.Script.Runner
import org.mapdb._
import grizzled.slf4j.Logging

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

object BlockchainParser extends App with Logging {

  val db = DBMaker.newFileDB(new File("utxo")).closeOnJvmShutdown().make()
  val cursor: HTreeMap[String, Int] = db.getHashMap("cursor")
  val utxos: HTreeMap[String, TxOut] = db.getHashMap("utxos")

  var prefix = ""
  var start = cursor.get("start")
  var skip = cursor.get("skip")
  var flags = 0

  class Processor extends Actor {
    val orphans = ListBuffer.empty[Block]

    def receive = {
      case block: Block => {
        logger.debug(s"received block ${block.hash} -> ${block.header.hashPreviousBlock}")
        processBlock(block, checkScripts = true)
        context become running(block.hash)
      }
    }

    def findNewTip(tip: BinaryData): BinaryData = orphans.zipWithIndex.find(_._1.header.hashPreviousBlock == tip) match {
      case None => tip
      case Some((newBlock, index)) => {
        orphans.remove(index)
        logger.debug(s"removing ${newBlock.hash} from orphans")
        processBlock(newBlock)
        findNewTip(newBlock.hash)
      }
    }

    def running(tip: BinaryData): Receive = {
      case block: Block if block.header.hashPreviousBlock != tip => {
        logger.debug(s"tip = ${tip}")
        logger.debug(s"received orphan block ${block.hash} -> ${block.header.hashPreviousBlock}")
        orphans += block
      }
      case block: Block => {
        logger.debug(s"tip = ${tip}")
        logger.debug(s"received block ${block.hash} -> ${block.header.hashPreviousBlock}")
        processBlock(block, checkScripts = true)
        val newTip = findNewTip(block.hash)
        logger.debug(s"orphans size: ${orphans.size}")
        context become running(newTip)
      }
    }
  }

  @tailrec
  def parse(arguments: Seq[String]): Unit = arguments match {
    case Nil => ()
    case "-start" :: value :: tail => start = value.toInt; parse(tail)
    case "-skip" :: value :: tail => skip = value.toInt; parse(tail)
    case "-flags" :: value :: tail => flags = value.toInt; parse(tail)
    case "-prefix" :: value :: tail => prefix = value; parse(tail)
  }

  val system = ActorSystem("parser")
  val processor = system.actorOf(Props[Processor], "processor")

  parse(args.toList)

  for (i <- start until 226) {
    if (i > start) {
      cursor.put("start", i)
      cursor.put("skip", 0)
      skip = 0
      db.commit()
    }
    val filename = prefix + blockName(i)
    logger.info(s"processing $filename")
    val istream = new FileInputStream(filename)
    logger.info(s"skipping $skip blocks")
    skipBlocks(istream, skip)
    readBlocks(istream, b => processor ! b)
    logger.info(s"number of utxos: ${utxos.size}")
  }


  def blockName(i: Int) = "blk%05d.dat".format(i)

  def skipBlock(input: InputStream): Unit = {
    val magic = uint32(input)
    assert(magic == 0xd9b4bef9L)
    val size = uint32(input)
    input.skip(size)
  }

  def skipBlocks(input: InputStream, count: Int): Unit = {
    for (i <- 0 until count) skipBlock(input)
  }

  def readBlock(input: InputStream): Block = {
    val magic = uint32(input)
    assert(magic == 0xd9b4bef9L)
    val size = uint32(input)
    val raw = new Array[Byte](size.toInt)
    input.read(raw)
    Block.read(new ByteArrayInputStream(raw))
  }

  def verif1(tx: Transaction, inputIndex: Int, txOut: TxOut): Boolean = {
    val result = ConsensusWrapper.VerifyScript(txOut.publicKeyScript, Transaction.write(tx), inputIndex, flags)
    result == 1
  }

  def verif2(tx: Transaction, inputIndex: Int, txOut: TxOut): Boolean = {
    val runner = new Runner(Script.Context(tx, inputIndex))
    runner.verifyScripts(tx.txIn(inputIndex).signatureScript, txOut.publicKeyScript)
  }

  var txCount = 0L

  def processTx(tx: Transaction, checkScripts: Boolean = false): Unit = {
    val hash = Transaction.hash(tx)
    for (index <- 0 until tx.txOut.length) {
      utxos.put(OutPoint(hash, index).toString, tx.txOut(index))
    }
    for (i <- 0 until tx.txIn.length) {
      val txIn = tx.txIn(i)
      if (!txIn.outPoint.isCoinbaseOutPoint) {
        Option(utxos.get(txIn.outPoint.toString)) match {
          case Some(txOut) =>
            Try(verif1(tx, i, txOut)) match {
              case Success(true) => ()
              case Success(false) => logger.warn(s"consensus cannot verify input $i for tx ${tx.txid}")
              case Failure(t) => logger.error(s"consensus cannot redeem input $i for tx ${tx.txid}", t)
            }
            Try(verif2(tx, i, txOut)) match {
              case Success(true) => ()
              case Success(false) => logger.warn(s"bitcoin-lib cannot verify input $i for tx ${tx.txid}")
              case Failure(t) => logger.error(s"bitcoin-lib cannot redeem input $i for tx ${tx.txid}", t)
            }
            utxos.remove(txIn.outPoint.toString)
            txCount = txCount + 1
          case None =>
            logger.warn(s"warning: cannot find txout for ${txIn.outPoint}")
        }
      }
    }
  }

  def processBlock(block: Block, checkScripts: Boolean): Unit = block.tx.map(tx => processTx(tx, checkScripts))

  def processBlock(block: Block): Unit = processBlock(block, true)

  @tailrec
  def readBlocks(input: InputStream, f: Block => Unit, count: Int = skip): Unit = {
    if (input.available() > 0) {
      if (count % 10000 == 0) {
        logger.info(s"processed $count blocks and verified $txCount transactions")
        db.commit()
      }
      val block = readBlock(input)
      f(block)
      cursor.put("skip", count)
      readBlocks(input, f, count + 1)
    }
  }
}
